//! tun2socks using lwip netstack: reads raw IP packets from the Android
//! TUN fd, routes TCP through a userspace TCP/IP stack (lwip), and
//! dispatches every accepted flow in-process via
//! `meow_tunnel::tcp::handle_tcp` — same pattern as meow-ios.
//!
//! DNS is handled in-process: UDP/53 packets are intercepted pre-stack,
//! A/AAAA queries go to `DnsServer::handle_query` for fake-IP synthesis,
//! and all other qtypes are forwarded verbatim to the pinned upstream pool.
//! No loopback DNS server socket exists.

use crate::engine;
use crate::logging;
use futures::{SinkExt, StreamExt};
use meow_common::{ConnType, Metadata, Network, ProxyConn};
use meow_dns::DnsServer;
use meow_tunnel::udp::UdpSession;
use parking_lot::Mutex;
use std::collections::HashSet;
use std::io;
use std::net::SocketAddr;
use std::os::raw::c_void;
use std::os::unix::io::RawFd;
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::{mpsc, Semaphore};
use tracing::{trace, warn};

type UdpMsg = (Vec<u8>, SocketAddr, SocketAddr);
type AnyIpPktFrame = Vec<u8>;

static TUN2SOCKS_RUNNING: AtomicBool = AtomicBool::new(false);

const DNS_BURST_CAP: usize = 256;
const DNS_TASK_TIMEOUT: Duration = Duration::from_secs(5);
static DNS_CAP_LOG_LAST_MS: AtomicU64 = AtomicU64::new(0);

const DNS_PASSTHROUGH_UPSTREAMS: &[&str] = &["119.29.29.29:53", "223.5.5.5:53"];
const DNS_PASSTHROUGH_TIMEOUT: Duration = Duration::from_secs(3);

fn warn_capped(slot: &AtomicU64, msg: &str) {
    let now_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    let last = slot.load(Ordering::Relaxed);
    if now_ms.saturating_sub(last) >= 1000
        && slot
            .compare_exchange(last, now_ms, Ordering::Relaxed, Ordering::Relaxed)
            .is_ok()
    {
        warn!("{}", msg);
    }
}

pub fn start(fd: i32, _dns_port: u16) -> Result<(), String> {
    if TUN2SOCKS_RUNNING.swap(true, Ordering::SeqCst) {
        return Err("tun2socks already running".into());
    }

    logging::bridge_log(&format!("tun2socks starting: fd={}", fd));

    unsafe {
        let flags = libc::fcntl(fd, libc::F_GETFL);
        libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
    }

    let rt = crate::get_runtime();
    rt.spawn(async move {
        if let Err(e) = run_tun2socks(fd).await {
            logging::bridge_log(&format!("tun2socks error: {}", e));
        }
        TUN2SOCKS_RUNNING.store(false, Ordering::SeqCst);
        logging::bridge_log("tun2socks exited");
    });

    Ok(())
}

pub fn stop() {
    TUN2SOCKS_RUNNING.store(false, Ordering::SeqCst);
}

// ---------------------------------------------------------------------------
// Main tun2socks loop
// ---------------------------------------------------------------------------

