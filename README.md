# Meow

![Feature Graphic](fastlane/metadata/android/en-US/images/featureGraphic.png)

A Clash/meow Android client with a native Jetpack Compose UI, powered by [meow-rs](https://github.com/madeye/meow-rs) and lwip-based tun2socks.

An iOS port is in public beta — see [madeye/meow-ios](https://github.com/madeye/meow-ios).

## Download

[<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=io.github.madeye.meow)
[<img src="https://img.shields.io/badge/Download_from-GitHub-333?style=for-the-badge&logo=github&logoColor=white" alt="Download from GitHub" height="80">](https://github.com/madeye/meow/releases/latest)
[<img src="https://img.shields.io/badge/iOS-TestFlight_Beta-0070F5?style=for-the-badge&logo=apple&logoColor=white" alt="Join the iOS TestFlight public beta" height="80">](https://testflight.apple.com/join/nnDAn7ZH)

## Architecture

```
Compose UI (Kotlin)
    |  ViewModels over StateFlow; OkHttp to the engine's loopback API
    v
Android Native (Kotlin)
    |  VpnService + AIDL IPC
    |  JNI (System.loadLibrary)
    v
Rust FFI (libmeow_android_ffi.so)
    |  lwip netstack tun2socks
    |  Per-socket VpnService.protect() via JNI
    v
meow-rs (Cargo dependency)
    |  Tunnel, Config, Proxy, API
    v
Network
```

## Features

- **Proxy Protocols**: Shadowsocks, Trojan, AnyTLS, Direct
  - Shadowsocks plugins: built-in `simple-obfs` (HTTP/TLS) and `v2ray-plugin`
    (WebSocket, optional TLS) — no external SIP003 binary required
- **Transports**: TLS (with ECH), WebSocket, gRPC, HTTP/2, HTTPUpgrade
- **Rule Engine**: Domain, IP, port, geo-based routing, rule-providers, proxy groups
- **tun2socks**: Userspace lwIP netstack (patched `lwip` crate)
- **DNS**: Engine-owned fake-IP resolver; every in-TUN DNS query is answered
  in-process and upstream lookups bypass the tunnel via protected sockets
- **Socket Protection**: Per-socket `VpnService.protect(fd)` via JNI callback
- **REST API**: Embedded external controller (`127.0.0.1:9090`) drives the
  live connections, logs, rules, and traffic views
- **Compose UI**: Shadowrocket-style tab view, styled to match the iOS app
  - Home: VPN toggle, proxy group & node selection, connection status
  - Subscribe: Add/edit/remove subscriptions, YAML editor, import/export config
  - Traffic: Real-time speed chart, session upload/download stats
  - Connections / Logs / Rules: Live views powered by the REST API
  - Settings: Version, network config, per-app VPN proxy/bypass, connectivity
    diagnostics, about
- **i18n**: English, Chinese (zh_CN)
- **E2E Tests**: Automated with ssserver + Android emulator (SS and HTTP-proxy harnesses)

## Building

### Prerequisites

- Android SDK (API 36) with NDK
- Rust toolchain with Android targets:
  ```
  rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
  ```
- JDK 17

### Build

```bash
# Build debug APK (arm64 only, release Rust)
export JAVA_HOME=/path/to/jdk17
./gradlew :mobile:assembleDebug -PTARGET_ABI=arm64 -PCARGO_PROFILE=release
```

The APK is at `mobile/build/outputs/apk/debug/mobile-arm64-v8a-debug.apk`.

### E2E Test

```bash
# Requires: ssserver, Android emulator, adb
./test-e2e.sh
```

## Project Structure

```
core/                           Android library module
  src/main/java/                Kotlin: VPN service, AIDL, Room DB
  src/main/rust/
    meow-android-ffi/         Rust FFI crate (JNI + tun2socks)
  src/main/java/.../api/        Engine controller client (REST + websocket)
  src/main/java/.../repo/       Profiles, per-app proxy, traffic history
mobile/                         Android app module (Compose UI host)
  src/main/java/.../ui/theme/   Brand tokens shared with the iOS app
  src/main/java/.../ui/screens/ Home, Subscribe, Traffic, Settings,
                                Connections, Logs, Rules, Per-app proxy,
                                YAML editor
  src/main/res/values{,-zh-rCN} Localization (en, zh_CN)
test-e2e.sh                     End-to-end test script (Shadowsocks)
test-e2e-http.sh                End-to-end test script (HTTP proxy)
```

## License

[MIT](LICENSE) - Max Lv <max.c.lv@gmail.com>
