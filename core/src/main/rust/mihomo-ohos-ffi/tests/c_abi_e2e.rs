//! Host end-to-end test of the exact C ABI the HarmonyOS NAPI glue
//! (`harmony/entry/src/main/cpp/napi_init.cpp`) calls: engine lifecycle,
//! config validation, error reporting, the protect-callback plumbing, and a
//! live DNS round-trip through tun2socks over a fake TUN fd.
//!
//! Engine state is process-global, so everything runs in one #[test].

use mihomo_ffi_core::testsupport::*;
use mihomo_ohos_ffi::*;
use std::ffi::{c_char, c_int, CStr, CString};
use std::net::{Ipv4Addr, SocketAddrV4};
use std::sync::atomic::{AtomicI32, Ordering};
use std::time::{Duration, Instant};

fn take_string(ptr: *mut c_char) -> String {
    assert!(!ptr.is_null());
    let s = unsafe { CStr::from_ptr(ptr) }
        .to_string_lossy()
        .into_owned();
    meow_string_free(ptr);
    s
}

static PROTECTED_FDS: AtomicI32 = AtomicI32::new(0);

extern "C" fn fake_protect(_fd: c_int) -> c_int {
    PROTECTED_FDS.fetch_add(1, Ordering::SeqCst);
    1
}

#[test]
fn c_abi_engine_lifecycle_and_dns_e2e() {
    meow_init();

    let version = take_string(meow_version());
    assert!(version.contains("meow-rs"), "unexpected version: {version}");

    // Config validation through the real loader.
    let good = CString::new("mode: rule\nproxies: []\nrules:\n  - MATCH,DIRECT\n").unwrap();
    assert_eq!(meow_validate_config(good.as_ptr()), 0);
    let bad = CString::new("proxies: {not-a-list: true}\n").unwrap();
    assert_eq!(meow_validate_config(bad.as_ptr()), -1);
    let err = take_string(meow_get_last_error());
    assert!(
        err.contains("validate config"),
        "unexpected error message: {err}"
    );

    // Home dir handling (empty clears; the engine then uses its built-in
    // minimal MATCH,DIRECT config).
    let dir = CString::new("").unwrap();
    meow_set_home_dir(dir.as_ptr());

    // Lifecycle: not running → start → running → double-start fails.
    assert_eq!(meow_is_running(), 0);
    assert_eq!(meow_start_engine(std::ptr::null(), std::ptr::null()), 0);
    assert_eq!(meow_is_running(), 1);
    assert_eq!(meow_start_engine(std::ptr::null(), std::ptr::null()), -1);
    let err = take_string(meow_get_last_error());
    assert!(err.contains("already running"), "unexpected error: {err}");

    // Bad fd is rejected.
    assert_eq!(meow_start_tun2socks(-1, Some(fake_protect)), -1);

    // tun2socks over a fake TUN with the protect callback installed.
    let (engine_fd, test_fd) = make_fake_tun();
    assert_eq!(meow_start_tun2socks(engine_fd, Some(fake_protect)), 0);

    // The stored callback is reachable through the protect plumbing (on
    // device this is what the engine-side SocketProtector will invoke for
    // every outbound fd once meow-rs exposes the hook on OpenHarmony).
    assert!(mihomo_ohos_ffi::protect::protect_fd(10).is_ok());
    assert_eq!(PROTECTED_FDS.load(Ordering::SeqCst), 1);

    // Live DNS round-trip: raw UDP/53 A query in, fake-IP (28.0.0.0/8)
    // answer out — the same packet-level contract the ArkTS
    // VpnConnection.create fd provides on-device.
    let src = SocketAddrV4::new(Ipv4Addr::new(172, 19, 0, 1), 41000);
    let dst = SocketAddrV4::new(Ipv4Addr::new(172, 19, 0, 2), 53);
    let query = build_dns_query(0x1337, "ohos-ffi-e2e.example.com");
    tun_write(test_fd, &build_ipv4_udp(src, dst, &query));

    let deadline = Instant::now() + Duration::from_secs(10);
    let answer = loop {
        let remaining = deadline
            .checked_duration_since(Instant::now())
            .expect("timed out waiting for DNS reply");
        let pkt = tun_read(test_fd, remaining).expect("no DNS reply before timeout");
        let Some((reply_src, _dst, payload)) = parse_ipv4_udp(&pkt) else {
            continue;
        };
        if reply_src.port() != 53 {
            continue;
        }
        assert_eq!(payload[0..2], 0x1337u16.to_be_bytes());
        break parse_dns_answer_a(&payload).expect("DNS reply has no A record");
    };
    assert_eq!(
        answer.octets()[0],
        28,
        "expected fake-IP from 28.0.0.0/8, got {answer}"
    );

    // Engine logs flowed through the drain buffer.
    let logs = take_string(meow_get_logs());
    assert!(!logs.is_empty(), "expected buffered engine logs");

    // Teardown.
    meow_stop_engine();
    assert_eq!(meow_is_running(), 0);
    assert_eq!(meow_get_upload_traffic(), 0);
    assert_eq!(meow_get_download_traffic(), 0);
}
