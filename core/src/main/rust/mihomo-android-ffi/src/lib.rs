//! JNI surface for the Kotlin VPN service — a thin wrapper around
//! `mihomo-ffi-core`, which embeds the meow-rs proxy engine and the
//! tun2socks layer. See the core crate for the actual engine/tun2socks
//! logic; this crate only does JNI marshalling plus the Android-specific
//! `SocketProtector` (a JNI shim around `VpnService.protect(int)` — see
//! `protect.rs`).

mod diagnostics;
mod logging;
mod protect;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use mihomo_ffi_core as core;

// ---------------------------------------------------------------------------
// Global allocator
//
// mimalloc instead of the platform malloc (scudo on Android API 30+).
// Empirically returns freed pages to the OS more aggressively under the
// allocation patterns mihomo + tun2socks generate (many short-lived
// per-flow allocations + the geoip mmdb scan), keeping VPN-service RSS
// closer to working set.
// ---------------------------------------------------------------------------

#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) {
    logging::init_android_logger();
    core::logging::bridge_log("nativeInit: android logger initialized");
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeSetHomeDir(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
) {
    let dir_str: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    core::logging::bridge_log(&format!("nativeSetHomeDir: {}", dir_str));
    core::set_home_dir(Some(dir_str));
}

/// Drains and returns the buffered engine log lines as a single newline-joined
/// string (empty if none pending). Kotlin splits it back into a list for the
/// logs screen. Safe to call whether or not the engine is running.
#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeGetLogs(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let joined = core::drain_logs().join("\n");
    env.new_string(joined)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeStartEngine(
    mut env: JNIEnv,
    _class: JClass,
    addr: JString,
    secret: JString,
) -> jint {
    let addr_str: String = env.get_string(&addr).map(|s| s.into()).unwrap_or_default();
    let secret_str: String = env
        .get_string(&secret)
        .map(|s| s.into())
        .unwrap_or_default();
    core::start_engine(Some(addr_str), Some(secret_str))
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeStopEngine(
    _env: JNIEnv,
    _class: JClass,
) {
    core::stop_engine();
    protect::clear();
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeStartTun2Socks(
    env: JNIEnv,
    _class: JClass,
    vpn_service: JObject,
    fd: jint,
    dns_port: jint,
) -> jint {
    core::logging::bridge_log(&format!(
        "nativeStartTun2Socks: fd={}, dns={}",
        fd, dns_port
    ));

    if fd < 0 {
        core::set_error("invalid file descriptor".to_string());
        return -1;
    }

    // Install the global SocketProtector — every outbound TCP/UDP fd
    // meow-rs opens (proxy adapters + the DNS resolver's default
    // SocketFactory) will fire VpnService.protect() before connect/bind.
    protect::install(&env, &vpn_service);

    match core::tun2socks::start(fd, dns_port as u16) {
        Ok(()) => {
            core::logging::bridge_log("nativeStartTun2Socks: started successfully");
            0
        }
        Err(e) => {
            core::logging::bridge_log(&format!("nativeStartTun2Socks: ERROR: {}", e));
            core::set_error(e);
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeIsRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if core::is_running() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeGetUploadTraffic(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    core::traffic_snapshot().0 as jlong
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeGetDownloadTraffic(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    core::traffic_snapshot().1 as jlong
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeValidateConfig(
    mut env: JNIEnv,
    _class: JClass,
    yaml: JString,
) -> jint {
    let yaml_str: String = env.get_string(&yaml).map(|s| s.into()).unwrap_or_default();
    core::validate_config(&yaml_str)
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeGetLastError(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let msg = core::get_error();
    env.new_string(&msg)
        .unwrap_or_else(|_| env.new_string("").unwrap())
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string(core::VERSION)
        .unwrap_or_else(|_| env.new_string("").unwrap())
        .into_raw()
}
