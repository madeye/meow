//! Pluggable `mihomo_dns::client::SocketFactory` that protects every DNS
//! socket the resolver opens via `VpnService.protect(fd)` before it's bound
//! or connected. The meow app does NOT exclude itself from VPN routing via
//! `addDisallowedApplication`, so without this protection the resolver's
//! upstream queries would route through our own `tun0` and loop back into
//! the tun2socks UDP/53 intercept.
//!
//! Mihomo v0.7.4+ exposes `set_socket_factory` exactly for this hook.

use crate::protect::protect_fd;
use mihomo_dns::client::{set_socket_factory, SocketFactory};
use std::future::Future;
use std::io;
use std::net::SocketAddr;
use std::os::unix::io::AsRawFd;
use std::pin::Pin;
use std::sync::{Arc, Once};
use tokio::net::{TcpStream, UdpSocket};

type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

struct EngineSocketFactory;

impl SocketFactory for EngineSocketFactory {
    fn bind_udp(&self) -> BoxFuture<'_, io::Result<UdpSocket>> {
        Box::pin(async {
            let sock = socket2::Socket::new(
                socket2::Domain::IPV4,
                socket2::Type::DGRAM,
                Some(socket2::Protocol::UDP),
            )?;
            // Protect BEFORE bind so the kernel resolves the route with the
            // bypass fwmark already on the socket.
            protect_fd(sock.as_raw_fd());
            sock.set_nonblocking(true)?;
            sock.bind(&socket2::SockAddr::from(SocketAddr::from((
                [0u8, 0, 0, 0],
                0,
            ))))?;
            let std_sock: std::net::UdpSocket = sock.into();
            UdpSocket::from_std(std_sock)
        })
    }

    fn connect_tcp(&self, addr: SocketAddr) -> BoxFuture<'_, io::Result<TcpStream>> {
        Box::pin(async move {
            let domain = match addr {
                SocketAddr::V4(_) => socket2::Domain::IPV4,
                SocketAddr::V6(_) => socket2::Domain::IPV6,
            };
            let sock =
                socket2::Socket::new(domain, socket2::Type::STREAM, Some(socket2::Protocol::TCP))?;
            sock.set_nonblocking(true)?;
            // Protect BEFORE connect — fwmark applied before the SYN.
            protect_fd(sock.as_raw_fd());
            match sock.connect(&socket2::SockAddr::from(addr)) {
                Ok(()) => {}
                Err(e) if e.raw_os_error() == Some(libc::EINPROGRESS) => {}
                Err(e) => return Err(e),
            }
            let std_stream: std::net::TcpStream = sock.into();
            let stream = TcpStream::from_std(std_stream)?;
            stream.writable().await?;
            if let Some(err) = stream.take_error()? {
                return Err(err);
            }
            Ok(stream)
        })
    }
}

/// Install the factory exactly once. Safe to call from `start_engine_async`
/// repeatedly across engine restarts.
pub fn install() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        let _ = set_socket_factory(Arc::new(EngineSocketFactory));
    });
}
