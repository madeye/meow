//! Protected TCP connect — routes through a global pre-connect hook.
//!
//! On Android, set_pre_connect_hook() is called at startup to register
//! a callback that invokes VpnService.protect(fd) before connect().
//! On other platforms, the hook is None and we fall back to TcpStream::connect().

use std::net::SocketAddr;
use std::os::unix::io::AsRawFd;
use std::sync::OnceLock;
use tokio::net::{TcpStream, UdpSocket};

/// A callback invoked with the raw fd before connect(). Returns true if protected.
type ProtectHook = Box<dyn Fn(i32) -> bool + Send + Sync>;

static PROTECT_HOOK: OnceLock<ProtectHook> = OnceLock::new();

/// Register a global pre-connect hook. Call once at startup.
pub fn set_pre_connect_hook(hook: impl Fn(i32) -> bool + Send + Sync + 'static) {
    PROTECT_HOOK.set(Box::new(hook)).ok();
}

/// Plain-TCP DNS upstream pool. TCP (not UDP) because UDP/53 to off-device
/// resolvers is filtered on some Chinese mobile/wifi paths, including the
/// network this app is regularly used on. TCP/53 stays open. Mirrors the
/// upstream pool pinned in `engine::pinned_dns_block` so split-horizon
/// answers stay consistent across this resolution path and mihomo's.
const TCP_DNS_UPSTREAMS: &[&str] = &["119.29.29.29:53", "223.5.5.5:53"];

/// Resolve a hostname over a protected TCP DNS socket (bypasses VPN TUN).
/// Falls back to `tokio::net::lookup_host` if no protect hook is set.
async fn protected_resolve(host: &str, port: u16) -> std::io::Result<SocketAddr> {
    let hook = match PROTECT_HOOK.get() {
        Some(h) => h,
        None => {
            let addr_str = format!("{}:{}", host, port);
            let addrs: Vec<SocketAddr> = tokio::net::lookup_host(&addr_str).await?.collect();
            return addrs.into_iter().next().ok_or_else(|| {
                std::io::Error::new(std::io::ErrorKind::AddrNotAvailable, "no addresses found")
            });
        }
    };

    let query = build_dns_query(host);
    // RFC 1035 §4.2.2 — TCP DNS prepends a 16-bit length to the message.
    let mut tcp_msg = Vec::with_capacity(2 + query.len());
    tcp_msg.extend_from_slice(&(query.len() as u16).to_be_bytes());
    tcp_msg.extend_from_slice(&query);

    let mut last_err = std::io::Error::new(
        std::io::ErrorKind::AddrNotAvailable,
        format!("no DNS upstream reachable for {host}"),
    );
    for upstream in TCP_DNS_UPSTREAMS {
        let Ok(dns_addr) = upstream.parse::<SocketAddr>() else {
            continue;
        };
        match tcp_dns_query(hook, dns_addr, &tcp_msg, host, port).await {
            Ok(sa) => return Ok(sa),
            Err(e) => last_err = e,
        }
    }
    Err(last_err)
}

/// Single-shot TCP DNS query against `dns_addr`. Opens a fresh fd-protected
/// socket, connects, sends the length-prefixed query, parses the first A
/// record from the reply.
async fn tcp_dns_query(
    hook: &ProtectHook,
    dns_addr: SocketAddr,
    tcp_msg: &[u8],
    host: &str,
    port: u16,
) -> std::io::Result<SocketAddr> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let domain = match dns_addr {
        SocketAddr::V4(_) => socket2::Domain::IPV4,
        SocketAddr::V6(_) => socket2::Domain::IPV6,
    };
    let sock = socket2::Socket::new(domain, socket2::Type::STREAM, Some(socket2::Protocol::TCP))?;
    sock.set_nonblocking(true)?;
    (hook)(sock.as_raw_fd());
    match sock.connect(&socket2::SockAddr::from(dns_addr)) {
        Ok(()) => {}
        Err(e) if e.raw_os_error() == Some(libc::EINPROGRESS) => {}
        Err(e) => return Err(e),
    }
    let std_stream: std::net::TcpStream = sock.into();
    let mut stream = TcpStream::from_std(std_stream)?;

    let deadline = std::time::Duration::from_secs(5);
    tokio::time::timeout(deadline, async {
        stream.writable().await?;
        if let Some(err) = stream.take_error()? {
            return Err(err);
        }
        stream.write_all(tcp_msg).await?;
        let mut len_buf = [0u8; 2];
        stream.read_exact(&mut len_buf).await?;
        let resp_len = u16::from_be_bytes(len_buf) as usize;
        let mut resp = vec![0u8; resp_len];
        stream.read_exact(&mut resp).await?;
        parse_dns_response(&resp, port)
    })
    .await
    .map_err(|_| {
        std::io::Error::new(
            std::io::ErrorKind::TimedOut,
            format!("DNS timeout for {host}"),
        )
    })?
}

/// Build a minimal DNS A query for the given hostname.
fn build_dns_query(host: &str) -> Vec<u8> {
    let mut buf = Vec::with_capacity(64);
    // Header: ID=0x1234, flags=0x0100 (standard query, recursion desired)
    buf.extend_from_slice(&[0x12, 0x34, 0x01, 0x00]);
    // QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
    buf.extend_from_slice(&[0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]);
    // QNAME
    for label in host.split('.') {
        buf.push(label.len() as u8);
        buf.extend_from_slice(label.as_bytes());
    }
    buf.push(0x00); // root label
                    // QTYPE=A (1), QCLASS=IN (1)
    buf.extend_from_slice(&[0x00, 0x01, 0x00, 0x01]);
    buf
}