async fn run_tun2socks(fd: RawFd) -> io::Result<()> {
    logging::bridge_log("tun2socks: building lwip netstack");

    let (mut stack, mut tcp_listener, udp_socket) =
        lwip::NetStack::with_buffer_size(1024, 256).map_err(|e| io::Error::other(e.to_string()))?;

    let (udp_write, mut udp_read) = udp_socket.split();

    let (udp_reply_tx, mut udp_reply_rx) = mpsc::channel::<UdpMsg>(256);
    let reply_readers: Arc<Mutex<HashSet<(SocketAddr, SocketAddr)>>> =
        Arc::new(Mutex::new(HashSet::new()));

    let (stack_ingress_tx, mut stack_ingress_rx) = mpsc::channel::<AnyIpPktFrame>(256);
    let (egress_tx, mut egress_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let dns_sem = Arc::new(Semaphore::new(DNS_BURST_CAP));

    let egress_tx_stack = egress_tx.clone();
    let stack_handle = tokio::spawn(async move {
        loop {
            tokio::select! {
                pkt = stack_ingress_rx.recv() => {
                    match pkt {
                        Some(frame) => {
                            if let Err(e) = stack.send(frame).await {
                                logging::bridge_log(&format!("stack send error: {}", e));
                                break;
                            }
                        }
                        None => break,
                    }
                }
                pkt = stack.next() => {
                    match pkt {
                        Some(Ok(frame)) => { let _ = egress_tx_stack.send(frame); }
                        Some(Err(e)) => {
                            logging::bridge_log(&format!("stack recv error: {}", e));
                            break;
                        }
                        None => break,
                    }
                }
            }
        }
    });

    let tcp_accept_handle = tokio::spawn(async move {
        while let Some((stream, local_addr, remote_addr)) = tcp_listener.next().await {
            if remote_addr.port() == 53 {
                drop(stream);
                continue;
            }
            tokio::spawn(async move {
                dispatch_tcp(stream, local_addr, remote_addr).await;
            });
        }
    });

    let tun_writer_handle = tokio::spawn(async move {
        while let Some(pkt) = egress_rx.recv().await {
            let mut retries = 0u32;
            loop {
                let written = unsafe { libc::write(fd, pkt.as_ptr() as *const c_void, pkt.len()) };
                if written >= 0 {
                    break;
                }
                let errno = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
                if errno == libc::EAGAIN && retries < 3 {
                    retries += 1;
                    tokio::task::yield_now().await;
                    continue;
                }
                break;
            }
        }
    });

    // UDP reply writer: serialises replies from per-session readers into
    // lwip's UdpWriteHalf.
    let udp_writer_handle = tokio::spawn(async move {
        let udp_write = udp_write;
        while let Some(msg) = udp_reply_rx.recv().await {
            if let Err(e) = udp_write.send_to(&msg.0, &msg.1, &msg.2) {
                logging::bridge_log(&format!("tun2socks: UDP reply send error: {}", e));
                break;
            }
        }
    });

    // UDP accept: non-DNS UDP flows dispatched via meow_tunnel::udp.
    let udp_reply_tx_accept = udp_reply_tx.clone();
    let reply_readers_accept = reply_readers.clone();
    let udp_accept_handle = tokio::spawn(async move {
        while let Some((payload, src, dst)) = udp_read.next().await {
            let reply_tx = udp_reply_tx_accept.clone();
            let readers = reply_readers_accept.clone();
            tokio::spawn(async move {
                dispatch_udp(payload, src, dst, reply_tx, readers).await;
            });
        }
    });

    // TUN reader: reads raw IP packets, intercepts DNS pre-stack.
    let tun_reader_handle = tokio::spawn(async move {
        let mut read_buf = vec![0u8; 65535];

        loop {
            if !TUN2SOCKS_RUNNING.load(Ordering::SeqCst) {
                break;
            }

            tokio::task::yield_now().await;

            let mut did_work = false;
            loop {
                let n =
                    unsafe { libc::read(fd, read_buf.as_mut_ptr() as *mut c_void, read_buf.len()) };
                if n <= 0 {
                    break;
                }
                did_work = true;
                let n = n as usize;
                let ip_data = &read_buf[..n];

                // In-process DNS: intercept UDP/53 pre-stack.
                if parse_udp_packet(ip_data).is_some_and(|p| p.dst_port == 53) {
                    let permit = match dns_sem.clone().try_acquire_owned() {
                        Ok(p) => p,
                        Err(_) => {
                            warn_capped(
                                &DNS_CAP_LOG_LAST_MS,
                                "tun2socks: DNS burst cap reached, dropping query",
                            );
                            continue;
                        }
                    };
                    let request = ip_data.to_vec();
                    let egress = egress_tx.clone();
                    tokio::spawn(async move {
                        let _permit = permit;
                        let work = async {
                            let Some(parsed) = parse_udp_packet(&request) else {
                                return;
                            };
                            let qtype = parse_dns_qtype(parsed.payload);

                            let response_payload = if matches!(qtype, Some(1) | Some(28)) {
                                let Some(resolver) = crate::DNS_RESOLVER.get() else {
                                    trace!("tun2socks: DNS dropped — resolver not ready");
                                    return;
                                };
                                match DnsServer::handle_query(parsed.payload, resolver).await {
                                    Ok(bytes) => bytes,
                                    Err(e) => {
                                        trace!("tun2socks: DnsServer::handle_query error: {}", e);
                                        return;
                                    }
                                }
                            } else {
                                match forward_dns_to_upstream(
                                    parsed.payload,
                                    DNS_PASSTHROUGH_UPSTREAMS,
                                    DNS_PASSTHROUGH_TIMEOUT,
                                )
                                .await
                                {
                                    Some(bytes) => bytes,
                                    None => {
                                        trace!(
                                            "tun2socks: DNS passthrough timed out (qtype={:?})",
                                            qtype
                                        );
                                        return;
                                    }
                                }
                            };
                            let Some(reply_pkt) = build_udp_reply(&request, &response_payload)
                            else {
                                return;
                            };
                            let _ = egress.send(reply_pkt);
                        };
                        if tokio::time::timeout(DNS_TASK_TIMEOUT, work).await.is_err() {
                            trace!(
                                "tun2socks: DNS task exceeded {:?}, aborting",
                                DNS_TASK_TIMEOUT
                            );
                        }
                    });
                    continue;
                }

                let frame: AnyIpPktFrame = ip_data.to_vec();
                match stack_ingress_tx.try_send(frame) {
                    Ok(()) => {}
                    Err(mpsc::error::TrySendError::Full(frame)) => {
                        let _ = stack_ingress_tx.send(frame).await;
                    }
                    Err(mpsc::error::TrySendError::Closed(_)) => break,
                }
            }

            if !did_work {
                tokio::time::sleep(Duration::from_micros(200)).await;
            }
        }
    });

    let _ = tun_reader_handle.await;

    stack_handle.abort();
    tcp_accept_handle.abort();
    udp_accept_handle.abort();
    udp_writer_handle.abort();
    tun_writer_handle.abort();
    drop(udp_reply_tx);

    logging::bridge_log("tun2socks: exiting");
    Ok(())
}

// ---------------------------------------------------------------------------
// TCP dispatch
// ---------------------------------------------------------------------------

async fn dispatch_tcp(stream: lwip::TcpStream, src_addr: SocketAddr, dst_addr: SocketAddr) {
    let tunnel = match engine::tunnel() {
        Some(t) => t,
        None => {
            warn!(
                "tun2socks: TCP {} -> {} dropped: engine not running",
                src_addr, dst_addr
            );
            return;
        }
    };

    tracing::debug!("tun2socks: dispatch {} -> {}", src_addr, dst_addr);

    let metadata = Metadata {
        network: Network::Tcp,
        conn_type: ConnType::Inner,
        src_ip: Some(src_addr.ip()),
        src_port: src_addr.port(),
        dst_ip: Some(dst_addr.ip()),
        dst_port: dst_addr.port(),
        ..Default::default()
    };

    let proxy_conn: Box<dyn ProxyConn> = Box::new(NetstackConn(stream));
    let inner = tunnel.inner().clone();
    meow_tunnel::tcp::handle_tcp(&inner, proxy_conn, metadata).await;
    tracing::debug!("tun2socks: flow done {} -> {}", src_addr, dst_addr);
}

struct NetstackConn(lwip::TcpStream);

impl AsyncRead for NetstackConn {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        Pin::new(&mut self.0).poll_read(cx, buf)
    }
}

