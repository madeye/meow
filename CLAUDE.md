# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Prerequisites (one-time)
cd flutter_module && flutter pub get && cd ..

# Build debug APK (arm64 only, release native for smaller .so)
export JAVA_HOME=/path/to/jdk17
./gradlew :mobile:assembleDebug -PTARGET_ABI=arm64 -PCARGO_PROFILE=release -PGO_PROFILE=release

# Build all ABIs
./gradlew :mobile:assembleDebug -PCARGO_PROFILE=release -PGO_PROFILE=release

# Build Rust only (faster iteration on tun2socks)
./gradlew :core:cargoBuildArm64 -PCARGO_PROFILE=release

# Build Go mihomo only (faster iteration on engine)
./gradlew :core:goBuildArm64 -PGO_PROFILE=release

# Clean (includes cargo clean + go clean)
./gradlew clean

# E2E test (requires ssserver, Android emulator, adb)
# Configurable via: EMULATOR, ADB, AVD, APK, SSSERVER, SKIP_EMULATOR_BOOT
./test-e2e.sh

# Run with existing emulator
SKIP_EMULATOR_BOOT=true ./test-e2e.sh
```

**Prerequisites:**
- **JDK 17** — JDK 25 breaks Kotlin compiler. Set `JAVA_HOME` explicitly.
- **Android NDK** (installed automatically via `ndk.version` in the gradle config).
- **Rust toolchain** with Android targets — used to build `libmihomo_android_ffi.so`.
- **Go 1.23+** — used to cross-compile the upstream mihomo engine into `libmihomo.so`.

## Lint Commands

**You MUST run the relevant lint commands before considering any code change complete.** Fix all lint errors before committing.

```bash
# Android lint (Kotlin)
./gradlew :mobile:lintDebug -PTARGET_ABI=arm64 -PCARGO_PROFILE=release -PGO_PROFILE=release

# Rust clippy (from repo root)
cd core/src/main/rust/mihomo-android-ffi && cargo clippy -- -D warnings && cd -

# Rust format check
cd core/src/main/rust/mihomo-android-ffi && cargo fmt --check && cd -

# Go vet + gofmt
cd core/src/main/go/mihomo-core && go vet ./... && gofmt -l . && cd -

# Flutter analyze
cd flutter_module && flutter analyze && cd -
```

Run Android lint after Kotlin changes, clippy/rustfmt after Rust changes, `go vet`/`gofmt` after Go changes, and flutter analyze after Dart changes.

## Architecture

Four-layer stack: **Flutter UI → Kotlin VPN Service → (Rust tun2socks + Go mihomo engine)**

```
Flutter (Dart)                    MethodChannel("io.github.madeye.meow/vpn")
    ↕                             EventChannel("io.github.madeye.meow/vpn_state")
Kotlin (Android)                  EventChannel("io.github.madeye.meow/traffic")
    ↕ JNI
    ├── libmihomo_android_ffi.so  (Rust)  netstack-smoltcp tun2socks + DoH
    │        TUN fd → TCP → SOCKS5 127.0.0.1:7890
    │                UDP:53 → DoH via SOCKS5
    │
    └── libmihomo.so              (Go)    upstream MetaCubeX/mihomo engine
             mixed listener on 127.0.0.1:7890
             rules, proxy adapters, external-controller on 127.0.0.1:9090
             VpnService.protect(fd) via dialer.DefaultSocketHook
```

### Rust tun2socks (`core/src/main/rust/mihomo-android-ffi/`)

The Rust side no longer hosts the proxy engine — it is now a pure tun2socks /
DoH layer. All sockets it owns are loopback, so none of them need to be
protected against routing loops.

- **lib.rs**: JNI entry points (`Java_io_github_madeye_meow_core_Tun2SocksCore_*`) — `nativeInit`, `nativeSetHomeDir` (used by doh_client), `nativeStartTun2Socks`, `nativeStopTun2Socks`, `nativeGetLastError`.
- **tun2socks.rs**: Reads TUN fd packets → feeds to `netstack-smoltcp` Stack → TCP connections relayed via SOCKS5 to the Go mihomo mixed listener on `127.0.0.1:7890`. UDP port 53 intercepted for DoH.
- **doh_client.rs**: DNS-over-HTTPS via reqwest through SOCKS5 proxy. Reads DoH server URLs from the current profile's `config.yaml`, falls back to `1.1.1.1` and `8.8.8.8`.
- **dns_table.rs**, **logging.rs**: DNS bookkeeping and Android logcat bridge.

### Go mihomo engine (`core/src/main/go/mihomo-core/`)

A Go module compiled with `go build -buildmode=c-shared` into `libmihomo.so`.
Wraps upstream [`github.com/metacubex/mihomo`](https://github.com/metacubex/mihomo).

- **engine.go**: Lifecycle — `setHomeDir`, `startEngine` (installs protect hook, calls `hub.Parse`), `stopEngine` (calls `executor.Shutdown`), `validateConfig`, `version`.
- **protect.go**: Wires `dialer.DefaultSocketHook` so every outbound socket is passed through a cgo shim that calls `VpnService.protect(fd)` via JNI.
- **stats.go**: Reads `statistic.DefaultManager.Total()` for upload/download traffic.
- **diagnostics.go**: Ports of `testDirectTcp`, `testProxyHttp`, `testDnsResolver`.
- **android_log.go**: Subscribes to mihomo's log event stream and forwards each entry to Android logcat.
- **exports.go**: `//export`ed cgo entry points consumed by the JNI bridge.
- **jni_bridge.c**: Hand-written C file defining `Java_io_github_madeye_meow_core_MihomoEngine_*`. Translates jstring / jbyte[] args to C types, calls the cgo exports, repackages results. Also owns the `meow_jni_protect` callback invoked from protect.go.

