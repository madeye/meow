//! Bridge between meow-rs `meow_common::SocketProtector` and Android's
//! `VpnService.protect(int fd)`. Every outbound socket meow-rs opens —
//! proxy adapters and the DNS resolver's default `SocketFactory` — fires
//! this hook before `connect()` / `bind()`, so the SYN / first UDP packet
//! already bypasses the tunnel.
//!
//! The `SocketProtector` trait and registry only exist on Android in
//! upstream meow-common; this module compiles to no-op stubs elsewhere
//! so the cdylib still type-checks against the host target.

#[cfg(target_os = "android")]
mod inner {
    use jni::objects::GlobalRef;
    use jni::JavaVM;
    use meow_common::SocketProtector;
    use std::os::fd::RawFd;
    use std::sync::Arc;

    /// `SocketProtector` impl that calls `VpnService.protect(int)` over JNI.
    pub(super) struct VpnSocketProtector {
        jvm: JavaVM,
        service: GlobalRef,
    }

    impl VpnSocketProtector {
        fn new(env: &jni::JNIEnv, service: &jni::objects::JObject) -> jni::errors::Result<Self> {
            Ok(Self {
                jvm: env.get_java_vm()?,
                service: env.new_global_ref(service)?,
            })
        }
    }

    impl SocketProtector for VpnSocketProtector {
        fn protect(&self, fd: RawFd) -> std::io::Result<()> {
            let mut env = self.jvm.attach_current_thread().map_err(|e| {
                std::io::Error::other(format!("VpnService.protect: JNI attach failed: {e}"))
            })?;
            let ok = env
                .call_method(
                    &self.service,
                    "protect",
                    "(I)Z",
                    &[jni::objects::JValue::Int(fd)],
                )
                .and_then(|v| v.z())
                .map_err(|e| {
                    std::io::Error::other(format!("VpnService.protect({fd}) JNI call failed: {e}"))
                })?;
            if ok {
                Ok(())
            } else {
                Err(std::io::Error::other(format!(
                    "VpnService.protect({fd}) returned false"
                )))
            }
        }
    }

    pub(super) fn install(env: &jni::JNIEnv, service: &jni::objects::JObject) {
        match VpnSocketProtector::new(env, service) {
            Ok(p) => {
                meow_common::set_socket_protector(Arc::new(p));
                crate::logging::bridge_log("protect: SocketProtector installed");
            }
            Err(e) => {
                crate::logging::bridge_log(&format!("protect: install failed: {e}"));
            }
        }
    }

    pub(super) fn clear() {
        meow_common::clear_socket_protector();
        crate::logging::bridge_log("protect: SocketProtector cleared");
    }
}

#[cfg(not(target_os = "android"))]
mod inner {
    pub(super) fn install(_env: &jni::JNIEnv, _service: &jni::objects::JObject) {}
    pub(super) fn clear() {}
}

/// Install the global protector. Called from `nativeStartTun2Socks` with the
/// live `VpnService` reference; safe to call again across VPN restarts.
pub fn install(env: &jni::JNIEnv, service: &jni::objects::JObject) {
    inner::install(env, service);
}

/// Remove the protector on VPN tear-down.
pub fn clear() {
    inner::clear();
}
