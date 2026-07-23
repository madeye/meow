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

# HarmonyOS end-to-end verification (no device needed; requires zig OR
# OHOS_NDK_HOME for the target check; optional SDK build + hdc device stages)
./test-e2e-ohos.sh

# HarmonyOS device .so (requires the OpenHarmony native SDK)
OHOS_NDK_HOME=~/Library/OpenHarmony/Sdk/12 harmony/scripts/build-rust-ohos.sh
```

**JDK 17 is required** — JDK 25 breaks Kotlin compiler. Set `JAVA_HOME` explicitly.

## Lint Commands

**You MUST run the relevant lint commands before considering any code change complete.** Fix all lint errors before committing.

```bash
# Android lint (Kotlin)
./gradlew :mobile:lintDebug -PTARGET_ABI=arm64 -PCARGO_PROFILE=release

# Rust clippy + format check — run in EACH crate you touched (they are
# sibling crates, not a workspace: mihomo-ffi-core, mihomo-android-ffi,
# mihomo-ohos-ffi)
for c in mihomo-ffi-core mihomo-android-ffi mihomo-ohos-ffi; do
  (cd core/src/main/rust/$c && cargo clippy --all-targets -- -D warnings && cargo fmt --check)
done

# Rust host tests (engine e2e over fake TUN + C ABI e2e)
(cd core/src/main/rust/mihomo-ffi-core && cargo test)
(cd core/src/main/rust/mihomo-ohos-ffi && cargo test)

# Flutter analyze
cd flutter_module && flutter analyze && cd -
```

Run Android lint after Kotlin changes, clippy/rustfmt after Rust changes, and flutter analyze after Dart changes.

## Architecture

Three-layer stack on both platforms, sharing one native engine core:

```
Android: Flutter UI ↔ Kotlin VPN Service ↔ JNI      → libmihomo_android_ffi.so ┐
HarmonyOS: ArkTS UI ↔ MeowVpnAbility     ↔ NAPI/C++ → libmihomo_ohos_ffi.so    ├─ mihomo-ffi-core
                                                                               ┘  (lwip tun2socks + meow-rs)
