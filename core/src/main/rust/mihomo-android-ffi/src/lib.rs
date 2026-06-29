//! Rust half of the meow-android native stack — JNI surface for the Kotlin
//! VPN service.
//!
//! Embeds the meow-rs proxy engine (pinned to a HEAD revision) and the
//! tun2socks layer in one cdylib. Outbound socket protection is wired
//! through upstream's `meow_common::SocketProtector` hook — see
//! `protect.rs` — so no proxy-side patches are needed. Every netstack TCP flow is dispatched
//! in-process via `meow_tunnel::tcp::handle_tcp` — no SOCKS5 loopback
//! hop. DNS is delegated to mihomo's resolver running in fake-IP mode
//! (28.0.0.0/8) with a pinned CN-side upstream pool injected by
//! `engine::strip_and_inject`; the tun2socks UDP/53 intercept hands every
//! in-TUN DNS datagram straight to `meow_dns::DnsServer::handle_query`
//! (A/AAAA) or forwards verbatim to the pinned upstreams (anything else).
//! Mirrors meow-ios.

mod diagnostics;
mod engine;
mod logging;
mod protect;
mod tun2socks;

use dashmap::DashMap;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use meow_api::log_stream::{LogBroadcastLayer, LogMessage};
use meow_api::ApiServer;
use meow_tunnel::Tunnel;
use parking_lot::{Mutex, RwLock};
use std::collections::HashMap;
use std::sync::{Arc, Once, OnceLock};
use tokio::sync::broadcast;
use tracing_subscriber::filter::LevelFilter;
use tracing_subscriber::prelude::*;

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
// In-memory log ring buffer for the Kotlin `getLogs` MethodChannel poll
//
// The logs screen polls every couple of seconds and appends whatever it gets,
// so reads have drain semantics: a background task accumulates formatted lines
// from the same broadcast channel the API `/logs` endpoint uses, and
// `nativeGetLogs` returns + clears the pending lines. The buffer is capped so
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

fn drain_log_buffer() -> Vec<String> {
    log_buffer().lock().drain(..).collect()
}

// ---------------------------------------------------------------------------
// Engine lifecycle
// ---------------------------------------------------------------------------

fn start_engine(external_controller: Option<String>, secret: Option<String>) -> i32 {
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

    // Geodata DBs are bundled in the APK and seeded into the engine home
    // dir by MihomoInstance.copyGeoxAssets() before nativeStartEngine
    // fires. The on-disk files at `$XDG_CONFIG_HOME/meow/Country.mmdb` and
    // `…/GeoLite2-ASN.mmdb` are guaranteed to exist by the time we reach
    // load_config, so no pre-VPN network fetch is needed here. See
    // `core/build.gradle.kts` (downloadGeoxAssets) for the bundling path.

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
    // `Resolver` instead of libc's `getaddrinfo`. Critical on Android: the
    // VPN's DNS server is `172.19.0.2` (our TUN), so `getaddrinfo` would
    // loop the query back through the engine's fake-IP pool and the
    // protected outbound socket would then try to dial a non-routable
    // `28.0.0.0/8` address. See meow-rs PR fix/connect-tcp-host-resolver-hook
    // and `meow-dns/src/host_resolver_hook.rs` for the bridge impl.
    //
    // The hook itself is `#[cfg(target_os = "android")]` upstream — gate our
    // call to match so the FFI still builds for `cargo check` on host
    // (macOS/linux) when iterating locally.
    #[cfg(target_os = "android")]
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
    // ignored on Android.

    logging::bridge_log(&format!(
        "start_engine_async: all tasks spawned, handles={}",
        handles.len()
    ));
    Ok(EngineState {
        tunnel,
        _handles: handles,
    })
}

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) {
    logging::init_android_logger();
    logging::bridge_log("nativeInit: android logger initialized");
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeSetHomeDir(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
) {
    let dir_str: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    logging::bridge_log(&format!("nativeSetHomeDir: {}", dir_str));
    *HOME_DIR.lock() = if dir_str.is_empty() {
        None
    } else {
        Some(dir_str)
    };
}

/// Drains and returns the buffered engine log lines as a single newline-joined
/// string (empty if none pending). Kotlin splits it back into a list for the
/// logs screen. Safe to call whether or not the engine is running.
#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeGetLogs(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let joined = drain_log_buffer().join("\n");
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
    start_engine(Some(addr_str), Some(secret_str))
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeStopEngine(
    _env: JNIEnv,
    _class: JClass,
) {
    tun2socks::stop();
    protect::clear();
    let mut engine = ENGINE.lock();
    if let Some(state) = engine.take() {
        for handle in state._handles {
            handle.abort();
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeStartTun2Socks(
    env: JNIEnv,
    _class: JClass,
    vpn_service: JObject,
    fd: jint,
    dns_port: jint,
) -> jint {
    logging::bridge_log(&format!(
        "nativeStartTun2Socks: fd={}, dns={}",
        fd, dns_port
    ));

    if fd < 0 {
        set_error("invalid file descriptor".to_string());
        return -1;
    }

    // Install the global SocketProtector — every outbound TCP/UDP fd
    // meow-rs opens (proxy adapters + the DNS resolver's default
    // SocketFactory) will fire VpnService.protect() before connect/bind.
    protect::install(&env, &vpn_service);

    match tun2socks::start(fd, dns_port as u16) {
        Ok(()) => {
            logging::bridge_log("nativeStartTun2Socks: started successfully");
            0
        }
        Err(e) => {
            logging::bridge_log(&format!("nativeStartTun2Socks: ERROR: {}", e));
            set_error(e);
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeIsRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if ENGINE.lock().is_some() {
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
    let engine = ENGINE.lock();
    match engine.as_ref() {
        Some(state) => state.tunnel.statistics().snapshot().0,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeGetDownloadTraffic(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let engine = ENGINE.lock();
    match engine.as_ref() {
        Some(state) => state.tunnel.statistics().snapshot().1,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeValidateConfig(
    mut env: JNIEnv,
    _class: JClass,
    yaml: JString,
) -> jint {
    let yaml_str: String = env.get_string(&yaml).map(|s| s.into()).unwrap_or_default();
    match get_runtime().block_on(meow_config::load_config_from_str(&yaml_str)) {
        Ok(_) => 0,
        Err(e) => {
            set_error(format!("validate config: {}", e));
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeGetLastError(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let msg = get_error();
    env.new_string(&msg)
        .unwrap_or_else(|_| env.new_string("").unwrap())
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string("meow-rs 8502a1d")
        .unwrap_or_else(|_| env.new_string("").unwrap())
        .into_raw()
}
