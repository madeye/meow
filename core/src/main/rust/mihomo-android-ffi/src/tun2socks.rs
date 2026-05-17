//! tun2socks using netstack-smoltcp: reads raw IP packets from the Android
//! TUN fd, routes TCP through a userspace TCP/IP stack (smoltcp), and
//! dispatches every accepted flow in-process via
//! `mihomo_tunnel::tcp::handle_tcp` — same pattern meow-ios uses, with no
//! SOCKS5 loopback hop.
//!
//! This module has no DNS-specific logic. Every in-TUN UDP/53 datagram is
//! forwarded through `mihomo_tunnel::udp::handle_udp` with its destination
//! rewritten to mihomo's bound `DnsServer` (loopback, pinned by
//! `engine::pinned_dns_block`). Fake-IP synthesis, reverse mapping,
//! upstream resolution, hosts, NXDOMAIN — all DNS handling lives inside
//! mihomo's tunnel and resolver, not in the FFI.
//!
//! TCP/UDP destination IPs returned to apps are fake-IPs from mihomo's
//! resolver pool. The dispatch path passes the literal `dst.ip()` to
//! `mihomo_tunnel`, whose `pre_handle_metadata` reverses any fake-IP back to
//! the qname the resolver originally returned, so SNI-aware proxy adapters
//! (Trojan, HTTP/Host, VLESS) see the real hostname without any local table.

use crate::engine;
use crate::logging;
use futures::{SinkExt, StreamExt};
use mihomo_common::{ConnType, Metadata, Network, ProxyConn};
use mihomo_tunnel::tunnel::TunnelInner;
use mihomo_tunnel::udp::UdpSession;
use parking_lot::Mutex;
use std::collections::HashSet;
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::os::raw::c_void;
use std::os::unix::io::RawFd;
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::{mpsc, Semaphore};
use tracing::warn;

use netstack_smoltcp::{AnyIpPktFrame, StackBuilder};

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

static TUN2SOCKS_RUNNING: AtomicBool = AtomicBool::new(false);

/// Burst cap: defensive backstop against the "bursty-on-flow" pattern that
/// lets a reconnect storm (DNS lookups + pent-up connect attempts from every
/// backgrounded app) consume excess memory before any flow completes. Drops
/// at the accept boundary; peers see RST / packet loss for a few hundred ms
/// instead of OOM. Mirrors the iOS DNS_BURST_CAP guard.
const DNS_BURST_CAP: usize = 256;

static DNS_CAP_LOG_LAST_MS: AtomicU64 = AtomicU64::new(0);

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
//
// Key design: the Stack is NOT split. It implements both Sink (ingress) and
// Stream (egress). futures::SplitSink/SplitStream use a BiLock that prevents
// concurrent use — causing deadlocks when ingress and egress run on separate
// tasks. Instead, the TUN reader feeds packets via an mpsc channel to a
// single "stack driver" task that owns the Stack and multiplexes both
// directions.
// ---------------------------------------------------------------------------

