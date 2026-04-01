mod diagnostics;
mod dns_table;
mod doh_client;
mod listener;
mod logging;
mod protect;
mod tun2socks;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use listener::MixedListener;
use mihomo_api::ApiServer;
use mihomo_tunnel::Tunnel;
use parking_lot::Mutex;
use std::sync::{Arc, OnceLock};

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

struct EngineState {
    tunnel: Tunnel,
    _handles: Vec<tokio::task::JoinHandle<()>>,
}

static ENGINE: Mutex<Option<EngineState>> = Mutex::new(None);
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
// Engine lifecycle
// ---------------------------------------------------------------------------

fn start_engine(
    external_controller: Option<String>,
    secret: Option<String>,
) -> i32 {
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

    let config_str = if let Some(dir) = HOME_DIR.lock().as_ref() {
        let path = format!("{}/config.yaml", dir);
        logging::bridge_log(&format!("start_engine_async: loading config from {}", path));
        match std::fs::read_to_string(&path) {
            Ok(s) => s,
            Err(e) => {
                logging::bridge_log(&format!("start_engine_async: failed to read {}: {}, using minimal", path, e));
                MINIMAL_CONFIG.to_string()
            }
        }
    } else {
        logging::bridge_log("start_engine_async: no home dir, using minimal config");
        MINIMAL_CONFIG.to_string()
    };
    let mut config = mihomo_config::load_config_from_str(&config_str)?;
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

    let raw_config = Arc::new(parking_lot::RwLock::new(config.raw.clone()));
    let tunnel = Tunnel::new(config.dns.resolver.clone());
    tunnel.set_mode(config.general.mode);
    tunnel.update_rules(config.rules);
    tunnel.update_proxies(config.proxies);

    let mut handles: Vec<tokio::task::JoinHandle<()>> = Vec::new();

    // DNS is handled by tun2socks DoH forwarder, not mihomo's DNS server

    if let Some(api_addr) = config.api.external_controller {
        let api_server = ApiServer::new(
            tunnel.clone(),
            api_addr,
            config.api.secret.clone(),
            String::new(),
            raw_config.clone(),
        );
        handles.push(tokio::spawn(async move {
            if let Err(e) = api_server.run().await {
                tracing::error!("API server error: {}", e);
            }
        }));
    }

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
    *HOME_DIR.lock() = if dir_str.is_empty() { None } else { Some(dir_str) };
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeStartEngine(
    mut env: JNIEnv,
    _class: JClass,
    addr: JString,
    secret: JString,
) -> jint {
    let addr_str: String = env.get_string(&addr).map(|s| s.into()).unwrap_or_default();
    let secret_str: String = env.get_string(&secret).map(|s| s.into()).unwrap_or_default();
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
    if ENGINE.lock().is_some() { JNI_TRUE } else { JNI_FALSE }
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
    match mihomo_config::load_config_from_str(&yaml_str) {
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
    env.new_string("mihomo-rust 0.2.0")
        .unwrap_or_else(|_| env.new_string("").unwrap())
        .into_raw()
}
