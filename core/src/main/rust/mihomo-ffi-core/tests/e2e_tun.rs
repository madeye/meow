//! Host end-to-end test of the shared native pipeline used by both the
//! Android and HarmonyOS apps:
//!
//!   fake TUN (socketpair) → tun2socks → { UDP/53 intercept → fake-IP DNS,
//!   lwip netstack → meow_tunnel dispatch → DIRECT adapter } → local server
//!
//! No device, emulator, or network access is required: DNS answers are
//! synthesized from the engine's fake-IP pool, and the TCP flow terminates
//! at a loopback echo server spawned by the test. The TCP client side speaks
//! real IPv4 through a smoltcp stack so the lwip netstack sees a genuine
//! handshake, data exchange, and FIN teardown — the exact packet-level
//! contract the platform TUN provides.
//!
//! Engine state is process-global, so everything runs in one #[test].

use mihomo_ffi_core::testsupport::*;
use smoltcp::iface::{Config, Interface, SocketSet};
use smoltcp::phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken};
use smoltcp::socket::tcp;
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{IpAddress, IpCidr, Ipv4Address};
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddrV4, TcpListener};
use std::os::unix::io::RawFd;
use std::time::{Duration, Instant};

const TUN_CLIENT_IP: Ipv4Addr = Ipv4Addr::new(172, 19, 0, 1);

#[test]
fn e2e_dns_and_tcp_through_fake_tun() {
    let (engine_fd, test_fd) = make_fake_tun();

    // No home dir → engine falls back to the built-in minimal config
    // (MATCH,DIRECT) run through strip_and_inject, which pins fake-IP DNS
    // on 28.0.0.0/8. No external controller → no API listener.
    assert_eq!(
        mihomo_ffi_core::start_engine(None, None),
        0,
        "engine failed to start: {}",
        mihomo_ffi_core::get_error()
    );
    assert!(mihomo_ffi_core::is_running());

    assert_eq!(
        mihomo_ffi_core::tun2socks::start(engine_fd, 0),
        Ok(()),
        "tun2socks failed to start"
    );

    let fake_ip = dns_query_e2e(test_fd);
    tcp_flow_e2e(test_fd);
    let _ = fake_ip; // fake-IP allocation verified inside dns_query_e2e

    // Traffic counters must have seen the TCP payload bytes.
    let (up, down) = mihomo_ffi_core::traffic_snapshot();
    assert!(up > 0, "upload counter still 0 after TCP flow");
    assert!(down > 0, "download counter still 0 after TCP flow");

    // Restart-protection: a second start must fail cleanly.
    assert_eq!(mihomo_ffi_core::start_engine(None, None), -1);
    assert!(mihomo_ffi_core::get_error().contains("already running"));

    // Teardown: engine reports stopped, tun2socks exits (its reader notices
    // the stop flag within its poll cadence).
    mihomo_ffi_core::stop_engine();
    assert!(!mihomo_ffi_core::is_running());
    assert_eq!(mihomo_ffi_core::traffic_snapshot(), (0, 0));
}

/// In-TUN DNS: a raw UDP/53 A query must come back as a fake-IP answer from
/// 28.0.0.0/8, entirely in-process (this is what the OS resolver sees when
/// the platform routes DNS at 172.19.0.2 into the TUN).
fn dns_query_e2e(test_fd: RawFd) -> Ipv4Addr {
    let src = SocketAddrV4::new(TUN_CLIENT_IP, 40000);
    let dst = SocketAddrV4::new(Ipv4Addr::new(172, 19, 0, 2), 53);
    let query = build_dns_query(0x4242, "harmony-e2e.example.com");
    tun_write(test_fd, &build_ipv4_udp(src, dst, &query));

    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let remaining = deadline
            .checked_duration_since(Instant::now())
            .expect("timed out waiting for DNS reply from the TUN");
        let pkt = tun_read(test_fd, remaining).expect("no DNS reply before timeout");
        let Some((reply_src, reply_dst, payload)) = parse_ipv4_udp(&pkt) else {
            continue; // unrelated packet
        };
        if reply_src.port() != 53 || reply_dst != std::net::SocketAddr::V4(src) {
            continue;
        }
        assert_eq!(
            payload[0..2],
            0x4242u16.to_be_bytes(),
            "DNS reply id mismatch"
        );
        let answer = parse_dns_answer_a(&payload).expect("DNS reply has no A record");
        assert_eq!(
            answer.octets()[0],
            28,
            "expected fake-IP from 28.0.0.0/8, got {answer}"
        );
        return answer;
    }
}