impl AsyncWrite for NetstackConn {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.0).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.0).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.0).poll_shutdown(cx)
    }
}

impl ProxyConn for NetstackConn {}

// ---------------------------------------------------------------------------
// UDP dispatch
// ---------------------------------------------------------------------------

async fn dispatch_udp(
    payload: Vec<u8>,
    src: SocketAddr,
    dst: SocketAddr,
    reply_tx: mpsc::Sender<UdpMsg>,
    reply_readers: Arc<Mutex<HashSet<(SocketAddr, SocketAddr)>>>,
) {
    let Some(tunnel) = engine::tunnel() else {
        return;
    };

    let mut metadata = Metadata {
        network: Network::Udp,
        conn_type: ConnType::Inner,
        src_ip: Some(src.ip()),
        src_port: src.port(),
        dst_ip: Some(dst.ip()),
        dst_port: dst.port(),
        ..Default::default()
    };

    tunnel.inner().pre_handle_metadata(&mut metadata);
    tunnel.inner().pre_resolve(&mut metadata).await;
    let Some(resolved_ip) = metadata.dst_ip else {
        return;
    };
    let key = (src, SocketAddr::new(resolved_ip, metadata.dst_port));

    meow_tunnel::udp::handle_udp(tunnel.inner(), &payload, src, metadata).await;

    if !reply_readers.lock().insert(key) {
        return;
    }

    let inner = tunnel.inner().clone();
    let Some(session) = inner.nat_table.get(&key).map(|r| r.value().clone()) else {
        reply_readers.lock().remove(&key);
        return;
    };

    spawn_udp_reply_reader(key, session, src, dst, reply_tx, reply_readers, inner);
}