/// Parse a DNS response and extract the first A record.
fn parse_dns_response(data: &[u8], port: u16) -> std::io::Result<SocketAddr> {
    if data.len() < 12 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "DNS response too short",
        ));
    }
    let ancount = u16::from_be_bytes([data[6], data[7]]) as usize;
    if ancount == 0 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::AddrNotAvailable,
            "no DNS answers",
        ));
    }

    // Skip header (12 bytes) and question section
    let mut pos = 12;
    // Skip QNAME
    while pos < data.len() {
        let len = data[pos] as usize;
        if len == 0 {
            pos += 1;
            break;
        }
        if len >= 0xC0 {
            pos += 2;
            break;
        } // compression pointer
        pos += 1 + len;
    }
    pos += 4; // skip QTYPE + QCLASS

    // Parse answer records
    for _ in 0..ancount {
        if pos >= data.len() {
            break;
        }
        // Skip NAME (may be compressed)
        if data[pos] & 0xC0 == 0xC0 {
            pos += 2;
        } else {
            while pos < data.len() {
                let len = data[pos] as usize;
                if len == 0 {
                    pos += 1;
                    break;
                }
                pos += 1 + len;
            }
        }
        if pos + 10 > data.len() {
            break;
        }
        let rtype = u16::from_be_bytes([data[pos], data[pos + 1]]);
        let rdlength = u16::from_be_bytes([data[pos + 8], data[pos + 9]]) as usize;
        pos += 10;
        if rtype == 1 && rdlength == 4 && pos + 4 <= data.len() {
            // A record
            let ip =
                std::net::Ipv4Addr::new(data[pos], data[pos + 1], data[pos + 2], data[pos + 3]);
            return Ok(SocketAddr::new(std::net::IpAddr::V4(ip), port));
        }
        pos += rdlength;
    }

    Err(std::io::Error::new(
        std::io::ErrorKind::AddrNotAvailable,
        "no A record in DNS response",
    ))
}

/// Bind a UDP socket, calling the protect hook before bind() if set so the
/// socket bypasses the VPN TUN. Falls back to plain `tokio::UdpSocket::bind`
/// when no hook is registered (non-Android platforms).
pub async fn protected_udp_bind(local: SocketAddr) -> std::io::Result<UdpSocket> {
    let hook = match PROTECT_HOOK.get() {
        Some(h) => h,
        None => return UdpSocket::bind(local).await,
    };

    let domain = match local {
        SocketAddr::V4(_) => socket2::Domain::IPV4,
        SocketAddr::V6(_) => socket2::Domain::IPV6,
    };
    let sock = socket2::Socket::new(domain, socket2::Type::DGRAM, Some(socket2::Protocol::UDP))?;
    // Protect BEFORE bind — fwmark must be on the socket when the kernel
    // resolves the route, which happens at bind / first send.
    (hook)(sock.as_raw_fd());
    sock.set_nonblocking(true)?;
    sock.bind(&socket2::SockAddr::from(local))?;
    let std_sock: std::net::UdpSocket = sock.into();
    UdpSocket::from_std(std_sock)
}

/// Connect a TCP stream, calling the protect hook before connect() if set.
pub async fn protected_tcp_connect(addr: &str) -> std::io::Result<TcpStream> {
    // If no hook is set, just use regular connect
    let hook = match PROTECT_HOOK.get() {
        Some(h) => h,
        None => return TcpStream::connect(addr).await,
    };

    // Resolve address — use protected DNS to bypass VPN tunnel
    let sock_addr: SocketAddr = match addr.parse() {
        Ok(a) => a,
        Err(_) => {
            // Parse host:port
            let (host, port) = addr.rsplit_once(':').ok_or_else(|| {
                std::io::Error::new(std::io::ErrorKind::InvalidInput, "invalid addr")
            })?;
            let port: u16 = port.parse().map_err(|_| {
                std::io::Error::new(std::io::ErrorKind::InvalidInput, "invalid port")
            })?;
            protected_resolve(host, port).await?
        }
    };

    let domain = match sock_addr {
        SocketAddr::V4(_) => socket2::Domain::IPV4,
        SocketAddr::V6(_) => socket2::Domain::IPV6,
    };

    let socket = socket2::Socket::new(domain, socket2::Type::STREAM, Some(socket2::Protocol::TCP))?;
    socket.set_nonblocking(true)?;

    // Protect BEFORE connect
    (hook)(socket.as_raw_fd());

    // Start non-blocking connect
    let sock_addr2 = socket2::SockAddr::from(sock_addr);
    match socket.connect(&sock_addr2) {
        Ok(()) => {}
        Err(e) if e.raw_os_error() == Some(libc::EINPROGRESS) => {}
        Err(e) => return Err(e),
    }

    let std_stream: std::net::TcpStream = socket.into();
    let stream = TcpStream::from_std(std_stream)?;

    // Bounded wait for connect to complete — wrapping in `tokio::time::timeout`
    // also registers the socket with the current reactor early, which is
    // required for `writable()` to receive readiness notifications when the
    // socket was created via `socket2` rather than tokio's own dialer. Without
    // this wrapper, the connect future can register on the wrong reactor or
    // never get notified, and the dispatch task hangs indefinitely.
    tokio::time::timeout(std::time::Duration::from_secs(15), stream.writable())
        .await
        .map_err(|_| std::io::Error::new(std::io::ErrorKind::TimedOut, "connect timeout"))??;
    if let Some(err) = stream.take_error()? {
        return Err(err);
    }

    Ok(stream)
}