/// Full TCP flow: smoltcp client in the "app" role opens a connection through
/// the fake TUN to a loopback echo server. Exercises lwip accept, in-process
/// `meow_tunnel::tcp::handle_tcp` dispatch, rule routing (MATCH,DIRECT), the
/// DIRECT adapter's outbound dial, and bidirectional relay + teardown.
fn tcp_flow_e2e(test_fd: RawFd) {
    // Echo server the DIRECT adapter will dial.
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind echo server");
    let echo_addr = listener.local_addr().unwrap();
    std::thread::spawn(move || {
        if let Ok((mut sock, _)) = listener.accept() {
            let mut buf = [0u8; 4096];
            while let Ok(n) = sock.read(&mut buf) {
                if n == 0 {
                    break;
                }
                if sock.write_all(&buf[..n]).is_err() {
                    break;
                }
            }
        }
    });

    let mut stack = SmolStack::new(test_fd);
    let payload = b"meow-harmonyos-e2e-payload";
    let echoed = stack.tcp_echo_roundtrip(
        (Ipv4Address::new(127, 0, 0, 1), echo_addr.port()),
        payload,
        Duration::from_secs(15),
    );
    assert_eq!(
        echoed.as_slice(),
        payload,
        "TCP payload was not echoed intact through the netstack"
    );
}

// ---------------------------------------------------------------------------
// smoltcp harness: raw-IP device over the test end of the socketpair
// ---------------------------------------------------------------------------

struct FdDevice {
    fd: RawFd,
}

struct FdRxToken(Vec<u8>);
struct FdTxToken {
    fd: RawFd,
}

impl RxToken for FdRxToken {
    fn consume<R, F: FnOnce(&[u8]) -> R>(self, f: F) -> R {
        let mut buf = self.0;
        f(&mut buf)
    }
}

impl TxToken for FdTxToken {
    fn consume<R, F: FnOnce(&mut [u8]) -> R>(self, len: usize, f: F) -> R {
        let mut buf = vec![0u8; len];
        let r = f(&mut buf);
        tun_write(self.fd, &buf);
        r
    }
}

impl Device for FdDevice {
    type RxToken<'a> = FdRxToken;
    type TxToken<'a> = FdTxToken;

    fn receive(&mut self, _ts: SmolInstant) -> Option<(FdRxToken, FdTxToken)> {
        let pkt = tun_read(self.fd, Duration::from_millis(1))?;
        Some((FdRxToken(pkt), FdTxToken { fd: self.fd }))
    }

    fn transmit(&mut self, _ts: SmolInstant) -> Option<FdTxToken> {
        Some(FdTxToken { fd: self.fd })
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = 1500;
        caps
    }
}

struct SmolStack {
    device: FdDevice,
    iface: Interface,
    sockets: SocketSet<'static>,
}

impl SmolStack {
    fn new(fd: RawFd) -> Self {
        let mut device = FdDevice { fd };
        let config = Config::new(smoltcp::wire::HardwareAddress::Ip);
        let mut iface = Interface::new(config, &mut device, SmolInstant::now());
        iface.update_ip_addrs(|addrs| {
            addrs
                .push(IpCidr::new(IpAddress::from(TUN_CLIENT_IP), 30))
                .unwrap();
        });
        iface
            .routes_mut()
            .add_default_ipv4_route(Ipv4Address::new(172, 19, 0, 2))
            .unwrap();
        Self {
            device,
            iface,
            sockets: SocketSet::new(Vec::new()),
        }
    }

    /// Connect, send `payload`, read it back, close. Panics on timeout.
    fn tcp_echo_roundtrip(
        &mut self,
        remote: (Ipv4Address, u16),
        payload: &[u8],
        timeout: Duration,
    ) -> Vec<u8> {
        let rx_buf = tcp::SocketBuffer::new(vec![0u8; 65535]);
        let tx_buf = tcp::SocketBuffer::new(vec![0u8; 65535]);
        let mut socket = tcp::Socket::new(rx_buf, tx_buf);
        socket
            .connect(
                self.iface.context(),
                remote,
                (IpAddress::from(TUN_CLIENT_IP), 39000),
            )
            .expect("smoltcp connect");
        let handle = self.sockets.add(socket);

        let deadline = Instant::now() + timeout;
        let mut sent = false;
        let mut received: Vec<u8> = Vec::new();

        loop {
            assert!(
                Instant::now() < deadline,
                "TCP echo round-trip timed out (received {} of {} bytes)",
                received.len(),
                payload.len()
            );
            self.iface
                .poll(SmolInstant::now(), &mut self.device, &mut self.sockets);

            let socket = self.sockets.get_mut::<tcp::Socket>(handle);
            if !sent && socket.can_send() {
                socket.send_slice(payload).expect("send_slice");
                sent = true;
            }
            if socket.can_recv() {
                socket
                    .recv(|buf| {
                        received.extend_from_slice(buf);
                        (buf.len(), ())
                    })
                    .expect("recv");
            }
            if received.len() >= payload.len() {
                socket.close();
                // Flush the FIN out through the device before returning.
                for _ in 0..10 {
                    self.iface
                        .poll(SmolInstant::now(), &mut self.device, &mut self.sockets);
                }
                return received;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    }
}
