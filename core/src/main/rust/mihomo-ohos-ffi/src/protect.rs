//! Outbound-socket protection for HarmonyOS.
//!
//! The NAPI layer hands us a C callback that invokes the ArkTS
//! `VpnConnection.protect(socketFd)`; conceptually this is the exact
//! equivalent of the Android JNI shim in `mihomo-android-ffi/src/protect.rs`.
//!
//! **Engine gap (meow-rs v0.18.0):** upstream `meow_common` compiles its
//! `SocketProtector` hook registry only for `target_os = "android"`, and the
//! OpenHarmony rustc targets are `target_os = "linux"` / `target_env =
//! "ohos"` — so the registry does not exist on this target yet and the
//! stored callback cannot be installed into the engine. Until the meow-rs
//! tag is bumped to one that widens the cfg to
//! `any(target_os = "android", target_env = "ohos")`, outbound sockets on
//! HarmonyOS are NOT protected and a default route through the TUN would
//! loop proxy-server traffic. Ship configs must therefore either route
//! selectively or wait for the engine bump; `install` logs a loud warning so
//! this is visible in hilog. The `HostResolver` hook (which IS
//! cross-platform upstream) is already installed by the core crate on ohos.

use crate::MeowProtectCallback;
use mihomo_ffi_core::logging::bridge_log;
use parking_lot::RwLock;

static PROTECT_CB: RwLock<Option<MeowProtectCallback>> = RwLock::new(None);

/// Store the NAPI-provided protect callback and, where the engine supports
/// it, install it as the global `SocketProtector`.
pub fn install(cb: Option<MeowProtectCallback>) {
    *PROTECT_CB.write() = cb;
    match cb {
        Some(_) => install_into_engine(),
        None => bridge_log("protect: no callback provided (test mode?)"),
    }
}

/// Drop the stored callback on VPN tear-down.
pub fn clear() {
    *PROTECT_CB.write() = None;
    bridge_log("protect: callback cleared");
}

/// Protect `fd` via the stored callback. Exercised directly by the host
/// end-to-end tests; on-device this is what the future `SocketProtector`
/// impl will call for every outbound fd.
pub fn protect_fd(fd: i32) -> std::io::Result<()> {
    let guard = PROTECT_CB.read();
    let Some(cb) = *guard else {
        return Err(std::io::Error::other("protect: no callback installed"));
    };
    if cb(fd) != 0 {
        Ok(())
    } else {
        Err(std::io::Error::other(format!(
            "VpnConnection.protect({fd}) returned false"
        )))
    }
}

fn install_into_engine() {
    // See module docs: meow_common::set_socket_protector does not exist for
    // target_env = "ohos" in meow-rs v0.18.0. When the engine gains the cfg,
    // replace this warning with a SocketProtector impl delegating to
    // `protect_fd` (mirroring mihomo-android-ffi/src/protect.rs).
    #[cfg(target_env = "ohos")]
    bridge_log(
        "protect: WARNING — meow-rs v0.18.0 has no SocketProtector registry on \
         OpenHarmony; outbound sockets are NOT protected against TUN routing loops",
    );
    #[cfg(not(target_env = "ohos"))]
    bridge_log("protect: callback stored (host build — engine hook is Android-only)");
}