fn spawn_udp_reply_reader(
    key: (SocketAddr, SocketAddr),
    session: Arc<UdpSession>,
    app_src: SocketAddr,
    app_dst: SocketAddr,
    reply_tx: mpsc::Sender<UdpMsg>,
    reply_readers: Arc<Mutex<HashSet<(SocketAddr, SocketAddr)>>>,
    tunnel_inner: Arc<meow_tunnel::tunnel::TunnelInner>,
) {
    tokio::spawn(async move {
        let mut buf = vec![0u8; 4 * 1024];
        while let Ok((n, _from)) = session.conn.read_packet(&mut buf).await {
            let msg: UdpMsg = (buf[..n].to_vec(), app_dst, app_src);
            if reply_tx.try_send(msg).is_err() {
                break;
            }
        }
        tunnel_inner.nat_table.remove(&key);
        reply_readers.lock().remove(&key);
    });
}

// ---------------------------------------------------------------------------
// DNS passthrough for non-A/AAAA qtypes
// ---------------------------------------------------------------------------

async fn forward_dns_to_upstream(
    query: &[u8],
    upstreams: &[&str],
    timeout: Duration,
) -> Option<Vec<u8>> {
    if upstreams.is_empty() || query.len() < 2 {
        return None;
    }
    let query_id = u16::from_be_bytes([query[0], query[1]]);
    let query_owned = query.to_vec();

    type DnsForwardFut = Pin<Box<dyn std::future::Future<Output = Option<Vec<u8>>> + Send>>;
    let mut futs: Vec<DnsForwardFut> = Vec::with_capacity(upstreams.len());
    for upstream in upstreams {
        let Ok(addr) = upstream.parse::<SocketAddr>() else {
            continue;
        };
        let q = query_owned.clone();
        futs.push(Box::pin(async move {
            use tokio::io::{AsyncReadExt, AsyncWriteExt};
            let stream = meow_common::connect_tcp(addr).await.ok()?;
            let mut stream = tokio::io::BufStream::new(stream);
            let len = u16::try_from(q.len()).ok()?;
            stream.write_all(&len.to_be_bytes()).await.ok()?;
            stream.write_all(&q).await.ok()?;
            stream.flush().await.ok()?;
            let mut len_buf = [0u8; 2];
            tokio::time::timeout(timeout, stream.read_exact(&mut len_buf))
                .await
                .ok()?
                .ok()?;
            let resp_len = u16::from_be_bytes(len_buf) as usize;
            let mut buf = vec![0u8; resp_len];
            stream.read_exact(&mut buf).await.ok()?;
            if buf.len() >= 2 && u16::from_be_bytes([buf[0], buf[1]]) == query_id {
                Some(buf)
            } else {
                None
            }
        }));
    }
    while !futs.is_empty() {
        let (result, _idx, remaining) = futures::future::select_all(futs).await;
        if result.is_some() {
            return result;
        }
        futs = remaining;
    }
    None
}

// ---------------------------------------------------------------------------
// DNS helpers
// ---------------------------------------------------------------------------

fn parse_dns_qtype(payload: &[u8]) -> Option<u16> {
    if payload.len() < 12 {
        return None;
    }
    let qdcount = u16::from_be_bytes([payload[4], payload[5]]);
    if qdcount == 0 {
        return None;
    }
    let mut pos = 12usize;
    loop {
        let len = *payload.get(pos)? as usize;
        if len == 0 {
            pos = pos.checked_add(1)?;
            break;
        }
        if len & 0xC0 == 0xC0 {
            pos = pos.checked_add(2)?;
            break;
        }
        pos = pos.checked_add(1 + len)?;
    }
    let hi = *payload.get(pos)?;
    let lo = *payload.get(pos.checked_add(1)?)?;
    Some(u16::from_be_bytes([hi, lo]))
}

