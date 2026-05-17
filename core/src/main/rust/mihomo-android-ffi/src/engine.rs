//! Engine helpers — `tunnel()` accessor plus the config strip + pinned DNS
//! block that mirrors meow-ios `engine::pinned_dns_block` /
//! `strip_listener_fields`. The strip enforces architecturally that:
//!
//!   * No loopback listener bind: `port`, `socks-port`, `mixed-port`,
//!     `tproxy-port`, and `listeners:` are stripped. tun2socks dispatches
//!     every TCP flow in-process via `mihomo_tunnel::tcp::handle_tcp`.
//!   * No SNI / ALPN sniffing: redundant once DNS is fake-IP, because
//!     `pre_handle_metadata` reverses the fake-IP back to the qname the
//!     resolver originally returned.
//!   * No user-supplied `dns:` block: the FFI pins the resolver to fake-IP
//!     mode (`28.0.0.0/8`) with CN-side upstreams, regardless of subscription
//!     content.
//!
//! Mirrors meow-ios behaviour exactly so split-horizon answers, NXDOMAIN
//! handling, and rule-engine fake-IP reversal are consistent across both
//! platforms.

use anyhow::{Context, Result};
use mihomo_config::{load_config, Config};
use mihomo_tunnel::Tunnel;
use std::path::{Path, PathBuf};

/// Returns a clone of the running engine's `Tunnel` handle, or `None` if the
/// engine isn't running. Mirrors meow-ios `engine::tunnel()`.
pub fn tunnel() -> Option<Tunnel> {
    crate::ENGINE.lock().as_ref().map(|s| s.tunnel.clone())
}

/// Pinned DNS block injected into every engine config. Configures mihomo's
/// resolver in fake-IP mode with the FFI's chosen CIDR; the tun2socks
/// UDP/53 intercept then hands every in-TUN datagram straight to
/// `mihomo_dns::DnsServer::handle_query`, so this block is the single source
/// of truth for synthesis, reverse mapping, AAAA / hosts / NXDOMAIN, and
/// upstream nameserver selection.
///
/// Nameserver pool is restricted to CN-side resolvers because mihomo's
/// `query_pool` races every entry in parallel ("first response wins"), and
/// mixing a global anycast resolver into the same pool lets it win the race
/// from outside CN — returning the global / SG / HK PoP for split-horizon
/// hosts like xiaohongshu.com.
///
/// `listen: 127.0.0.1:1053` binds mihomo's `DnsServer` on a loopback UDP
/// socket. tun2socks no longer parses DNS payloads or calls
/// `DnsServer::handle_query` directly — every in-TUN UDP/53 datagram is
/// rewritten to `127.0.0.1:1053` and dispatched through
/// `mihomo_tunnel::udp::handle_udp` so DNS rides the same in-process tunnel
/// path application traffic does. mihomo's tunnel routes the packet to its
/// own bound DnsServer (via the DIRECT proxy + the NAT/reply machinery in
/// mihomo-tunnel), so fake-IP synthesis, upstream resolution, hosts,
/// NXDOMAIN — all DNS logic — stays inside mihomo, not in the FFI.
pub fn pinned_dns_block() -> serde_yaml::Value {
    let yaml = r#"
enable: true
listen: 127.0.0.1:1053
enhanced-mode: fake-ip
fake-ip-range: 28.0.0.0/8
nameserver:
  - tcp://119.29.29.29:53
  - tcp://223.5.5.5:53
"#;
    serde_yaml::from_str(yaml).expect("pinned DNS YAML is a compile-time constant")
}

/// Strip the shorthand listener ports + explicit `listeners:` array + user
/// `dns:` block + `sniffer:` block from a raw config YAML, then inject the
/// pinned DNS block. Operates on a generic `serde_yaml::Value` so unmodelled
/// top-level keys (`tun:`, `experimental:`, `profile:`, etc.) round-trip
/// unchanged.
pub fn strip_and_inject(yaml: &str) -> Result<String> {
    let mut doc: serde_yaml::Value = serde_yaml::from_str(yaml).context("parsing config YAML")?;
    if let serde_yaml::Value::Mapping(m) = &mut doc {
        for key in [
            "port",
            "socks-port",
            "mixed-port",
            "tproxy-port",
            "listeners",
            "sniffer",
            "dns",
        ] {
            m.remove(serde_yaml::Value::String(key.to_string()));
        }
        if let serde_yaml::Value::Mapping(dns) = pinned_dns_block() {
            m.insert(
                serde_yaml::Value::String("dns".into()),
                serde_yaml::Value::Mapping(dns),
            );
        }
    }
    serde_yaml::to_string(&doc).context("serializing stripped config YAML")
}

/// RAII handle that removes a file on drop. Used so the sibling
/// `config.yaml.android-stripped.yaml` we hand to `load_config` never
/// survives past the load call — including on `?` early-returns, panics,
/// and profile-swap failures.
struct TempFileGuard(PathBuf);

impl Drop for TempFileGuard {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

/// Read `config_path`, strip + inject pinned DNS, and hand a sibling
/// `config.yaml.android-stripped.yaml` to `load_config`. The sibling
/// placement is deliberate: `load_config` uses `path.parent()` as the
/// rule-/proxy-provider `cache_dir`, so colocating with the original
/// keeps rule-provider cache files in the home dir. Using
/// `load_config_from_str` would silently disable that caching.
pub async fn load_stripped_config(config_path: &str) -> Result<Config> {
    let original = std::fs::read_to_string(config_path)
        .with_context(|| format!("reading config from {config_path}"))?;
    let stripped = strip_and_inject(&original)?;
    let stripped_path = sibling_stripped_path(config_path);
    std::fs::write(&stripped_path, stripped)
        .with_context(|| format!("writing stripped config to {}", stripped_path.display()))?;
    let _guard = TempFileGuard(stripped_path.clone());
    let cfg = load_config(stripped_path.to_str().expect("utf-8 path")).await?;
    Ok(cfg)
}

fn sibling_stripped_path(config_path: &str) -> PathBuf {
    Path::new(config_path).with_extension("android-stripped.yaml")
}
