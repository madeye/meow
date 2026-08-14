//! tun2socks using lwip netstack: reads raw IP packets from the Android
//! TUN fd, routes TCP through a userspace TCP/IP stack (lwip), and
//! dispatches every accepted flow in-process via
//! `meow_tunnel::tcp::handle_tcp` — same pattern as meow-ios.
//!
//! DNS is handled in-process: UDP/53 packets are intercepted pre-stack and
//! handed to `DnsServer::handle_query`, which uses the configured resolver
//! for both address and generic record types. No loopback DNS socket exists.

use crate::engine;
use crate::logging;
use futures::{SinkExt, StreamExt};
use meow_common::{ConnType, Metadata, Network, ProxyConn};
use meow_dns::DnsServer;
use meow_tunnel::udp::UdpSession;
use parking_lot::{const_mutex, Mutex};
use std::collections::HashSet;
use std::io;
use std::net::SocketAddr;
use std::os::raw::c_void;
use std::os::unix::io::{AsRawFd, RawFd};
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::unix::AsyncFd;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};
use tokio::sync::{mpsc, Notify, Semaphore};
use tokio::task::JoinHandle;
use tracing::{trace, warn};

type UdpMsg = (Vec<u8>, SocketAddr, SocketAddr);
type AnyIpPktFrame = Vec<u8>;
type FlowTasks = Arc<Mutex<Vec<JoinHandle<()>>>>;

static TUN2SOCKS_ACTIVE: AtomicBool = AtomicBool::new(false);
static TUN2SOCKS_STOP_REQUESTED: AtomicBool = AtomicBool::new(false);
// JoinHandle of the current run task, so a rapid stop->start can wait for the
// previous teardown to finish before flipping ACTIVE (see start).
static TUN2SOCKS_RUN_HANDLE: Mutex<Option<JoinHandle<()>>> = const_mutex(None);
// Per-run stop signal: stop() notifies the current run's reader so it wakes
// from AsyncFd::readable immediately instead of blocking until the next packet.
static STOP_NOTIFY_SLOT: Mutex<Option<Arc<Notify>>> = const_mutex(None);

const DNS_BURST_CAP: usize = 256;
const DNS_TASK_TIMEOUT: Duration = Duration::from_secs(5);
static DNS_CAP_LOG_LAST_MS: AtomicU64 = AtomicU64::new(0);
const TUN_EGRESS_CAP: usize = 256;

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

/// Wrapper around a raw TUN fd for AsyncFd. It only lends the fd to
/// tokio's IO driver for readiness notifications; it never owns/closes the
/// fd (the Android VpnService manages the fd lifecycle).
struct TunFd(RawFd);

impl AsRawFd for TunFd {
    fn as_raw_fd(&self) -> RawFd {
        self.0
    }
}

pub fn start(fd: i32, _dns_port: u16) -> Result<(), String> {
    // If a previous run is still tearing down (stop() flipped the flag but the
    // spawned task hasn't finished aborting flows / awaiting lwIP yet), wait
    // for it to finish - bounded - before flipping ACTIVE. Without this, a
    // rapid VPN toggle races the previous teardown and start() returns
    // "already running", failing the reconnect.
    {
        let prev = TUN2SOCKS_RUN_HANDLE.lock().take();
        if let Some(handle) = prev {
            let rt = crate::get_runtime();
            let _ = rt
                .block_on(async { tokio::time::timeout(Duration::from_millis(800), handle).await });
        }
    }

    if TUN2SOCKS_ACTIVE
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return Err("tun2socks already running".into());
    }
    TUN2SOCKS_STOP_REQUESTED.store(false, Ordering::SeqCst);

    logging::bridge_log(&format!("tun2socks starting: fd={}", fd));

    unsafe {
        let flags = libc::fcntl(fd, libc::F_GETFL);
        libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
    }

    // Fresh per-run stop signal so a latched permit from a previous run can't
    // bleed into the next one (Notify permits are per-instance).
    let stop_notify = Arc::new(Notify::new());
    {
        *STOP_NOTIFY_SLOT.lock() = Some(stop_notify.clone());
    }

    let rt = crate::get_runtime();
    let run_handle = rt.spawn(async move {
        if let Err(e) = run_tun2socks(fd, stop_notify).await {
            logging::bridge_log(&format!("tun2socks error: {}", e));
        }
        TUN2SOCKS_STOP_REQUESTED.store(false, Ordering::SeqCst);
        TUN2SOCKS_ACTIVE.store(false, Ordering::SeqCst);
        logging::bridge_log("tun2socks exited");
    });

    *TUN2SOCKS_RUN_HANDLE.lock() = Some(run_handle);

    Ok(())
}

