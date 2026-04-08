//! Rust half of the Meow native stack.
//!
//! This crate used to host both the proxy engine (mihomo-rust Tunnel +
//! MixedListener + ApiServer) and the tun2socks layer. As of the Go
//! mihomo migration, the proxy engine lives in a separate Go-backed
//! shared library (libmihomo.so) — see core/src/main/go/mihomo-core.
//! What remains here is tun2socks only:
//!
//!   TUN fd → netstack-smoltcp → SOCKS5 127.0.0.1:7890 (Go mihomo)
//!   UDP:53 → DoH (via the same SOCKS5 port)
//!
//! All sockets owned by this crate are loopback, so none of them need
//! VpnService.protect(). The protect hook is now owned by the Go engine.

mod dns_table;
mod doh_client;
mod logging;
mod tun2socks;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use parking_lot::Mutex;
use std::sync::OnceLock;

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();

pub(crate) fn get_runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("Failed to create tokio runtime")
    })
}

/// Directory where the current profile's config.yaml lives. The DoH
/// client reads it to discover the user-configured DoH server list.
pub(crate) static HOME_DIR: Mutex<Option<String>> = Mutex::new(None);

// ---------------------------------------------------------------------------
// Thread-local error message
// ---------------------------------------------------------------------------

thread_local! {
    static LAST_ERROR: std::cell::RefCell<String> = const { std::cell::RefCell::new(String::new()) };
}

fn set_error(msg: String) {
    LAST_ERROR.with(|e| *e.borrow_mut() = msg);
}

fn get_error() -> String {
    LAST_ERROR.with(|e| e.borrow().clone())
}

// ---------------------------------------------------------------------------
// JNI entry points — class: io.github.madeye.meow.core.Tun2SocksCore
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_Tun2SocksCore_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) {
    logging::init_android_logger();
    logging::bridge_log("Tun2SocksCore.nativeInit: android logger initialized");
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_Tun2SocksCore_nativeSetHomeDir(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
) {
    let dir_str: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    logging::bridge_log(&format!("Tun2SocksCore.nativeSetHomeDir: {}", dir_str));
    *HOME_DIR.lock() = if dir_str.is_empty() {
        None
    } else {
        Some(dir_str)
    };
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_Tun2SocksCore_nativeStartTun2Socks(
    _env: JNIEnv,
    _class: JClass,
    _vpn_service: JObject,
    fd: jint,
    socks_port: jint,
    dns_port: jint,
) -> jint {
    logging::bridge_log(&format!(
        "Tun2SocksCore.nativeStartTun2Socks: fd={}, socks={}, dns={}",
        fd, socks_port, dns_port
    ));

    if fd < 0 {
        set_error("invalid file descriptor".to_string());
        return -1;
    }

    // Note: vpn_service is accepted for ABI stability but intentionally
    // unused. Protect() has moved to the Go engine side, which owns its
    // own VpnService ref. All sockets created here are loopback.

    match tun2socks::start(fd, socks_port as u16, dns_port as u16) {
        Ok(()) => {
            logging::bridge_log("Tun2SocksCore.nativeStartTun2Socks: started successfully");
            0
        }
        Err(e) => {
            logging::bridge_log(&format!("Tun2SocksCore.nativeStartTun2Socks: ERROR: {}", e));
            set_error(e);
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_Tun2SocksCore_nativeStopTun2Socks(
    _env: JNIEnv,
    _class: JClass,
) {
    logging::bridge_log("Tun2SocksCore.nativeStopTun2Socks");
    tun2socks::stop();
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_Tun2SocksCore_nativeGetLastError(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let msg = get_error();
    env.new_string(&msg)
        .unwrap_or_else(|_| env.new_string("").unwrap())
        .into_raw()
}