### Kotlin Core (`core/src/main/java/io/github/madeye/meow/`)

- **bg/BaseService.kt**: State machine (Idle→Connecting→Connected→Stopping→Stopped) with AIDL binder, RemoteCallbackList for traffic callbacks. Ported from shadowsocks-android.
- **bg/VpnService.kt**: Creates TUN interface (172.19.0.1/30, MTU 1500, route 0.0.0.0/0). Passes TUN fd + `this` (VpnService) to the native layer via JNI. DNS set to 172.19.0.2 (routed through TUN → tun2socks DoH).
- **bg/MihomoInstance.kt**: Writes config.yaml (stripping `dns:` and `subscriptions:` sections, prepending `mixed-port: 7890`), calls both libraries' JNI start/stop entry points in order. DNS is disabled in mihomo — handled by tun2socks DoH.
- **core/MihomoEngine.kt**: JNI bridge object for the Go engine. `System.loadLibrary("mihomo")`.
- **core/Tun2SocksCore.kt**: JNI bridge object for the Rust tun2socks layer. `System.loadLibrary("mihomo_android_ffi")`.
- **database/**: Room database with `ClashProfile` entity (id, name, url, yamlContent, selected, lastUpdated, tx, rx).

### Flutter UI (`flutter_module/lib/`)

- **app.dart**: MaterialApp with 4-tab NavigationBar (Home, Subscribe, Traffic, Settings). `profileChanged` ValueNotifier bridges subscription changes to home screen reload.
- **services/vpn_channel.dart**: Singleton wrapping MethodChannel/EventChannel for VPN control, profile CRUD, traffic streams.
- **l10n/strings.dart**: Map-based i18n (English default, Chinese via `_Zh` subclass). Uses `S.of(context)` pattern.
- **screens/home_screen.dart**: SliverAppBar with Switch toggle, proxy node list from selected profile's YAML, status card.
- **screens/traffic_screen.dart**: Real-time speed chart (CustomPainter), session upload/download/total cards (blue/green/purple).

### Key Data Flow

1. User taps VPN switch → Flutter `MethodChannel.invokeMethod('connect')` → Kotlin `startForegroundService(VpnService)` → `MihomoInstance.start()` writes config.yaml → `MihomoEngine.nativeStartEngine()` → Go mihomo loads config, installs the protect hook, starts the hub (mixed listener + external-controller) → Kotlin calls `MihomoEngine.nativeSetProtect(vpnService)` so the hook can reach the Android `VpnService.protect(int)` method → Kotlin calls `Tun2SocksCore.nativeStartTun2Socks(vpnService, fd, 7890, 1053)` → Rust starts the netstack-smoltcp stack reading from the TUN fd.

2. App traffic → TUN → Rust tun2socks intercepts: UDP port 53 → DoH; TCP → netstack-smoltcp accepts → SOCKS5 to 127.0.0.1:7890 → Go mihomo routes via rules → proxy adapter (SS/Trojan/Direct) creates outbound socket → `dialer.DefaultSocketHook` fires → cgo shim calls `VpnService.protect(fd)` → connect bypasses VPN → remote server.

## Module Dependencies

```
mobile → core, flutter
core → rust (via rust-android-gradle cargo plugin) + go (via custom gradle Exec tasks)

mihomo-android-ffi (Rust)
    → netstack-smoltcp, jni, android_logger, reqwest (DoH), rustls, tokio

mihomo-core (Go)
    → github.com/metacubex/mihomo (upstream, via go.mod network fetch)
    → golang.org/x/net (for the SOCKS proxy test helper)
```

## E2E Test Structure

`test-e2e.sh` runs 5 tests: tun0 exists, DNS resolution, TCP 1.1.1.1:80, TCP 8.8.8.8:443, HTTP curl to Google generate_204. Uses `ssserver` on host (plain SS, no plugin), pushes a static `curl-aarch64` binary, injects Room database via sqlite3 + `run-as`, triggers VPN via `am start --ez auto_connect true`, accepts VPN consent dialog via uiautomator.