async fn run_tun2socks(fd: RawFd) -> io::Result<()> {
    logging::bridge_log("tun2socks: building netstack-smoltcp stack");

    let (mut stack, tcp_runner, _udp_socket, tcp_listener) = StackBuilder::default()
        .enable_tcp(true)
        .enable_udp(false)
        .stack_buffer_size(1024)
        .tcp_buffer_size(512)
        .build()?;

    let tcp_runner = tcp_runner.expect("TCP runner");
    let mut tcp_listener = tcp_listener.expect("TCP listener");

    logging::bridge_log("tun2socks: starting tasks");

    let (ingress_tx, mut ingress_rx) = mpsc::channel::<AnyIpPktFrame>(256);
    let (egress_tx, mut egress_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let dns_sem = Arc::new(Semaphore::new(DNS_BURST_CAP));

    let runner_handle = tokio::spawn(async move {
        if let Err(e) = tcp_runner.await {
            logging::bridge_log(&format!("tun2socks: TCP runner error: {}", e));
        }
    });

    let egress_tx2 = egress_tx.clone();
    let stack_handle = tokio::spawn(async move {
        loop {
            tokio::select! {
                pkt = ingress_rx.recv() => {
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
                        Some(Ok(frame)) => { let _ = egress_tx2.send(frame); }
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
            tokio::spawn(async move {
                handle_tcp_stream(stream, local_addr, remote_addr).await;
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

    // Reply readers for in-flight UDP/53 sessions, keyed by the
    // mihomo-tunnel NAT key (matches what `mihomo_tunnel::udp::handle_udp`
    // inserts into `nat_table`). Prevents spawning a second reader for the
    // same flow when the app retransmits before mihomo's reply arrives.
    let dns_reply_readers: Arc<Mutex<HashSet<(SocketAddr, SocketAddr)>>> =
        Arc::new(Mutex::new(HashSet::new()));

    let udp_reply_tx = egress_tx.clone();
    let dns_reply_readers_reader = dns_reply_readers.clone();
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

                // App UDP/53 queries arrive addressed to the TUN-subnet DNS
                // IP (172.19.0.2). Dispatch through mihomo's UDP tunnel with
                // the destination rewritten to mihomo's loopback DnsServer.
                // tun2socks itself never parses DNS payloads — all DNS
                // handling lives inside mihomo.
                //
                // The intercept is gated on `dst_ip == 172.19.0.2` so that
                // UDP/53 packets the engine emits *out* (upstream nameserver
                // queries from its own protected socket) can never round-trip
                // back into the tunnel via this path.
                if let Some(parsed) = parse_udp_packet(ip_data) {
                    if parsed.dst_port == 53 && parsed.dst_ip == [172, 19, 0, 2] {
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
                        let reply_tx = udp_reply_tx.clone();
                        let readers = dns_reply_readers_reader.clone();
                        tokio::spawn(async move {
                            let _permit = permit;
                            dispatch_dns_via_tunnel(request, reply_tx, readers).await;
                        });
                        continue;
                    }
                }

                let frame: AnyIpPktFrame = ip_data.to_vec();
                match ingress_tx.try_send(frame) {
                    Ok(()) => {}
                    Err(mpsc::error::TrySendError::Full(frame)) => {
                        let _ = ingress_tx.send(frame).await;
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

    runner_handle.abort();
    stack_handle.abort();
    tcp_accept_handle.abort();
    tun_writer_handle.abort();

    logging::bridge_log("tun2socks: exiting");
    Ok(())
}

// ---------------------------------------------------------------------------
// TCP → in-process mihomo_tunnel dispatch
//
// One netstack flow → one `Metadata` → one `mihomo_tunnel::tcp::handle_tcp`
// invocation. `dst_ip` is passed as-is (no local host table): mihomo's
// `pre_handle_metadata` reverses fake-IPs back to the qname the resolver
// returned, so SNI-aware proxy adapters see the real hostname.
// ---------------------------------------------------------------------------

async fn handle_tcp_stream(
    stream: netstack_smoltcp::TcpStream,
    src_addr: SocketAddr,
    dst_addr: SocketAddr,
) {
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
    mihomo_tunnel::tcp::handle_tcp(&inner, proxy_conn, metadata).await;
    tracing::debug!("tun2socks: flow done {} -> {}", src_addr, dst_addr);
}

/// `ProxyConn` requires an orphan-rule-friendly local type, so we wrap
/// netstack's `TcpStream` in a newtype. The blanket `AsyncRead`/`AsyncWrite`
/// impls are forwarded straight through; netstack already provides them.
struct NetstackConn(netstack_smoltcp::TcpStream);

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
// UDP/53 → mihomo tunnel dispatch
//
// One in-TUN DNS query → one `mihomo_tunnel::udp::handle_udp` call with the
// destination rewritten to mihomo's bound loopback `DnsServer`. The tunnel's
// rule engine matches the loopback address, dials DIRECT UDP, and inserts
// the session into the NAT table; we then spawn a reply reader that pulls
// the resolver's response and writes a matching UDP IP frame back to the
// TUN. No DNS payload parsing happens in this module.
// ---------------------------------------------------------------------------

/// Where mihomo's `DnsServer` listens. Must match `engine::pinned_dns_block`'s
/// `dns.listen` field exactly.
const MIHOMO_DNS_LISTEN: SocketAddr =
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), 1053);

async fn dispatch_dns_via_tunnel(
    request: Vec<u8>,
    reply_tx: mpsc::UnboundedSender<Vec<u8>>,
    reply_readers: Arc<Mutex<HashSet<(SocketAddr, SocketAddr)>>>,
) {
    let Some(parsed) = parse_udp_packet(&request) else {
        return;
    };
    let Some(tunnel) = engine::tunnel() else {
        warn!("tun2socks: DNS dropped — engine not running");
        return;
    };

    // App-side endpoints (preserved for the reply frame).
    let app_src = SocketAddr::new(IpAddr::V4(Ipv4Addr::from(parsed.src_ip)), parsed.src_port);
    let app_dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::from(parsed.dst_ip)), parsed.dst_port);

    // Rewrite the dispatch destination to mihomo's bound DnsServer. The NAT
    // key handle_udp installs is keyed off this rewritten address; the reply
    // reader rewrites the response frame's apparent source back to `app_dst`
    // so the app sees the answer come from the resolver it queried.
    let dispatch_dst = MIHOMO_DNS_LISTEN;
    let payload = parsed.payload.to_vec();
    let metadata = Metadata {
        network: Network::Udp,
        conn_type: ConnType::Inner,
        src_ip: Some(app_src.ip()),
        src_port: app_src.port(),
        dst_ip: Some(dispatch_dst.ip()),
        dst_port: dispatch_dst.port(),
        ..Default::default()
    };

    let inner = tunnel.inner().clone();
    mihomo_tunnel::udp::handle_udp(&inner, &payload, app_src, metadata).await;

    let key = (app_src, dispatch_dst);
    if !reply_readers.lock().insert(key) {
        return;
    }
    let Some(session) = inner.nat_table.get(&key).map(|r| r.value().clone()) else {
        // handle_udp bailed before the NAT insert (no rule / dial error).
        reply_readers.lock().remove(&key);
        return;
    };

    spawn_dns_reply_reader(
        key,
        session,
        app_src,
        app_dst,
        reply_tx,
        reply_readers,
        inner,
    );
}

fn spawn_dns_reply_reader(
    key: (SocketAddr, SocketAddr),
    session: Arc<UdpSession>,
    app_src: SocketAddr,
    app_dst: SocketAddr,
    reply_tx: mpsc::UnboundedSender<Vec<u8>>,
    reply_readers: Arc<Mutex<HashSet<(SocketAddr, SocketAddr)>>>,
    tunnel_inner: Arc<TunnelInner>,
) {
    tokio::spawn(async move {
        // 4 KiB is enough for any DNS UDP datagram that survives the 1500-MTU
        // TUN without fragmentation; oversized replies are truncated, which
        // matches the on-wire reality (TC bit handling is mihomo's problem,
        // not ours).
        let mut buf = vec![0u8; 4 * 1024];
        while let Ok((n, _from)) = session.conn.read_packet(&mut buf).await {
            let Some(frame) = build_udp_reply_with_endpoints(app_dst, app_src, &buf[..n]) else {
                continue;
            };
            if reply_tx.send(frame).is_err() {
                break;
            }
        }
        tunnel_inner.nat_table.remove(&key);
        reply_readers.lock().remove(&key);
    });
}

// ---------------------------------------------------------------------------
// UDP / IP parsing helpers
// ---------------------------------------------------------------------------

struct ParsedUdp<'a> {
    src_ip: [u8; 4],
    src_port: u16,
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

/// Build a UDP-over-IPv4 frame from explicit endpoints. UDP checksum left
/// at 0 (legal for IPv4 per RFC 768); IPv4 header checksum is computed.
/// Used by the DNS reply reader to inject mihomo's response back into the
/// TUN as if it came straight from the resolver the app queried.
fn build_udp_reply_with_endpoints(
    src: SocketAddr,
    dst: SocketAddr,
    payload: &[u8],
) -> Option<Vec<u8>> {
    let SocketAddr::V4(src) = src else {
        return None;
    };
    let SocketAddr::V4(dst) = dst else {
        return None;
    };
    let total_len = 20u16
        .checked_add(8)
        .and_then(|n| n.checked_add(u16::try_from(payload.len()).ok()?))?;
    let udp_len = 8u16.checked_add(u16::try_from(payload.len()).ok()?)?;

    let mut pkt = Vec::with_capacity(usize::from(total_len));
    pkt.push(0x45);
    pkt.push(0x00);
    pkt.extend_from_slice(&total_len.to_be_bytes());
    pkt.extend_from_slice(&[0, 0]);
    pkt.extend_from_slice(&[0x40, 0x00]);
    pkt.push(64);
    pkt.push(17);
    pkt.extend_from_slice(&[0, 0]);
    pkt.extend_from_slice(&src.ip().octets());
    pkt.extend_from_slice(&dst.ip().octets());

    let cksum = ipv4_header_checksum(&pkt[0..20]);
    pkt[10..12].copy_from_slice(&cksum.to_be_bytes());

    pkt.extend_from_slice(&src.port().to_be_bytes());
    pkt.extend_from_slice(&dst.port().to_be_bytes());
    pkt.extend_from_slice(&udp_len.to_be_bytes());
    pkt.extend_from_slice(&[0, 0]);
    pkt.extend_from_slice(payload);

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
