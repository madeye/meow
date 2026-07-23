//! Native connectivity probes surfaced in the Settings diagnostics UI.
//! Platform-neutral logic; the JNI / C ABI wrappers live in the platform
//! crates.

use std::net::{TcpStream, UdpSocket};
use std::time::{Duration, Instant};

/// Attempt a direct (unprotected, outside-the-tunnel from the caller's point
/// of view) TCP connect and report latency.
pub fn test_direct_tcp(host: &str, port: u16) -> String {
    let addr = format!("{}:{}", host, port);
    let start = Instant::now();
    match TcpStream::connect_timeout(
        &addr
            .parse()
            .unwrap_or_else(|_| "0.0.0.0:0".parse().unwrap()),
        Duration::from_secs(5),
    ) {
        Ok(_) => {
            let elapsed = start.elapsed();
            format!("OK: connected to {} in {:?}", addr, elapsed)
        }
        Err(e) => {
            let elapsed = start.elapsed();
            format!("FAIL after {:?}: {}", elapsed, e)
        }
    }
}

/// Send a plain UDP DNS A query for www.baidu.com to `dns_addr`
/// (`ip:port`) and report the first A record in the response.
pub fn test_dns_resolver(dns_addr: &str) -> String {
    let sock = match UdpSocket::bind("0.0.0.0:0") {
        Ok(s) => s,
        Err(e) => return format!("DNS-TEST: FAIL bind: {}", e),
    };
    let _ = sock.set_read_timeout(Some(Duration::from_secs(5)));

    if let Err(e) = sock.connect(dns_addr) {
        return format!("DNS-TEST: FAIL connect to {}: {}", dns_addr, e);
    }

    let query = build_dns_query("www.baidu.com");
    if let Err(e) = sock.send(&query) {
        return format!("DNS-TEST: FAIL write: {}", e);
    }

    let mut buf = vec![0u8; 512];
    match sock.recv(&mut buf) {
        Ok(n) => {
            if let Some(ip) = parse_dns_response_a(&buf[..n]) {
                format!("DNS-TEST: OK {} for www.baidu.com", ip)
            } else {
                "DNS-TEST: FAIL could not parse A record".to_string()
            }
        }
        Err(e) => format!("DNS-TEST: FAIL read: {}", e),
    }
}

pub(crate) fn build_dns_query(domain: &str) -> Vec<u8> {
    let mut buf = Vec::with_capacity(64);
    buf.extend_from_slice(&[
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    ]);
    for label in domain.split('.') {
        buf.push(label.len() as u8);
        buf.extend_from_slice(label.as_bytes());
    }
    buf.push(0x00);
    buf.extend_from_slice(&[0x00, 0x01, 0x00, 0x01]);
    buf
}

pub(crate) fn parse_dns_response_a(msg: &[u8]) -> Option<String> {
    if msg.len() < 12 {
        return None;
    }
    let mut pos = 12;
    let qdcount = (msg[4] as usize) << 8 | msg[5] as usize;
    for _ in 0..qdcount {
        while pos < msg.len() {
            let l = msg[pos] as usize;
            pos += 1;
            if l == 0 {
                break;
            }
            if l >= 0xC0 {
                pos += 1;
                break;
            }
            pos += l;
        }
        pos += 4;
    }
    let ancount = (msg[6] as usize) << 8 | msg[7] as usize;
    for _ in 0..ancount {
        if pos < msg.len() && msg[pos] >= 0xC0 {
            pos += 2;
        } else {
            while pos < msg.len() {
                let l = msg[pos] as usize;
                pos += 1;
                if l == 0 {
                    break;
                }
                pos += l;
            }
        }
        if pos + 10 > msg.len() {
            break;
        }
        let rtype = (msg[pos] as usize) << 8 | msg[pos + 1] as usize;
        let rdlen = (msg[pos + 8] as usize) << 8 | msg[pos + 9] as usize;
        pos += 10;
        if rtype == 1 && rdlen == 4 && pos + 4 <= msg.len() {
            return Some(format!(
                "{}.{}.{}.{}",
                msg[pos],
                msg[pos + 1],
                msg[pos + 2],
                msg[pos + 3]
            ));
        }
        pos += rdlen;
    }
    None
}
