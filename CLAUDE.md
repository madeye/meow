# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Prerequisites (one-time)
cd flutter_module && flutter pub get && cd ..

# Build debug APK (arm64 only, release Rust for smaller .so)
export JAVA_HOME=/path/to/jdk17
./gradlew :mobile:assembleDebug -PTARGET_ABI=arm64 -PCARGO_PROFILE=release

# Build all ABIs
./gradlew :mobile:assembleDebug -PCARGO_PROFILE=release

# Build Rust only (faster iteration on native code)
./gradlew :core:cargoBuildArm64 -PCARGO_PROFILE=release

# Clean (includes cargo clean)
./gradlew clean

# E2E test (requires ssserver, Android emulator, adb)
# Configurable via: EMULATOR, ADB, AVD, APK, SSSERVER, SKIP_EMULATOR_BOOT
./test-e2e.sh

# Run with existing emulator
SKIP_EMULATOR_BOOT=true ./test-e2e.sh
```

**JDK 17 is required** — JDK 25 breaks Kotlin compiler. Set `JAVA_HOME` explicitly.

## Lint Commands

**You MUST run the relevant lint commands before considering any code change complete.** Fix all lint errors before committing.

```bash
# Android lint (Kotlin)
./gradlew :mobile:lintDebug -PTARGET_ABI=arm64 -PCARGO_PROFILE=release

# Rust clippy (from repo root)
cd core/src/main/rust/meow-android-ffi && cargo clippy -- -D warnings && cd -

# Rust format check
cd core/src/main/rust/meow-android-ffi && cargo fmt --check && cd -

# Flutter analyze
cd flutter_module && flutter analyze && cd -
```

Run Android lint after Kotlin changes, clippy/rustfmt after Rust changes, and flutter analyze after Dart changes.

## Architecture

Three-layer stack: **Flutter UI → Kotlin VPN Service → Rust FFI**

```
Flutter (Dart)                    MethodChannel("io.github.madeye.meow/vpn")
    ↕                             EventChannel("io.github.madeye.meow/vpn_state")
Kotlin (Android)                  EventChannel("io.github.madeye.meow/traffic")
    ↕ JNI