```

Android channels: MethodChannel("io.github.madeye.meow/vpn"), EventChannel("…/vpn_state"), EventChannel("…/traffic").

### Rust native (`core/src/main/rust/` — three sibling crates, NOT a workspace)

- **mihomo-ffi-core/**: platform-neutral engine core shared by both platform
  crates. `lib.rs` (state, tokio runtime, engine lifecycle, log ring buffer,
  `start_engine`/`stop_engine`/`validate_config`/`traffic_snapshot`),
  `engine.rs` (`tunnel()` accessor + `strip_and_inject`: strips listener
  ports, `sniffer:`, user `dns:` and injects the pinned fake-IP DNS block,
  same pattern as meow-ios), `tun2socks.rs` (TUN fd → UDP/53 pre-stack
  intercept answered by `meow_dns::DnsServer::handle_query` for A/AAAA +
  upstream passthrough otherwise; everything else feeds the `lwip` netstack
  and each flow is dispatched in-process via `meow_tunnel::{tcp,udp}` —
  `NetstackConn` is the `ProxyConn` newtype around `lwip::TcpStream`),
  `diagnostics.rs` (connectivity probes), `testsupport.rs` (feature-gated
  fake-TUN + raw-packet helpers for the e2e tests), and `tests/e2e_tun.rs`
  (host e2e: fake TUN socketpair + smoltcp client → DNS fake-IP round-trip +
  TCP echo through netstack → tunnel → DIRECT).
- **mihomo-android-ffi/**: thin JNI surface
  (`Java_io_github_madeye_meow_core_MihomoCore_*`) + `protect.rs`, the
  `meow_common::SocketProtector` impl shimming `VpnService.protect(int)` —
  installed in `nativeStartTun2Socks`, fired for every outbound fd before
  `connect()`/`bind()`. Also `android_logger` init and the mimalloc global
  allocator.
- **mihomo-ohos-ffi/**: C ABI surface (`meow_*` functions) for the HarmonyOS
  NAPI glue; `crate-type = ["cdylib", "rlib"]` so `tests/c_abi_e2e.rs`
  exercises the exact C ABI on the host. `protect.rs` stores the ArkTS
  `VpnConnection.protect` callback — NOTE: meow-rs v0.18.0 compiles the
  `SocketProtector` registry only for Android (`target_env = "ohos"` is
  `target_os = "linux"`), so the engine does not yet invoke it per-fd on
  OpenHarmony; widening that cfg upstream + a tag bump closes the gap.
  `logging.rs` bridges the `log` facade to hilog (`OH_LOG_Print`).
- The three crates repeat the same `[patch.crates-io]` lwip pin (patches
  only apply from the build root). Bumping meow-rs means updating the tag in
  BOTH mihomo-ffi-core (all `meow-*` lines) and mihomo-android-ffi
  (`meow-common`).

### HarmonyOS app (`harmony/`)

DevEco Studio (hvigor) project targeting **HarmonyOS NEXT** (5.0/API 12,
`runtimeOS: HarmonyOS`, `@kit.*` imports) while staying OpenHarmony-
compatible — see `harmony/README.md`. HarmonyOS 4.x and earlier are
AOSP-based and use the Android APK.
`harmony/scripts/check_harmony_consistency.py` (run by `test-e2e-ohos.sh`)
cross-checks the FFI layers (Rust C ABI = C header = NAPI exports = ArkTS
d.ts ⊇ call sites), manifest resource refs, and NEXT conventions — keep it
passing when touching any of those files.
`entry/src/main/cpp/napi_init.cpp` is the NAPI glue (`libmeow.so`) over the
Rust C ABI (`mihomo_ohos_ffi.h` mirrors `mihomo-ohos-ffi/src/lib.rs`);
`entry/src/main/ets/vpnability/MeowVpnAbility.ets` is the VPN extension
(TUN 172.19.0.1/30, DNS 172.19.0.2, same plan as Android);
`scripts/build-rust-ohos.sh` builds + installs the device .so into
`entry/libs/<abi>/`.

### Kotlin Core (`core/src/main/java/io/github/madeye/meow/`)

- **bg/BaseService.kt**: State machine (Idle→Connecting→Connected→Stopping→Stopped) with AIDL binder, RemoteCallbackList for traffic callbacks. Ported from shadowsocks-android.
- **bg/VpnService.kt**: Creates TUN interface (172.19.0.1/30, MTU 1500, route 0.0.0.0/0). Passes TUN fd + `this` (VpnService) to Rust via JNI. DNS set to 172.19.0.2 (routed through TUN → in-process DNS interception in tun2socks).
- **bg/MihomoInstance.kt**: Writes config.yaml (stripping only the app-managed `subscriptions:` block — `dns:`/listeners/`sniffer:` are handled by `engine::strip_and_inject` on the Rust side), calls JNI start/stop.
- **core/MihomoCore.kt**: JNI bridge object. `System.loadLibrary("mihomo_android_ffi")`.
- **database/**: Room database with `ClashProfile` entity (id, name, url, yamlContent, selected, lastUpdated, tx, rx).

### Flutter UI (`flutter_module/lib/`)

- **app.dart**: MaterialApp with 4-tab NavigationBar (Home, Subscribe, Traffic, Settings). `profileChanged` ValueNotifier bridges subscription changes to home screen reload.
- **services/vpn_channel.dart**: Singleton wrapping MethodChannel/EventChannel for VPN control, profile CRUD, traffic streams.
- **services/mihomo_api.dart**: Typed REST/WebSocket client for the embedded mihomo external-controller (always `http://127.0.0.1:9090`, no override — see `MihomoInstance.kt`). Powers the connections/logs/rules/traffic live views. `services/traffic_history.dart` keeps a rolling traffic window.
- **l10n/strings.dart**: Map-based i18n (English default, Chinese via `_Zh` subclass). Uses `S.of(context)` pattern.
- **screens/**: `home_screen.dart` (Switch toggle + proxy node list), `traffic_screen.dart` (speed chart + session cards), plus `connections_screen`, `logs_screen`, `rules_screen` (driven by `mihomo_api.dart`), `subscriptions_screen` + `yaml_editor_screen` (profile/YAML editing), `per_app_proxy_screen`, and `settings_screen`.

### Key Data Flow

1. User taps VPN switch → Flutter `MethodChannel.invokeMethod('connect')` → Kotlin `startForegroundService(VpnService)` → `MihomoInstance.start()` writes config.yaml → JNI `nativeStartEngine()` → Rust starts tokio runtime, tunnel, API server → JNI `nativeStartTun2Socks(vpnService, fd, 1053)` → Rust installs the `SocketProtector` (JNI shim around `VpnService.protect`) into meow-common, then starts the lwip netstack reading from TUN fd.

2. App traffic → TUN → tun2socks intercepts: UDP port 53 → in-process DNS (engine `meow_dns::Resolver` for A/AAAA, upstream passthrough otherwise); TCP → lwip netstack accepts → `meow_tunnel::tcp::handle_tcp(&inner, NetstackConn(stream), metadata)` → mihomo routes via rules → proxy adapter (SS/Trojan/Direct) dials via `meow_common::connect_tcp` → installed `SocketProtector` fires `VpnService.protect(fd)` → connect bypasses VPN → remote server.

## Module Dependencies

```
mobile → core, flutter
core → rust (via rust-android-gradle cargo plugin, module mihomo-android-ffi)
harmony/entry (libmeow.so NAPI glue) → libmihomo_ohos_ffi.so
mihomo-android-ffi → mihomo-ffi-core, jni, android_logger, mimalloc, meow-common
mihomo-ohos-ffi   → mihomo-ffi-core, mimalloc (+ hilog on device)
mihomo-ffi-core   → meow-{tunnel,config,dns,api,common,transport,proxy} (git dep, tag-pinned, currently v0.18.0)
                  → lwip (patched madeye/lwip rev), redb
```

meow-rs crates are pinned by git **tag** in `Cargo.toml` — bumping the engine means changing the tag on every `meow-*` line. Enabled protocol/transport features: `anytls`, `ech-tls-tunnel` (config/proxy) and `tls,ws,ech,grpc,h2,httpupgrade` (transport). Supported proxy protocols: Shadowsocks (with built-in `simple-obfs` and `v2ray-plugin`), Trojan, AnyTLS, Direct.

## E2E Test Structure

`test-e2e.sh` runs 5 tests: tun0 exists, DNS resolution, TCP 1.1.1.1:80, TCP 8.8.8.8:443, HTTP curl to Google generate_204. Uses `ssserver` on host (plain SS, no plugin), pushes a static `curl-aarch64` binary, injects Room database via sqlite3 + `run-as`, triggers VPN via `am start --ez auto_connect true`, accepts VPN consent dialog via uiautomator.

`test-e2e-ohos.sh` verifies the HarmonyOS port without a device: (1) host
e2e tests that drive the real engine + lwip netstack through a fake TUN fd —
`mihomo-ffi-core/tests/e2e_tun.rs` (raw-packet DNS fake-IP round-trip +
full smoltcp TCP echo flow through netstack → tunnel → DIRECT) and
`mihomo-ohos-ffi/tests/c_abi_e2e.rs` (the exact C ABI the NAPI layer calls);
(2) `cargo check --target aarch64-unknown-linux-ohos` of the whole native
stack (OHOS SDK if `OHOS_NDK_HOME` is set, else zig musl stand-in);
(3) optional device .so build (`OHOS_NDK_HOME`) and (4) optional
install/launch via `hdc` when a device and built HAP are present.
