//! Platform-neutral core of the meow mobile native stack.
//!
//! Embeds the meow-rs proxy engine (pinned to a release tag) and the
//! tun2socks layer, exposing a plain-Rust API that the per-platform FFI
//! surfaces wrap:
//!
//!   * `mihomo-android-ffi` — JNI surface for the Kotlin `VpnService`
//!     (`Java_io_github_madeye_meow_core_MihomoCore_*`).
//!   * `mihomo-ohos-ffi` — C ABI surface for the HarmonyOS (OpenHarmony)
//!     NAPI glue in `harmony/entry/src/main/cpp`.
//!
//! Outbound socket protection is wired through upstream's
//! `meow_common::SocketProtector` hook, installed by the platform crates
//! (JNI shim around `VpnService.protect` on Android; NAPI callback into the
//! ArkTS `VpnConnection.protect` on HarmonyOS). Every netstack TCP flow is
//! dispatched in-process via `meow_tunnel::tcp::handle_tcp` — no SOCKS5
//! loopback hop. DNS is delegated to mihomo's resolver running in fake-IP
//! mode (28.0.0.0/8) with a pinned CN-side upstream pool injected by
//! `engine::strip_and_inject`; the tun2socks UDP/53 intercept hands every
//! in-TUN DNS datagram straight to `meow_dns::DnsServer::handle_query`
//! (A/AAAA) or forwards verbatim to the pinned upstreams (anything else).
//! Mirrors meow-ios.

pub mod diagnostics;
pub mod engine;
pub mod logging;
#[cfg(feature = "testsupport")]
pub mod testsupport;
pub mod tun2socks;

use dashmap::DashMap;
use meow_api::log_stream::{LogBroadcastLayer, LogMessage};
use meow_api::ApiServer;
use meow_tunnel::Tunnel;
use parking_lot::{Mutex, RwLock};
use std::collections::HashMap;
use std::sync::{Arc, Once, OnceLock};
use tokio::sync::broadcast;
use tracing_subscriber::filter::LevelFilter;
use tracing_subscriber::prelude::*;

/// Engine version string reported to the UI layers.
pub const VERSION: &str = "meow-rs v0.18.0";

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();

pub(crate) fn get_runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        // Worker count left at the tokio default (one per CPU). Blocking
        // pool capped at 2 so background work (file I/O, redb writes, geoip
        // mmdb scans) can't explode RSS via tokio's default 512-thread cap.
        // Per-thread stack capped at 512 KB (default 2 MB) — async leaf
        // tasks don't recurse deeply, and this saves ~3 MB RSS per thread
        // once the blocking pool warms up.
        tokio::runtime::Builder::new_multi_thread()
            .max_blocking_threads(2)
            .thread_stack_size(512 * 1024)
            .enable_all()
            .build()
            .expect("Failed to create tokio runtime")
    })
}

pub(crate) struct EngineState {
    pub(crate) tunnel: Tunnel,
    _handles: Vec<tokio::task::JoinHandle<()>>,
}

pub(crate) static ENGINE: Mutex<Option<EngineState>> = Mutex::new(None);
pub(crate) static HOME_DIR: Mutex<Option<String>> = Mutex::new(None);
pub(crate) static DNS_RESOLVER: OnceLock<Arc<meow_dns::Resolver>> = OnceLock::new();

// ---------------------------------------------------------------------------
// Thread-local error message
//
// Thread-local (not global) on purpose: the platform FFI surfaces call
// `get_last_error` from the same thread that just observed a failure return
// code, and a global slot would let a concurrent start on another binder
// thread clobber the message in between.
// ---------------------------------------------------------------------------

thread_local! {
    static LAST_ERROR: std::cell::RefCell<String> = const { std::cell::RefCell::new(String::new()) };
}

pub fn set_error(msg: String) {
    LAST_ERROR.with(|e| *e.borrow_mut() = msg);
}

pub fn get_error() -> String {
    LAST_ERROR.with(|e| e.borrow().clone())
}

