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
cd core/src/main/rust/mihomo-android-ffi && cargo clippy -- -D warnings && cd -

# Rust format check
cd core/src/main/rust/mihomo-android-ffi && cargo fmt --check && cd -

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
Rust (libmihomo_android_ffi.so)   netstack-smoltcp tun2socks + meow-rs engine
```

### Rust FFI (`core/src/main/rust/mihomo-android-ffi/`)

- **lib.rs**: JNI entry points (`Java_io_github_madeye_meow_core_MihomoCore_*`), engine lifecycle (tokio runtime, Tunnel, API server). No SOCKS5/HTTP loopback listener — every TUN flow is dispatched in-process.
- **tun2socks.rs**: Reads TUN fd packets → feeds to `netstack-smoltcp` Stack → each accepted TCP flow is wrapped as a `ProxyConn` newtype around the netstack `TcpStream` and handed straight to `meow_tunnel::tcp::handle_tcp(&inner, conn, metadata)`. UDP/53 intercepted and answered by the in-process plain-TCP DNS client (which uses the same `handle_tcp` path).
- **protect.rs**: Implements `meow_common::SocketProtector` via a JNI shim around `VpnService.protect(int)`. Installed once in `nativeStartTun2Socks`; meow-rs invokes it for every outbound TCP/UDP fd (proxy adapters + the DNS resolver's default `SocketFactory`) before `connect()`/`bind()`.
- **engine.rs**: `tunnel()` accessor — returns the running `Tunnel` handle so `tun2socks`, `dns_client`, and `china_dns` can dispatch flows through `meow_tunnel::tcp::handle_tcp` without re-implementing rule routing.
- **doh_client.rs**: DNS-over-HTTPS via reqwest. Falls back to `1.1.1.1` and `8.8.8.8`.

### Kotlin Core (`core/src/main/java/io/github/madeye/meow/`)

- **bg/BaseService.kt**: State machine (Idle→Connecting→Connected→Stopping→Stopped) with AIDL binder, RemoteCallbackList for traffic callbacks. Ported from shadowsocks-android.
- **bg/VpnService.kt**: Creates TUN interface (172.19.0.1/30, MTU 1500, route 0.0.0.0/0). Passes TUN fd + `this` (VpnService) to Rust via JNI. DNS set to 172.19.0.2 (routed through TUN → tun2socks DoH).
- **bg/MihomoInstance.kt**: Writes config.yaml (stripping `dns:` and `subscriptions:` sections), calls JNI start/stop. DNS is disabled in mihomo — handled by tun2socks DoH.
- **core/MihomoCore.kt**: JNI bridge object. `System.loadLibrary("mihomo_android_ffi")`.
- **database/**: Room database with `ClashProfile` entity (id, name, url, yamlContent, selected, lastUpdated, tx, rx).

### Flutter UI (`flutter_module/lib/`)

- **app.dart**: MaterialApp with 4-tab NavigationBar (Home, Subscribe, Traffic, Settings). `profileChanged` ValueNotifier bridges subscription changes to home screen reload.
- **services/vpn_channel.dart**: Singleton wrapping MethodChannel/EventChannel for VPN control, profile CRUD, traffic streams.
- **l10n/strings.dart**: Map-based i18n (English default, Chinese via `_Zh` subclass). Uses `S.of(context)` pattern.
- **screens/home_screen.dart**: SliverAppBar with Switch toggle, proxy node list from selected profile's YAML, status card.
- **screens/traffic_screen.dart**: Real-time speed chart (CustomPainter), session upload/download/total cards (blue/green/purple).

### Key Data Flow

1. User taps VPN switch → Flutter `MethodChannel.invokeMethod('connect')` → Kotlin `startForegroundService(VpnService)` → `MihomoInstance.start()` writes config.yaml → JNI `nativeStartEngine()` → Rust starts tokio runtime, tunnel, API server → JNI `nativeStartTun2Socks(vpnService, fd, 1053)` → Rust installs the `SocketProtector` (JNI shim around `VpnService.protect`) into meow-common, then starts the netstack-smoltcp stack reading from TUN fd.

2. App traffic → TUN → tun2socks intercepts: UDP port 53 → in-process TCP DNS (china-dns split → `meow_tunnel::tcp::handle_tcp`); TCP → netstack-smoltcp accepts → `meow_tunnel::tcp::handle_tcp(&inner, NetstackConn(stream), metadata)` → mihomo routes via rules → proxy adapter (SS/Trojan/Direct) dials via `meow_common::connect_tcp` → installed `SocketProtector` fires `VpnService.protect(fd)` → connect bypasses VPN → remote server.

## Module Dependencies

```
mobile → core, flutter
core → rust (via rust-android-gradle cargo plugin)
mihomo-android-ffi → meow-{tunnel,config,dns,api,common,transport,proxy} (git dep, HEAD-pinned)
                   → netstack-smoltcp, jni, android_logger, reqwest, socket2
```

## E2E Test Structure

`test-e2e.sh` runs 5 tests: tun0 exists, DNS resolution, TCP 1.1.1.1:80, TCP 8.8.8.8:443, HTTP curl to Google generate_204. Uses `ssserver` on host (plain SS, no plugin), pushes a static `curl-aarch64` binary, injects Room database via sqlite3 + `run-as`, triggers VPN via `am start --ez auto_connect true`, accepts VPN consent dialog via uiautomator.