pub fn stop() {
    TUN2SOCKS_STOP_REQUESTED.store(true, Ordering::SeqCst);
    if let Some(notify) = STOP_NOTIFY_SLOT.lock().clone() {
        notify.notify_one();
    }
}

// ---------------------------------------------------------------------------
// Main tun2socks loop
// ---------------------------------------------------------------------------

async fn run_tun2socks(fd: RawFd, stop_notify: Arc<Notify>) -> io::Result<()> {
    logging::bridge_log("tun2socks: building lwip netstack");

    // Wrap the TUN fd in AsyncFd for event-driven readiness: the reader waits
    // for readability instead of busy-polling, and the writer applies
    // backpressure (waits for writability) instead of dropping on EAGAIN.
    // TunFd never closes the fd — the Android VpnService owns its lifecycle.
    let tun_asyncfd =
        Arc::new(AsyncFd::new(TunFd(fd)).map_err(|e| io::Error::other(e.to_string()))?);

    let (mut stack, mut tcp_listener, udp_socket) =
        lwip::NetStack::with_buffer_size(1024, 256).map_err(|e| io::Error::other(e.to_string()))?;

    let (udp_write, mut udp_read) = udp_socket.split();

    let (udp_reply_tx, mut udp_reply_rx) = mpsc::channel::<UdpMsg>(256);
    let reply_readers: Arc<Mutex<HashSet<(SocketAddr, SocketAddr)>>> =
        Arc::new(Mutex::new(HashSet::new()));

    let (stack_ingress_tx, mut stack_ingress_rx) = mpsc::channel::<AnyIpPktFrame>(256);
    let (egress_tx, mut egress_rx) = mpsc::channel::<Vec<u8>>(TUN_EGRESS_CAP);
    let dns_sem = Arc::new(Semaphore::new(DNS_BURST_CAP));
    let flow_tasks: FlowTasks = Arc::new(Mutex::new(Vec::new()));

    let egress_tx_lwip = egress_tx.clone();
    let udp_reply_tx_lwip = udp_reply_tx.clone();
    let reply_readers_lwip = reply_readers.clone();
    let flow_tasks_lwip = flow_tasks.clone();
    // lwip's Rust listener/socket wrappers are backed by C callbacks that
    // mutate Rust queues and wakers through raw pointers. Keep all wrapper
    // polling and UDP writes on one task; detached tasks only own accepted
    // streams or proxy-side work.
    let lwip_handle = tokio::spawn(async move {
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
                        Some(Ok(frame)) => {
                            if egress_tx_lwip.send(frame).await.is_err() {
                                break;
                            }
                        }
                        Some(Err(e)) => {
                            logging::bridge_log(&format!("stack recv error: {}", e));
                            break;
                        }
                        None => break,
                    }
                }
                accepted = tcp_listener.next() => {
                    match accepted {
                        Some((stream, local_addr, remote_addr)) => {
                            if remote_addr.port() == 53 {
                                let handle = tokio::spawn(dispatch_tcp_dns(stream));
                                track_flow_task(&flow_tasks_lwip, handle);
                                continue;
                            }
                            let handle = tokio::spawn(async move {
                                dispatch_tcp(stream, local_addr, remote_addr).await;
                            });
                            track_flow_task(&flow_tasks_lwip, handle);
                        }
                        None => break,
                    }
                }
                udp_pkt = udp_read.next() => {
                    match udp_pkt {
                        Some((payload, src, dst)) => {
                            let reply_tx = udp_reply_tx_lwip.clone();
                            let readers = reply_readers_lwip.clone();
                            let handle = tokio::spawn(async move {
                                dispatch_udp(payload, src, dst, reply_tx, readers).await;
                            });
                            track_flow_task(&flow_tasks_lwip, handle);
                        }
                        None => break,
                    }
                }
                msg = udp_reply_rx.recv() => {
                    match msg {
                        Some(msg) => {
                            if let Err(e) = udp_write.send_to(&msg.0, &msg.1, &msg.2) {
                                logging::bridge_log(&format!("tun2socks: UDP reply send error: {}", e));
                                break;
                            }
                        }
                        None => break,
                    }
                }
            }
        }
    });

    let tun_asyncfd_w = tun_asyncfd.clone();
    let tun_writer_handle = tokio::spawn(async move {
        'tun_packets: while let Some(pkt) = egress_rx.recv().await {
            if TUN2SOCKS_STOP_REQUESTED.load(Ordering::SeqCst) {
                break;
            }
            let mut offset = 0usize;
            loop {
                let n = unsafe {
                    libc::write(
                        fd,
                        pkt[offset..].as_ptr() as *const c_void,
                        pkt.len() - offset,
                    )
                };
                if n >= 0 {
                    offset += n as usize;
                    // A 0-byte write on a non-empty buffer is unexpected for a
                    // TUN fd (writes are atomic: full length or -1/EAGAIN);
                    // treat it as done to avoid looping forever.
                    if n == 0 || offset >= pkt.len() {
                        break;
                    }
                    continue;
                }
                let e = std::io::Error::last_os_error();
                if e.kind() == std::io::ErrorKind::WouldBlock {
                    // Backpressure: wait for the TUN fd to become writable
                    // instead of dropping the packet after a few spins.
                    let mut guard = match tokio::select! {
                        result = tun_asyncfd_w.writable() => Some(result),
                        _ = tokio::time::sleep(Duration::from_millis(50)) => None,
                    } {
                        Some(Ok(g)) => g,
                        Some(Err(e)) => {
                            logging::bridge_log(&format!("tun2socks: fd writable error: {}", e));
                            break;
                        }
                        None if TUN2SOCKS_STOP_REQUESTED.load(Ordering::SeqCst) => {
                            break 'tun_packets;
                        }
                        None => continue,
                    };
                    guard.clear_ready();
                    continue;
                }
                // Hard error: drop the remainder of this packet.
                logging::bridge_log(&format!("tun2socks: fd write error: {}", e));
                break;
            }
        }
    });

    // TUN reader: reads raw IP packets, intercepts DNS pre-stack.
    // Event-driven via AsyncFd (see tun_asyncfd): idle traffic costs zero
    // syscalls instead of busy-polling every 200us.
    let tun_asyncfd_r = tun_asyncfd.clone();
    let tun_reader_handle = tokio::spawn(async move {
        let mut read_buf = vec![0u8; 65535];

        loop {
            if TUN2SOCKS_STOP_REQUESTED.load(Ordering::SeqCst) {
                break;
            }

            // Block until the TUN fd is readable, or a stop is requested.
            // AsyncFd drives this via the tokio IO driver.
            let mut guard = tokio::select! {
                g = tun_asyncfd_r.readable() => match g {
                    Ok(g) => g,
                    Err(e) => {
                        logging::bridge_log(&format!("tun2socks: fd readable error: {}", e));
                        break;
                    }
                },
                _ = stop_notify.notified() => break,
            };

            // Drain everything currently available until EAGAIN, then go back
            // to waiting for readability (clearing the readiness guard so the
            // IO driver re-registers interest).
            loop {
                if TUN2SOCKS_STOP_REQUESTED.load(Ordering::SeqCst) {
                    break;
                }
                let n =
                    unsafe { libc::read(fd, read_buf.as_mut_ptr() as *mut c_void, read_buf.len()) };
                if n <= 0 {
                    if n < 0 {
                        let e = std::io::Error::last_os_error();
                        if e.kind() != std::io::ErrorKind::WouldBlock {
                            logging::bridge_log(&format!("tun2socks: fd read error: {}", e));
                        }
                    }
                    guard.clear_ready();
                    break;
                }
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
                            let Some(resolver) = engine::tunnel().map(|t| t.resolver().clone())
                            else {
                                trace!("tun2socks: DNS dropped — resolver not ready");
                                return;
                            };
                            let response_payload =
                                match DnsServer::handle_query(parsed.payload, &resolver).await {
                                    Ok(bytes) => bytes,
                                    Err(e) => {
                                        trace!("tun2socks: DnsServer::handle_query error: {}", e);
                                        return;
                                    }
                                };
                            let Some(reply_pkt) = build_udp_reply(&request, &response_payload)
                            else {
                                return;
                            };
                            let _ = egress.send(reply_pkt).await;
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
        }
    });

    let _ = tun_reader_handle.await;

    abort_flow_tasks(&flow_tasks).await;
    drop(udp_reply_tx);
    let _ = lwip_handle.await;
    abort_flow_tasks(&flow_tasks).await;
    tun_writer_handle.abort();
    let _ = tun_writer_handle.await;

    logging::bridge_log("tun2socks: exiting");
    Ok(())
}

