//! Engine accessor — exposes the running `Tunnel` so the in-process TCP DNS
//! client and china_dns split layer can dispatch flows through
//! `mihomo_tunnel::tcp::handle_tcp` without re-implementing the NAT/proxy
//! routing logic.
//!
//! Android keeps the SOCKS5 loopback (MixedListener) for ordinary tun2socks
//! TCP traffic — this accessor exists only for the in-process DNS path,
//! which mirrors iOS and avoids a chicken-and-egg dependency on the mixed
//! listener at startup.

use mihomo_tunnel::Tunnel;

/// Returns a clone of the running engine's `Tunnel` handle, or `None` if the
/// engine isn't running. Mirrors meow-ios `engine::tunnel()`.
pub fn tunnel() -> Option<Tunnel> {
    crate::ENGINE.lock().as_ref().map(|s| s.tunnel.clone())
}