// ---------------------------------------------------------------------------
// Minimal config
// ---------------------------------------------------------------------------

const MINIMAL_CONFIG: &str = "\
mode: rule\n\
log-level: info\n\
allow-lan: false\n\
proxies: []\n\
proxy-groups: []\n\
rules:\n\
  - MATCH,DIRECT\n\
";

// ---------------------------------------------------------------------------
// Process-wide tracing subscriber + log broadcast channel
//
// Mirrors meow-ios `engine::log_broadcast_tx` / `install_tracing_subscriber`.
// `set_global_default` can only be installed once per process; subsequent
// engine restarts reuse the same broadcast::Sender that was registered the
// first time.
// ---------------------------------------------------------------------------

fn log_broadcast_tx() -> &'static broadcast::Sender<LogMessage> {
    static TX: OnceLock<broadcast::Sender<LogMessage>> = OnceLock::new();
    TX.get_or_init(|| {
        let (tx, _rx) = broadcast::channel(128);
        tx
    })
}

fn install_tracing_subscriber() {
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        let log_layer = LogBroadcastLayer {
            tx: log_broadcast_tx().clone(),
        }
        .with_filter(LevelFilter::INFO);
        let _ = tracing_subscriber::registry().with(log_layer).try_init();
    });
    spawn_log_buffer_drainer();
}

// ---------------------------------------------------------------------------
// In-memory log ring buffer for the UI log poll
//
// The logs screen polls every couple of seconds and appends whatever it gets,
// so reads have drain semantics: a background task accumulates formatted lines
// from the same broadcast channel the API `/logs` endpoint uses, and
// `drain_logs` returns + clears the pending lines. The buffer is capped so
// it stays bounded while the screen is closed (oldest lines dropped first).
// ---------------------------------------------------------------------------

const LOG_BUFFER_CAP: usize = 2000;

fn log_buffer() -> &'static Mutex<std::collections::VecDeque<String>> {
    static BUF: OnceLock<Mutex<std::collections::VecDeque<String>>> = OnceLock::new();
    BUF.get_or_init(|| Mutex::new(std::collections::VecDeque::new()))
}

