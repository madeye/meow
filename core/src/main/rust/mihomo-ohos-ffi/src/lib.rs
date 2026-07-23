//! C ABI surface for HarmonyOS (OpenHarmony) — a thin wrapper around
//! `mihomo-ffi-core`, which embeds the meow-rs proxy engine and the
//! tun2socks layer.
//!
//! The ArkTS app never calls this library directly: the NAPI glue in
//! `harmony/entry/src/main/cpp/napi_init.cpp` (built with the OHOS NDK)
//! marshals between ArkTS and these `meow_*` functions. Keeping the Rust
//! side plain C ABI — no `napi` dependency — means the crate builds and
//! tests on any host, and the same surface is exercised end-to-end by
//! `tests/c_abi_e2e.rs` without a device.
//!
//! String returns are heap `CString`s owned by the caller; release them with
//! [`meow_string_free`]. All functions are safe to call from any thread.

// The C ABI deliberately exposes safe `extern "C"` functions taking raw
// pointers (the NAPI glue is the only caller and always passes valid,
// NUL-terminated strings); making them `unsafe fn` would buy nothing across
// the FFI boundary.
#![allow(clippy::not_unsafe_ptr_arg_deref)]

mod logging;
pub mod protect;

use mihomo_ffi_core as core;
use std::ffi::{c_char, c_int, c_longlong, CStr, CString};

// ---------------------------------------------------------------------------
// Global allocator (see mihomo-android-ffi for rationale)
// ---------------------------------------------------------------------------

#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

/// Socket-protect callback provided by the NAPI layer: must call the ArkTS
/// `VpnConnection.protect(fd)` and return 1 on success, 0 on failure. Runs
/// on engine worker threads and must not block for long.
pub type MeowProtectCallback = extern "C" fn(fd: c_int) -> c_int;

fn cstr_arg(ptr: *const c_char) -> String {
    if ptr.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(ptr) }
        .to_string_lossy()
        .into_owned()
}

fn cstring_ret(s: String) -> *mut c_char {
    // Interior NULs can't cross the C boundary; replace instead of failing.
    CString::new(s)
        .unwrap_or_else(|e| {
            let bytes: Vec<u8> = e
                .into_vec()
                .into_iter()
                .map(|b| if b == 0 { b' ' } else { b })
                .collect();
            CString::new(bytes).expect("NULs replaced")
        })
        .into_raw()
}

/// Initialize logging (hilog on device, no-op elsewhere). Safe to call
/// multiple times.
#[no_mangle]
pub extern "C" fn meow_init() {
    logging::init();
    core::logging::bridge_log("meow_init: logger initialized");
}

/// Set the engine home dir (`config.yaml`, geodata, DNS cache). Pass NULL or
/// an empty string to clear.
#[no_mangle]
pub extern "C" fn meow_set_home_dir(dir: *const c_char) {
    let dir_str = cstr_arg(dir);
    core::logging::bridge_log(&format!("meow_set_home_dir: {}", dir_str));
    core::set_home_dir(Some(dir_str));
}

/// Start the embedded engine. `external_controller` is the REST API bind
/// address (e.g. "127.0.0.1:9090", NULL to disable), `secret` the API secret
/// (NULL/empty for none). Returns 0 on success, -1 on failure — retrieve the
/// message with [`meow_get_last_error`] from the same thread.
#[no_mangle]
pub extern "C" fn meow_start_engine(
    external_controller: *const c_char,
    secret: *const c_char,
) -> c_int {
    let controller = if external_controller.is_null() {
        None
    } else {
        Some(cstr_arg(external_controller))
    };
    core::start_engine(controller, Some(cstr_arg(secret)))
}

/// Stop tun2socks + the engine and clear the socket protector.
#[no_mangle]
pub extern "C" fn meow_stop_engine() {
    core::stop_engine();
    protect::clear();
}

/// Start the tun2socks layer on `fd` (the TUN fd returned by the ArkTS
/// `VpnConnection.create`). `protect_cb` protects outbound sockets from
/// being routed back into the TUN; pass NULL only in tests. Returns 0 on
/// success, -1 on failure.
#[no_mangle]
pub extern "C" fn meow_start_tun2socks(
    fd: c_int,
    protect_cb: Option<MeowProtectCallback>,
) -> c_int {
    core::logging::bridge_log(&format!("meow_start_tun2socks: fd={}", fd));

    if fd < 0 {
        core::set_error("invalid file descriptor".to_string());
        return -1;
    }

    protect::install(protect_cb);

    match core::tun2socks::start(fd, 0) {
        Ok(()) => {
            core::logging::bridge_log("meow_start_tun2socks: started successfully");
            0
        }
        Err(e) => {
            core::logging::bridge_log(&format!("meow_start_tun2socks: ERROR: {}", e));
            core::set_error(e);
            -1
        }
    }
}

/// 1 while the engine is running, else 0.
#[no_mangle]
pub extern "C" fn meow_is_running() -> c_int {
    core::is_running() as c_int
}

/// Upload byte counter since engine start (0 when stopped).
#[no_mangle]
pub extern "C" fn meow_get_upload_traffic() -> c_longlong {
    core::traffic_snapshot().0 as c_longlong
}

/// Download byte counter since engine start (0 when stopped).
#[no_mangle]
pub extern "C" fn meow_get_download_traffic() -> c_longlong {
    core::traffic_snapshot().1 as c_longlong
}

/// Validate a config YAML with the real loader. 0 = valid, -1 = invalid
/// (message via [`meow_get_last_error`]).
#[no_mangle]
pub extern "C" fn meow_validate_config(yaml: *const c_char) -> c_int {
    core::validate_config(&cstr_arg(yaml))
}

/// Last error message recorded on the calling thread. Free with
/// [`meow_string_free`].
#[no_mangle]
pub extern "C" fn meow_get_last_error() -> *mut c_char {
    cstring_ret(core::get_error())
}

/// Drain buffered engine log lines, newline-joined (empty string if none).
/// Free with [`meow_string_free`].
#[no_mangle]
pub extern "C" fn meow_get_logs() -> *mut c_char {
    cstring_ret(core::drain_logs().join("\n"))
}

/// Engine version. Free with [`meow_string_free`].
#[no_mangle]
pub extern "C" fn meow_version() -> *mut c_char {
    cstring_ret(core::VERSION.to_string())
}

/// Connectivity probe: direct TCP connect to `host:port` (diagnostics UI).
/// Free the result with [`meow_string_free`].
#[no_mangle]
pub extern "C" fn meow_test_direct_tcp(host: *const c_char, port: c_int) -> *mut c_char {
    cstring_ret(core::diagnostics::test_direct_tcp(
        &cstr_arg(host),
        port as u16,
    ))
}

/// Connectivity probe: UDP DNS query to `dns_addr` ("ip:port").
/// Free the result with [`meow_string_free`].
#[no_mangle]
pub extern "C" fn meow_test_dns_resolver(dns_addr: *const c_char) -> *mut c_char {
    cstring_ret(core::diagnostics::test_dns_resolver(&cstr_arg(dns_addr)))
}

/// Release a string returned by any `meow_*` function. NULL is a no-op.
#[no_mangle]
pub extern "C" fn meow_string_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        drop(unsafe { CString::from_raw(ptr) });
    }
}
