//! Engine accessor — exposes the running `Tunnel` so tun2socks, the
//! in-process TCP DNS client, and the china_dns split layer can dispatch
//! flows through `mihomo_tunnel::tcp::handle_tcp` without re-implementing
//! the NAT/proxy routing logic. Mirrors meow-ios `engine::tunnel()`.

use mihomo_tunnel::Tunnel;

/// Returns a clone of the running engine's `Tunnel` handle, or `None` if the
/// engine isn't running. Mirrors meow-ios `engine::tunnel()`.
pub fn tunnel() -> Option<Tunnel> {
    crate::ENGINE.lock().as_ref().map(|s| s.tunnel.clone())
}