fn track_flow_task(flow_tasks: &FlowTasks, handle: JoinHandle<()>) {
    let mut tasks = flow_tasks.lock();
    tasks.retain(|task| !task.is_finished());
    tasks.push(handle);
}

async fn abort_flow_tasks(flow_tasks: &FlowTasks) {
    let tasks = {
        let mut tasks = flow_tasks.lock();
        tasks.drain(..).collect::<Vec<_>>()
    };
    for task in tasks {
        task.abort();
        let _ = task.await;
    }
}

// ---------------------------------------------------------------------------
// TCP dispatch
// ---------------------------------------------------------------------------

async fn dispatch_tcp_dns(mut stream: lwip::TcpStream) {
    loop {
        let mut length = [0u8; 2];
        if stream.read_exact(&mut length).await.is_err() {
            return;
        }
        let query_len = u16::from_be_bytes(length) as usize;
        if query_len == 0 {
            return;
        }
        let mut query = vec![0u8; query_len];
        if stream.read_exact(&mut query).await.is_err() {
            return;
        }
        let Some(resolver) = engine::tunnel().map(|t| t.resolver().clone()) else {
            trace!("tun2socks: TCP DNS dropped — resolver not ready");
            return;
        };
        let Ok(Ok(response)) =
            tokio::time::timeout(DNS_TASK_TIMEOUT, DnsServer::handle_query(&query, &resolver))
                .await
        else {
            trace!("tun2socks: TCP DNS query failed or timed out");
            return;
        };
        let Ok(response_len) = u16::try_from(response.len()) else {
            return;
        };
        if stream.write_all(&response_len.to_be_bytes()).await.is_err()
            || stream.write_all(&response).await.is_err()
            || stream.flush().await.is_err()
        {
            return;
        }
    }
}

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
// UDP / IP packet helpers
// ---------------------------------------------------------------------------

struct ParsedUdp<'a> {
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
    let dst_port = u16::from_be_bytes([ip_data[ihl + 2], ip_data[ihl + 3]]);
    let udp_len = u16::from_be_bytes([ip_data[ihl + 4], ip_data[ihl + 5]]) as usize;
    let start = ihl + 8;
    let end = (ihl + udp_len).min(ip_data.len());
    if start > end {
        return None;
    }
    Some(ParsedUdp {
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