fn spawn_log_buffer_drainer() {
    static SPAWNED: Once = Once::new();
    SPAWNED.call_once(|| {
        let mut rx = log_broadcast_tx().subscribe();
        get_runtime().spawn(async move {
            loop {
                match rx.recv().await {
                    Ok(msg) => {
                        let line = format!("{} {}", msg.level.as_str().to_uppercase(), msg.payload);
                        let mut buf = log_buffer().lock();
                        buf.push_back(line);
                        while buf.len() > LOG_BUFFER_CAP {
                            buf.pop_front();
                        }
                    }
                    // Reader fell behind the 128-slot channel; skip the gap.
                    Err(broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }
        });
    });
}

/// Drains and returns the buffered engine log lines (empty if none pending).
/// Safe to call whether or not the engine is running.
pub fn drain_logs() -> Vec<String> {
    log_buffer().lock().drain(..).collect()
}

// ---------------------------------------------------------------------------
// Public lifecycle API (wrapped by the platform FFI crates)
// ---------------------------------------------------------------------------

/// Set (or clear, with `None`) the engine home dir. `config.yaml`, geodata
/// DBs, and the DNS cache all live under it; `XDG_CONFIG_HOME` is pointed at
/// its parent so meow-config resolves `$XDG_CONFIG_HOME/meow/Country.mmdb`.
pub fn set_home_dir(dir: Option<String>) {
    match dir {
        None => *HOME_DIR.lock() = None,
        Some(dir_str) if dir_str.is_empty() => *HOME_DIR.lock() = None,
        Some(dir_str) => {
            // meow-config resolves geodata at `$XDG_CONFIG_HOME/meow/Country.mmdb`.
            // Our home dir is `.../meow`, so XDG_CONFIG_HOME is its parent. Set it
            // here (not only in start_engine) so config validation — used by the
            // YAML editor and config import before any VPN start — can load the
            // bundled GeoIP DB instead of failing on the default relative
            // `./meow/Country.mmdb` path.
            if let Some(parent) = std::path::Path::new(&dir_str).parent() {
                std::env::set_var("XDG_CONFIG_HOME", parent);
            }
            *HOME_DIR.lock() = Some(dir_str);
        }
    }
}

/// Start the embedded engine. Returns 0 on success, -1 on failure (message
/// retrievable via [`get_error`] on the same thread). Mirrors the historical
/// JNI return-code contract so both platform surfaces stay thin.
pub fn start_engine(external_controller: Option<String>, secret: Option<String>) -> i32 {
    logging::bridge_log("start_engine: acquiring ENGINE lock");
    let mut engine = ENGINE.lock();
    if engine.is_some() {
        set_error("proxy is already running".to_string());
        return -1;
    }

    let rt = get_runtime();
    match rt.block_on(async { start_engine_async(external_controller, secret).await }) {
        Ok(state) => {
            logging::bridge_log("start_engine: engine started successfully");
            *engine = Some(state);
            0
        }
        Err(e) => {
            logging::bridge_log(&format!("start_engine: ERROR: {}", e));
            set_error(format!("start proxy: {}", e));
            -1
        }
    }
}

/// Stop tun2socks + the engine. The platform crate is responsible for
/// clearing its `SocketProtector` (a platform-owned global) afterwards.
pub fn stop_engine() {
    tun2socks::stop();
    let mut engine = ENGINE.lock();
    if let Some(state) = engine.take() {
        for handle in state._handles {
            handle.abort();
        }
    }
}

pub fn is_running() -> bool {
    ENGINE.lock().is_some()
}

/// (upload, download) byte counters since engine start; (0, 0) when stopped.
/// `i64` to match the engine's statistics type (and the JNI `jlong` return).
pub fn traffic_snapshot() -> (i64, i64) {
    let engine = ENGINE.lock();
    match engine.as_ref() {
        Some(state) => state.tunnel.statistics().snapshot(),
        None => (0, 0),
    }
}

/// Validate a config YAML by round-tripping it through the real loader.
/// Returns 0 when valid, -1 otherwise (message via [`get_error`]).
pub fn validate_config(yaml: &str) -> i32 {
    match get_runtime().block_on(meow_config::load_config_from_str(yaml)) {
        Ok(_) => 0,
        Err(e) => {
            set_error(format!("validate config: {}", e));
            -1
        }
    }
}

async fn start_engine_async(
    external_controller: Option<String>,
    secret: Option<String>,
) -> Result<EngineState, anyhow::Error> {
    logging::bridge_log("start_engine_async: initializing rustls");
    let _ = rustls::crypto::ring::default_provider().install_default();
    install_tracing_subscriber();

    // Resolve config path + set XDG_CONFIG_HOME (meow-config looks for
    // $XDG_CONFIG_HOME/meow/Country.mmdb). Our dir is .../no_backup/meow,
    // so XDG_CONFIG_HOME is the parent.
    let config_path = if let Some(dir) = HOME_DIR.lock().as_ref() {
        if let Some(parent) = std::path::Path::new(dir).parent() {
            std::env::set_var("XDG_CONFIG_HOME", parent);
            logging::bridge_log(&format!(
                "start_engine_async: set XDG_CONFIG_HOME={}",
                parent.display()
            ));
        }
        Some(format!("{}/config.yaml", dir))
    } else {
        None
    };

    // Geodata DBs are bundled with the app and seeded into the engine home
    // dir by the platform layer before start_engine fires. The on-disk files
    // at `$XDG_CONFIG_HOME/meow/Country.mmdb` and `…/GeoLite2-ASN.mmdb` are
    // guaranteed to exist by the time we reach load_config, so no pre-VPN
    // network fetch is needed here.

    // Load via engine::load_stripped_config (mirrors meow-ios): strips
    // listener/sniffer/dns blocks and injects the pinned fake-IP DNS block.
    // Falls back to the minimal config (also passed through strip_and_inject)
    // when no home dir is set or the file is unreadable.
    let mut config = match config_path.as_deref() {
        Some(path) if std::path::Path::new(path).exists() => {
            logging::bridge_log(&format!("start_engine_async: loading config from {}", path));
            engine::load_stripped_config(path).await?
        }
        _ => {
            logging::bridge_log("start_engine_async: using minimal config");
            let stripped = engine::strip_and_inject(MINIMAL_CONFIG)?;
            meow_config::load_config_from_str(&stripped).await?
        }
    };
    logging::bridge_log(&format!(
        "start_engine_async: config loaded, proxies={}, rules={}",
        config.proxies.len(),
        config.rules.len()
    ));

    if let Some(addr) = external_controller {
        config.api.external_controller = addr.parse().ok();
    }
    if let Some(s) = secret {
        config.api.secret = if s.is_empty() { None } else { Some(s) };
    }

    // Install the global host-resolver hook so `meow_common::connect_tcp_host`
    // (used by every proxy adapter that dials by hostname — Trojan, VLESS,
    // SS, SOCKS5, HTTP, …) routes the lookup through meow-rs's own
    // `Resolver` instead of libc's `getaddrinfo`. Critical inside a VPN: the
    // tunnel's DNS server is `172.19.0.2` (our TUN), so `getaddrinfo` would
    // loop the query back through the engine's fake-IP pool and the
    // protected outbound socket would then try to dial a non-routable
    // `28.0.0.0/8` address. See meow-rs PR fix/connect-tcp-host-resolver-hook
    // and `meow-dns/src/host_resolver_hook.rs` for the bridge impl.
    //
    // The hook is compiled on every platform upstream, but only install it
    // where traffic actually flows through a VPN TUN (Android + HarmonyOS)
    // so host-side `cargo test` runs keep libc resolution.
    #[cfg(any(target_os = "android", target_env = "ohos"))]
    meow_common::set_host_resolver(Arc::new(meow_dns::ResolverHostHook::new(Arc::clone(
        &config.dns.resolver,
    ))));

    let _ = DNS_RESOLVER.set(config.dns.resolver.clone());

    let raw_config = Arc::new(RwLock::new(config.raw.clone()));
    let tunnel = Tunnel::new(config.dns.resolver.clone());
    tunnel.set_mode(config.general.mode);
    tunnel.update_rules(config.rules);
    tunnel.update_proxies(config.proxies);

    let mut handles: Vec<tokio::task::JoinHandle<()>> = Vec::new();

    let proxy_providers = {
        let map: DashMap<_, _> = config.proxy_providers.into_iter().collect();
        Arc::new(map)
    };
    let rule_providers = Arc::new(RwLock::new(
        config.rule_providers.into_iter().collect::<HashMap<_, _>>(),
    ));
    let listeners_for_api = config.listeners.named.clone();
    let log_tx = log_broadcast_tx().clone();

    if let Some(api_addr) = config.api.external_controller {
        let api_server = ApiServer::new(
            tunnel.clone(),
            api_addr,
            config.api.secret.clone(),
            String::new(),
            raw_config.clone(),
            log_tx,
            proxy_providers,
            rule_providers,
            listeners_for_api,
            // No external web UI dashboard — the Flutter app talks to the API
            // directly (added in meow-rs v0.15.1, #223).
            None,
        );
        handles.push(tokio::spawn(async move {
            if let Err(e) = api_server.run().await {
                tracing::error!("API server error: {}", e);
            }
        }));
    }

    // No SOCKS5 / HTTP loopback listener — tun2socks dispatches every flow
    // through `meow_tunnel::tcp::handle_tcp` in-process (same path as
    // meow-ios). The `listeners.*` block in user configs is intentionally
    // ignored on mobile targets.

    logging::bridge_log(&format!(
        "start_engine_async: all tasks spawned, handles={}",
        handles.len()
    ));
    Ok(EngineState {
        tunnel,
        _handles: handles,
    })
}
