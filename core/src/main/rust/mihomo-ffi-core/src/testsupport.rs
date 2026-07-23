//! Helpers for the host end-to-end tests (feature `testsupport`, never
//! compiled into release builds).
//!
//! The platform TUN device is replaced by one end of a `socketpair(AF_UNIX,
//! SOCK_DGRAM)`: datagram sockets preserve packet boundaries, so each
//! `read`/`write` moves exactly one raw IP packet — the same contract the
//! Android/HarmonyOS TUN fd provides. Tests hold one end and craft/parse raw
//! IPv4 packets; `tun2socks::start` gets the other end.

use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::os::unix::io::RawFd;
use std::time::{Duration, Instant};

/// Create the fake TUN. Returns `(engine_fd, test_fd)`; hand `engine_fd` to
/// `tun2socks::start` and speak raw IPv4 on `test_fd`.
pub fn make_fake_tun() -> (RawFd, RawFd) {
    let mut fds = [0i32; 2];
    let rc = unsafe { libc::socketpair(libc::AF_UNIX, libc::SOCK_DGRAM, 0, fds.as_mut_ptr()) };
    assert_eq!(
        rc,
        0,
        "socketpair failed: {}",
        std::io::Error::last_os_error()
    );
    (fds[0], fds[1])
}

/// Write one raw IP packet to the fake TUN.
pub fn tun_write(fd: RawFd, pkt: &[u8]) {
    let n = unsafe { libc::write(fd, pkt.as_ptr() as *const libc::c_void, pkt.len()) };
    assert_eq!(n as usize, pkt.len(), "short TUN write");
}

/// Read one raw IP packet from the fake TUN, waiting up to `timeout`.
/// Returns `None` on timeout.
pub fn tun_read(fd: RawFd, timeout: Duration) -> Option<Vec<u8>> {
    let deadline = Instant::now() + timeout;
    let mut buf = vec![0u8; 65535];
    loop {
        let remaining = deadline.checked_duration_since(Instant::now())?;
        let mut pfd = libc::pollfd {
            fd,
            events: libc::POLLIN,
            revents: 0,
        };
        let timeout_ms = remaining.as_millis().min(i32::MAX as u128) as i32;
        let rc = unsafe { libc::poll(&mut pfd, 1, timeout_ms.max(1)) };
        if rc <= 0 {
            return None;
        }
        let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
        if n > 0 {
            buf.truncate(n as usize);
            return Some(buf);
        }
    }
}

/// Build a raw IPv4/UDP packet.
pub fn build_ipv4_udp(src: SocketAddrV4, dst: SocketAddrV4, payload: &[u8]) -> Vec<u8> {
    let udp_len = 8 + payload.len();
    let total_len = 20 + udp_len;
    let mut pkt = Vec::with_capacity(total_len);
    pkt.push(0x45);
    pkt.push(0x00);
    pkt.extend_from_slice(&(total_len as u16).to_be_bytes());
    pkt.extend_from_slice(&[0, 0]); // identification
    pkt.extend_from_slice(&[0x40, 0x00]); // DF, no fragment offset
    pkt.push(64); // TTL
    pkt.push(17); // UDP
    pkt.extend_from_slice(&[0, 0]); // header checksum placeholder
    pkt.extend_from_slice(&src.ip().octets());
    pkt.extend_from_slice(&dst.ip().octets());
    let cksum = ipv4_header_checksum(&pkt[0..20]);
    pkt[10..12].copy_from_slice(&cksum.to_be_bytes());

    pkt.extend_from_slice(&src.port().to_be_bytes());
    pkt.extend_from_slice(&dst.port().to_be_bytes());
    pkt.extend_from_slice(&(udp_len as u16).to_be_bytes());
    // Checksum 0 = "not computed", legal for UDP over IPv4.
    pkt.extend_from_slice(&[0, 0]);
    pkt.extend_from_slice(payload);
    pkt
}

/// Parse an IPv4/UDP packet into `(src, dst, payload)`.
pub fn parse_ipv4_udp(pkt: &[u8]) -> Option<(SocketAddr, SocketAddr, Vec<u8>)> {
    if pkt.len() < 28 || (pkt[0] >> 4) != 4 || pkt[9] != 17 {
        return None;
    }
    let ihl = (pkt[0] & 0x0F) as usize * 4;
    if pkt.len() < ihl + 8 {
        return None;
    }
    let src_ip = Ipv4Addr::new(pkt[12], pkt[13], pkt[14], pkt[15]);
    let dst_ip = Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]);
    let src_port = u16::from_be_bytes([pkt[ihl], pkt[ihl + 1]]);
    let dst_port = u16::from_be_bytes([pkt[ihl + 2], pkt[ihl + 3]]);
    let udp_len = u16::from_be_bytes([pkt[ihl + 4], pkt[ihl + 5]]) as usize;
    let start = ihl + 8;
    let end = (ihl + udp_len).min(pkt.len());
    if start > end {
        return None;
    }
    Some((
        SocketAddr::new(src_ip.into(), src_port),
        SocketAddr::new(dst_ip.into(), dst_port),
        pkt[start..end].to_vec(),
    ))
}

/// Build a bare DNS query payload (A record, RD set).
pub fn build_dns_query(id: u16, domain: &str) -> Vec<u8> {
    let mut buf = Vec::with_capacity(64);
    buf.extend_from_slice(&id.to_be_bytes());
    buf.extend_from_slice(&[0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]);
    for label in domain.split('.') {
        buf.push(label.len() as u8);
        buf.extend_from_slice(label.as_bytes());
    }
    buf.push(0x00);
    buf.extend_from_slice(&[0x00, 0x01, 0x00, 0x01]); // QTYPE=A, QCLASS=IN
    buf
}

/// First A record in a DNS response payload, if any.
pub fn parse_dns_answer_a(msg: &[u8]) -> Option<Ipv4Addr> {
    crate::diagnostics::parse_dns_response_a(msg).and_then(|s| s.parse().ok())
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
