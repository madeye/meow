//! tun2socks using netstack-smoltcp: reads raw IP packets from the Android TUN
//! fd, routes TCP through a userspace TCP/IP stack (smoltcp) and dispatches
//! every accepted flow in-process via `mihomo_tunnel::tcp::handle_tcp` — same
//! pattern meow-ios uses, with no SOCKS5 loopback hop. UDP/53 is intercepted
//! and answered by the in-process plain-TCP DNS client (`dns_client.rs`),
//! itself dispatching through `handle_tcp` so DNS reuses the tunnel's rule
//! engine and proxy selectors.

use crate::china_dns;
use crate::dns_client;
use crate::dns_table;
use crate::engine;
use crate::logging;
use futures::{SinkExt, StreamExt};
use mihomo_common::{ConnType, Metadata, Network, ProxyConn};
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::os::raw::c_void;
use std::os::unix::io::RawFd;
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::{mpsc, Semaphore};
use tracing::{debug, info, warn};

use netstack_smoltcp::{AnyIpPktFrame, StackBuilder};

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

static TUN2SOCKS_RUNNING: AtomicBool = AtomicBool::new(false);

// Burst cap: defensive backstop against the "bursty-on-flow" pattern that lets
// a reconnect storm (DNS lookups + pent-up connect attempts from every
// backgrounded app) consume excess memory before any flow completes. Drops at
// the accept boundary; peers see RST / packet loss for a few hundred ms
// instead of OOM. Mirrors the iOS DNS_BURST_CAP guard.
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

    info!("tun2socks starting: fd={}", fd);
    // TCP DNS dispatches per-request through `mihomo_tunnel::tcp::handle_tcp`
    // — same in-process Rust-to-Rust path as the netstack TCP flows below, so
    // no extra loopback port is involved for DNS either.
    dns_client::init_dns_client();

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
        info!("tun2socks exited");
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
// tasks. Instead, the TUN reader feeds packets via an mpsc channel to a single
// "stack driver" task that owns the Stack and multiplexes both directions.
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

    // Channel: TUN reader → stack driver (ingress packets)
    let (ingress_tx, mut ingress_rx) = mpsc::channel::<AnyIpPktFrame>(256);

    // Channel: stack driver + UDP replies → TUN writer (egress packets)
    let (egress_tx, mut egress_rx) = mpsc::unbounded_channel::<Vec<u8>>();

    // Per-DNS-burst semaphore — see DNS_BURST_CAP.
    let dns_sem = Arc::new(Semaphore::new(DNS_BURST_CAP));

    // Task 1: TCP runner (smoltcp internal polling)
    let runner_handle = tokio::spawn(async move {
        if let Err(e) = tcp_runner.await {
            logging::bridge_log(&format!("tun2socks: TCP runner error: {}", e));
        }
    });

    // Task 2: Stack driver — single owner of Stack, no split
    let egress_tx2 = egress_tx.clone();
    let stack_handle = tokio::spawn(async move {
        loop {
            tokio::select! {
                // Ingress: receive packet from TUN reader, send to stack
                pkt = ingress_rx.recv() => {
                    match pkt {
                        Some(frame) => {
                            if let Err(e) = stack.send(frame).await {
                                logging::bridge_log(&format!("stack send error: {}", e));
                                break;
                            }
                        }
                        None => break, // channel closed
                    }
                }
                // Egress: receive packet from stack, send to TUN writer
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

    // Task 3: Accept TCP connections → in-process tunnel dispatch
    let tcp_accept_handle = tokio::spawn(async move {
        while let Some((stream, local_addr, remote_addr)) = tcp_listener.next().await {
            logging::bridge_log(&format!("tun2socks: TCP {} -> {}", local_addr, remote_addr));
            tokio::spawn(async move {
                handle_tcp_stream(stream, local_addr, remote_addr).await;
            });
        }
    });

    // Task 4: TUN fd writer
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

    // Task 5: TUN fd reader
    let udp_reply_tx = egress_tx.clone();
    let tun_reader_handle = tokio::spawn(async move {
        let mut read_buf = vec![0u8; 65535];
        let mut _pkt_total: u64 = 0;
        let mut _pkt_udp: u64 = 0;

        loop {
            if !TUN2SOCKS_RUNNING.load(Ordering::SeqCst) {
                break;
            }

            // Poll non-blocking fd with short yield
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
                _pkt_total += 1;
                let ip_data = &read_buf[..n];

                // Intercept UDP DNS
                if let Some((src_ip, src_port, dst_ip, dst_port, payload)) =
                    parse_udp_packet(ip_data)
                {
                    if dst_port == 53 {
                        _pkt_udp += 1;
                        // Burst cap at the accept boundary, matching iOS.
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
                        let reply_tx = udp_reply_tx.clone();
                        let query = payload.to_vec();
                        tokio::spawn(async move {
                            let _permit = permit;
                            handle_dns_query(src_ip, src_port, dst_ip, dst_port, query, reply_tx)
                                .await;
                        });
                        continue;
                    }
                }

                // Send to stack for TCP processing (non-blocking try_send to avoid stall)
                let frame: AnyIpPktFrame = ip_data.to_vec();
                match ingress_tx.try_send(frame) {
                    Ok(()) => {}
                    Err(mpsc::error::TrySendError::Full(frame)) => {
                        // Channel full — block briefly to let stack drain
                        let _ = ingress_tx.send(frame).await;
                    }
                    Err(mpsc::error::TrySendError::Closed(_)) => break,
                }
            }

            if !did_work {
                // No packets available — sleep briefly to avoid busy-loop
                tokio::time::sleep(tokio::time::Duration::from_micros(200)).await;
            }
        }
    });

    // Wait for reader to finish (it checks TUN2SOCKS_RUNNING)
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
// invocation. The netstack `TcpStream` is handed in as the inbound side of
// the tunnel; mihomo opens the upstream proxy connection, copies bidirection-
// ally, and updates per-flow stats / connection tracking. No SOCKS5 hop.
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

    // If the dst IP came from our DNS table (i.e. it's a fake-IP / cached
    // resolution), use the original hostname so mihomo's rule engine and
    // domain-aware proxy adapters (Trojan SNI, HTTP Host, etc.) see the real
    // name. Otherwise pass the IP literal in `dst_ip`.
    let (host, dst_ip) = match dns_table::dns_table_lookup(dst_addr.ip()) {
        Some(h) => (h, None),
        None => (String::new(), Some(dst_addr.ip())),
    };

    let target_desc = if !host.is_empty() {
        format!("{}:{}", host, dst_addr.port())
    } else {
        format!("{}", dst_addr)
    };
    debug!("tun2socks: dispatch {} -> {}", src_addr, target_desc);

    let metadata = Metadata {
        network: Network::Tcp,
        conn_type: ConnType::Inner,
        src_ip: Some(src_addr.ip()),
        src_port: src_addr.port(),
        dst_ip,
        dst_port: dst_addr.port(),
        host,
        ..Default::default()
    };

    let proxy_conn: Box<dyn ProxyConn> = Box::new(NetstackConn(stream));
    let inner = tunnel.inner().clone();
    mihomo_tunnel::tcp::handle_tcp(&inner, proxy_conn, metadata).await;
    debug!("tun2socks: flow done {} -> {}", src_addr, target_desc);
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
// UDP helpers
// ---------------------------------------------------------------------------

fn parse_udp_packet(ip_data: &[u8]) -> Option<(u32, u16, u32, u16, &[u8])> {
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
    let src_ip = u32::from_ne_bytes([ip_data[12], ip_data[13], ip_data[14], ip_data[15]]);
    let dst_ip = u32::from_ne_bytes([ip_data[16], ip_data[17], ip_data[18], ip_data[19]]);
    let src_port = u16::from_be_bytes([ip_data[ihl], ip_data[ihl + 1]]);
    let dst_port = u16::from_be_bytes([ip_data[ihl + 2], ip_data[ihl + 3]]);
    let udp_len = u16::from_be_bytes([ip_data[ihl + 4], ip_data[ihl + 5]]) as usize;
    let start = ihl + 8;
    let end = (ihl + udp_len).min(ip_data.len());
    if start > end {
        return None;
    }
    Some((src_ip, src_port, dst_ip, dst_port, &ip_data[start..end]))
}

fn build_udp_packet(
    src_ip: u32,
    src_port: u16,
    dst_ip: u32,
    dst_port: u16,
    payload: &[u8],
) -> Vec<u8> {
    let udp_len = 8 + payload.len();
    let total_len = 20 + udp_len;
    let mut p = vec![0u8; total_len];
    p[0] = 0x45;
    p[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    p[6] = 0x40;
    p[8] = 64;
    p[9] = 17;
    p[12..16].copy_from_slice(&src_ip.to_ne_bytes());
    p[16..20].copy_from_slice(&dst_ip.to_ne_bytes());
    let ck = ip_checksum(&p[..20]);
    p[10..12].copy_from_slice(&ck.to_be_bytes());
    p[20..22].copy_from_slice(&src_port.to_be_bytes());
    p[22..24].copy_from_slice(&dst_port.to_be_bytes());
    p[24..26].copy_from_slice(&(udp_len as u16).to_be_bytes());
    p[28..].copy_from_slice(payload);
    p
}

fn ip_checksum(h: &[u8]) -> u16 {
    let mut s: u32 = 0;
    for i in (0..h.len()).step_by(2) {
        s += if i + 1 < h.len() {
            (h[i] as u32) << 8 | h[i + 1] as u32
        } else {
            (h[i] as u32) << 8
        };
    }
    while s >> 16 != 0 {
        s = (s & 0xFFFF) + (s >> 16);
    }
    !s as u16
}

async fn handle_dns_query(
    src_ip: u32,
    src_port: u16,
    dst_ip: u32,
    dst_port: u16,
    query: Vec<u8>,
    reply_tx: mpsc::UnboundedSender<Vec<u8>>,
) {
    let name = dns_table::parse_dns_query_name(&query).unwrap_or_default();
    logging::bridge_log(&format!(
        "DNS: {} from {:?}:{}",
        name,
        Ipv4Addr::from(src_ip.to_ne_bytes()),
        src_port
    ));

    // china_dns layers the trust-china-dns split-horizon resolver over the
    // TCP DNS path: A/AAAA queries race a Chinese plain-UDP resolver against
    // the trusted TCP DNS client, picking the China answer iff at least one
    // of its records resolves to a CN IP per the bundled Country.mmdb.
    // Non-A/AAAA queries and configurations without GeoIP fall through to
    // plain TCP DNS.
    if let Some(response) = china_dns::resolve(&query).await {
        for (ip, hostname, ttl) in dns_table::parse_dns_response_records(&response) {
            dns_table::dns_table_insert(ip, hostname, ttl);
        }
        let _ = reply_tx.send(build_udp_packet(
            dst_ip, dst_port, src_ip, src_port, &response,
        ));
    }
}