Rust (libmeow_android_ffi.so)   lwip netstack tun2socks + meow-rs engine
```

### Rust FFI (`core/src/main/rust/meow-android-ffi/`)

- **lib.rs**: JNI entry points (`Java_io_github_madeye_meow_core_MeowCore_*`), engine lifecycle (tokio runtime, Tunnel, API server). No SOCKS5/HTTP loopback listener — every TUN flow is dispatched in-process.
- **tun2socks.rs**: Reads TUN fd packets. UDP/53 is intercepted pre-stack: A/AAAA queries are answered in-process by the engine's `meow_dns::Resolver` (`DnsServer::handle_query`), other qtypes are forwarded to upstream DNS over protected sockets. Everything else feeds the `lwip` netstack — each accepted TCP flow is wrapped as `NetstackConn` (a `ProxyConn` newtype around `lwip::TcpStream`) and handed straight to `meow_tunnel::tcp::handle_tcp(&inner, conn, metadata)`; UDP flows are dispatched to `meow_tunnel::udp::handle_udp` with per-session reply readers.
- **protect.rs**: Implements `meow_common::SocketProtector` via a JNI shim around `VpnService.protect(int)`. Installed once in `nativeStartTun2Socks`; meow-rs invokes it for every outbound TCP/UDP fd (proxy adapters + the DNS resolver's default `SocketFactory`) before `connect()`/`bind()`.
- **engine.rs**: `tunnel()` accessor — returns the running `Tunnel` handle so `tun2socks` can dispatch flows through `meow_tunnel::{tcp,udp}` without re-implementing rule routing. Also `strip_and_inject`: strips listener ports, `sniffer:`, and the user `dns:` block from config.yaml and injects the pinned fake-IP DNS block (same pattern as meow-ios).
- **diagnostics.rs**: JNI-exposed native connectivity probes (`nativeTestDirectTcp`, UDP) used by the Settings diagnostics UI.
- **logging.rs**: `android_logger` / tracing setup bridging Rust logs to logcat and the in-app log stream.

### Kotlin Core (`core/src/main/java/io/github/madeye/meow/`)

- **bg/BaseService.kt**: State machine (Idle→Connecting→Connected→Stopping→Stopped) with AIDL binder, RemoteCallbackList for traffic callbacks. Ported from shadowsocks-android.
- **bg/VpnService.kt**: Creates TUN interface (172.19.0.1/30, MTU 1500, route 0.0.0.0/0). Passes TUN fd + `this` (VpnService) to Rust via JNI. DNS set to 172.19.0.2 (routed through TUN → in-process DNS interception in tun2socks).
- **bg/MeowInstance.kt**: Writes config.yaml (stripping only the app-managed `subscriptions:` block — `dns:`/listeners/`sniffer:` are handled by `engine::strip_and_inject` on the Rust side), calls JNI start/stop.
- **core/MeowCore.kt**: JNI bridge object. `System.loadLibrary("meow_android_ffi")`.
- **database/**: Room database with `ClashProfile` entity (id, name, url, yamlContent, selected, lastUpdated, tx, rx).

### Flutter UI (`flutter_module/lib/`)

- **app.dart**: MaterialApp with 4-tab NavigationBar (Home, Subscribe, Traffic, Settings). `profileChanged` ValueNotifier bridges subscription changes to home screen reload.
- **services/vpn_channel.dart**: Singleton wrapping MethodChannel/EventChannel for VPN control, profile CRUD, traffic streams.
- **services/meow_api.dart**: Typed REST/WebSocket client for the embedded meow external-controller (always `http://127.0.0.1:9090`, no override — see `MeowInstance.kt`). Powers the connections/logs/rules/traffic live views. `services/traffic_history.dart` keeps a rolling traffic window.
- **l10n/strings.dart**: Map-based i18n (English default, Chinese via `_Zh` subclass). Uses `S.of(context)` pattern.
- **screens/**: `home_screen.dart` (Switch toggle + proxy node list), `traffic_screen.dart` (speed chart + session cards), plus `connections_screen`, `logs_screen`, `rules_screen` (driven by `meow_api.dart`), `subscriptions_screen` + `yaml_editor_screen` (profile/YAML editing), `per_app_proxy_screen`, and `settings_screen`.

### Key Data Flow

1. User taps VPN switch → Flutter `MethodChannel.invokeMethod('connect')` → Kotlin `startForegroundService(VpnService)` → `MeowInstance.start()` writes config.yaml → JNI `nativeStartEngine()` → Rust starts tokio runtime, tunnel, API server → JNI `nativeStartTun2Socks(vpnService, fd, 1053)` → Rust installs the `SocketProtector` (JNI shim around `VpnService.protect`) into meow-common, then starts the lwip netstack reading from TUN fd.

2. App traffic → TUN → tun2socks intercepts: UDP port 53 → in-process DNS (engine `meow_dns::Resolver` for A/AAAA, upstream passthrough otherwise); TCP → lwip netstack accepts → `meow_tunnel::tcp::handle_tcp(&inner, NetstackConn(stream), metadata)` → meow routes via rules → proxy adapter (SS/Trojan/Direct) dials via `meow_common::connect_tcp` → installed `SocketProtector` fires `VpnService.protect(fd)` → connect bypasses VPN → remote server.

## Module Dependencies

```
mobile → core, flutter
core → rust (via rust-android-gradle cargo plugin)
meow-android-ffi → meow-{tunnel,config,dns,api,common,transport,proxy} (git dep, tag-pinned, currently v0.20.1)
                   → lwip (patched madeye/lwip rev), jni, android_logger, redb, mimalloc
```

meow-rs crates are pinned by git **tag** in `Cargo.toml` — bumping the engine means changing the tag on every `meow-*` line. The full upstream protocol set is enabled explicitly (mirrors meow-app's `full` bundle and meow-ios): `ss`, `trojan`, `vless` (+`vless-vision`, +`vless-encryption`, REALITY), `vmess`, `snell`, `hysteria2`, `anytls`, `ech-tls-tunnel` (config/proxy) and `tls,ws,ech,grpc,h2,httpupgrade,reality,boring-tls` (transport; `boring-tls` provides proxy-outbound ECH + uTLS fingerprinting via vendored BoringSSL). Supported proxy protocols: Shadowsocks (with built-in `simple-obfs` and `v2ray-plugin`), Trojan, VLESS, VMess, Snell, Hysteria2, AnyTLS, Direct.

## E2E Test Structure

`test-e2e.sh` runs 5 tests: tun0 exists, DNS resolution, TCP 1.1.1.1:80, TCP 8.8.8.8:443, HTTP curl to Google generate_204. Uses `ssserver` on host (plain SS, no plugin), pushes a static `curl-aarch64` binary, injects Room database via sqlite3 + `run-as`, triggers VPN via `am start --ez auto_connect true`, accepts VPN consent dialog via uiautomator.