// ---------------------------------------------------------------------------
// UDP / IP packet helpers
// ---------------------------------------------------------------------------

struct ParsedUdp<'a> {
    #[allow(dead_code)]
    src_ip: [u8; 4],
    #[allow(dead_code)]
    src_port: u16,
    #[allow(dead_code)]
    dst_ip: [u8; 4],
    dst_port: u16,
    payload: &'a [u8],
}

fn parse_udp_packet(ip_data: &[u8]) -> Option<ParsedUdp<'_>> {
    if ip_data.len() < 28 {
        return None;
    }
    if (ip_data[0] >> 4) != 4 {
        return None;
    }
    if ip_data[9] != 17 {
        return None;
    }
    let ihl = (ip_data[0] & 0x0F) as usize * 4;
    if ip_data.len() < ihl + 8 {
        return None;
    }
    let src_ip = [ip_data[12], ip_data[13], ip_data[14], ip_data[15]];
    let dst_ip = [ip_data[16], ip_data[17], ip_data[18], ip_data[19]];
    let src_port = u16::from_be_bytes([ip_data[ihl], ip_data[ihl + 1]]);
    let dst_port = u16::from_be_bytes([ip_data[ihl + 2], ip_data[ihl + 3]]);
    let udp_len = u16::from_be_bytes([ip_data[ihl + 4], ip_data[ihl + 5]]) as usize;
    let start = ihl + 8;
    let end = (ihl + udp_len).min(ip_data.len());
    if start > end {
        return None;
    }
    Some(ParsedUdp {
        src_ip,
        src_port,
        dst_ip,
        dst_port,
        payload: &ip_data[start..end],
    })
}

fn build_udp_reply(orig_ip_data: &[u8], reply_payload: &[u8]) -> Option<Vec<u8>> {
    if orig_ip_data.len() < 28 || (orig_ip_data[0] >> 4) != 4 || orig_ip_data[9] != 17 {
        return None;
    }
    let ihl = (orig_ip_data[0] & 0x0F) as usize * 4;
    if ihl < 20 || orig_ip_data.len() < ihl + 8 {
        return None;
    }
    let total_len = 20u16
        .checked_add(8)
        .and_then(|n| n.checked_add(u16::try_from(reply_payload.len()).ok()?))?;
    let udp_len = 8u16.checked_add(u16::try_from(reply_payload.len()).ok()?)?;

    let mut pkt = Vec::with_capacity(usize::from(total_len));
    pkt.push(0x45);
    pkt.push(0x00);
    pkt.extend_from_slice(&total_len.to_be_bytes());
    pkt.extend_from_slice(&[0, 0]);
    pkt.extend_from_slice(&[0x40, 0x00]);
    pkt.push(64);
    pkt.push(17);
    pkt.extend_from_slice(&[0, 0]);
    pkt.extend_from_slice(&orig_ip_data[16..20]); // src = original dst
    pkt.extend_from_slice(&orig_ip_data[12..16]); // dst = original src

    let cksum = ipv4_header_checksum(&pkt[0..20]);
    pkt[10..12].copy_from_slice(&cksum.to_be_bytes());

    pkt.extend_from_slice(&orig_ip_data[ihl + 2..ihl + 4]); // src port = original dst port
    pkt.extend_from_slice(&orig_ip_data[ihl..ihl + 2]); // dst port = original src port
    pkt.extend_from_slice(&udp_len.to_be_bytes());
    pkt.extend_from_slice(&[0, 0]);
    pkt.extend_from_slice(reply_payload);

    Some(pkt)
}

fn ipv4_header_checksum(h: &[u8]) -> u16 {
    let mut s: u32 = 0;
    for i in (0..h.len()).step_by(2) {
        s += if i + 1 < h.len() {
            ((h[i] as u32) << 8) | h[i + 1] as u32
        } else {
            (h[i] as u32) << 8
        };
    }
    while s >> 16 != 0 {
        s = (s & 0xFFFF) + (s >> 16);
    }
    !s as u16
}
