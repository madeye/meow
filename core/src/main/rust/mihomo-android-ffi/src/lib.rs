//! Rust half of the meow-android native stack — JNI surface for the Kotlin
//! VPN service.
//!
//! Embeds the mihomo-rust v0.6.0 proxy engine (with a local fork of
//! `mihomo-proxy` carrying the `set_pre_connect_hook` patch) and the
//! tun2socks layer in one cdylib. Ordinary TCP traffic is dispatched via a
//! local SOCKS5 listener (`MixedListener` on 127.0.0.1:7890) — see
//! `tun2socks.rs`. The DoH client (`doh_client.rs`) and the China-DNS
//! split-horizon layer (`china_dns.rs`) dispatch in-process via
//! `mihomo_tunnel::tcp::handle_tcp` to avoid a startup-time dependency on
//! the SOCKS listener.

mod china_dns;
mod diagnostics;
mod dns_table;
mod doh_cache;
mod doh_client;
mod engine;
mod listener;
mod logging;
mod protect;
mod tun2socks;

use dashmap::DashMap;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use listener::MixedListener;
use mihomo_api::log_stream::{LogBroadcastLayer, LogMessage};
use mihomo_api::ApiServer;
use mihomo_dns::DnsServer;
use mihomo_tunnel::Tunnel;
use parking_lot::{Mutex, RwLock};
use std::collections::HashMap;
use std::sync::{Arc, Once, OnceLock};
use tokio::sync::broadcast;
use tracing_subscriber::filter::LevelFilter;
use tracing_subscriber::prelude::*;

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

pub(crate) struct EngineState {
    pub(crate) tunnel: Tunnel,
    _handles: Vec<tokio::task::JoinHandle<()>>,
}

pub(crate) static ENGINE: Mutex<Option<EngineState>> = Mutex::new(None);
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
// Minimal config
// ---------------------------------------------------------------------------

const MINIMAL_CONFIG: &str = "\
mixed-port: 7890\n\
mode: rule\n\
log-level: info\n\
allow-lan: false\n\
dns:\n\
  enable: false\n\
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

    let config_str = if let Some(dir) = HOME_DIR.lock().as_ref() {
        // Set XDG_CONFIG_HOME so mihomo-config finds GeoIP databases.
        // mihomo-config looks for $XDG_CONFIG_HOME/mihomo/Country.mmdb.
        // Our dir is .../no_backup/mihomo, so set XDG_CONFIG_HOME to its
        // parent (.../no_backup).
        if let Some(parent) = std::path::Path::new(dir).parent() {
            std::env::set_var("XDG_CONFIG_HOME", parent);
            logging::bridge_log(&format!(
                "start_engine_async: set XDG_CONFIG_HOME={}",
                parent.display()
            ));
        }
        let path = format!("{}/config.yaml", dir);
        logging::bridge_log(&format!("start_engine_async: loading config from {}", path));
        match std::fs::read_to_string(&path) {
            Ok(s) => s,
            Err(e) => {
                logging::bridge_log(&format!(
                    "start_engine_async: failed to read {}: {}, using minimal",
                    path, e
                ));
                MINIMAL_CONFIG.to_string()
            }
        }
    } else {
        logging::bridge_log("start_engine_async: no home dir, using minimal config");
        MINIMAL_CONFIG.to_string()
    };
    let mut config = mihomo_config::load_config_from_str(&config_str).await?;
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

    let raw_config = Arc::new(RwLock::new(config.raw.clone()));
    let tunnel = Tunnel::new(config.dns.resolver.clone());
    tunnel.set_mode(config.general.mode);
    tunnel.update_rules(config.rules);
    tunnel.update_proxies(config.proxies);

    let mut handles: Vec<tokio::task::JoinHandle<()>> = Vec::new();

    // mihomo-rust v0.6.0 ApiServer::new grew from 5 → 9 params for the new
    // /providers/*, /rules, /listeners and /logs routes. Build the required
    // shapes from the loaded Config.
    let proxy_providers = {
        let map: DashMap<_, _> = config.proxy_providers.into_iter().collect();
        Arc::new(map)
    };
    let rule_providers = Arc::new(RwLock::new(
        config.rule_providers.into_iter().collect::<HashMap<_, _>>(),
    ));
    let listeners_for_api = config.listeners.named.clone();
    let log_tx = log_broadcast_tx().clone();

    // DNS server task (v0.6.0 split: the resolver's UDP/53 listener moved
    // into a separate `DnsServer::new(resolver, addr).run()`). This is
    // optional — Android's tun2socks intercepts UDP/53 and dispatches to
    // china_dns/DoH, so the engine's listener typically is not configured.
    // If the user explicitly sets `dns.listen` in config.yaml, we honour it.
    if let Some(addr) = config.dns.listen_addr {
        let resolver = config.dns.resolver.clone();
        handles.push(tokio::spawn(async move {
            let dns_server = DnsServer::new(resolver, addr);
            if let Err(e) = dns_server.run().await {
                tracing::error!("DNS server error: {}", e);
            }
        }));
    }

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

    // Android-specific: keep the SOCKS5 mixed listener for ordinary
    // tun2socks TCP traffic. iOS dispatches in-process and skips this.
    let bind_addr = &config.listeners.bind_address;
    if let Some(port) = config.listeners.mixed_port {
        let addr: std::net::SocketAddr = format!("{}:{}", bind_addr, port).parse()?;
        let listener = MixedListener::new(tunnel.clone(), addr);
        handles.push(tokio::spawn(async move {
            if let Err(e) = listener.run().await {
                tracing::error!("Mixed listener error: {}", e);
            }
        }));
    }

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
    protect::clear_vpn_service();
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
    socks_port: jint,
    dns_port: jint,
) -> jint {
    logging::bridge_log(&format!(
        "nativeStartTun2Socks: fd={}, socks={}, dns={}",
        fd, socks_port, dns_port
    ));

    if fd < 0 {
        set_error("invalid file descriptor".to_string());
        return -1;
    }

    // Store VpnService reference for socket protection
    protect::set_vpn_service(&env, &vpn_service);

    // Register the protect hook in the patched mihomo-proxy
    mihomo_proxy::set_pre_connect_hook(protect::protect_fd);

    match tun2socks::start(fd, socks_port as u16, dns_port as u16) {
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
    match get_runtime().block_on(mihomo_config::load_config_from_str(&yaml_str)) {
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
    env.new_string("mihomo-rust 0.6.0")
        .unwrap_or_else(|_| env.new_string("").unwrap())
        .into_raw()
}
