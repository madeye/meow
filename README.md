# Meow

![Feature Graphic](fastlane/metadata/android/en-US/images/featureGraphic.png)

A Clash/mihomo Android client with Flutter UI, powered by [meow-rs](https://github.com/madeye/meow-rs) and netstack-smoltcp tun2socks.

An iOS port is in public beta — see [madeye/meow-ios](https://github.com/madeye/meow-ios).

## Download

[<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=io.github.madeye.meow)
[<img src="https://img.shields.io/badge/Download_from-GitHub-333?style=for-the-badge&logo=github&logoColor=white" alt="Download from GitHub" height="80">](https://github.com/madeye/meow/releases/latest)
[<img src="https://img.shields.io/badge/iOS-TestFlight_Beta-0070F5?style=for-the-badge&logo=apple&logoColor=white" alt="Join the iOS TestFlight public beta" height="80">](https://testflight.apple.com/join/nnDAn7ZH)

## Architecture

```
Flutter UI (Dart)
    |  MethodChannel / EventChannel
    v
Android Native (Kotlin)
    |  VpnService + AIDL IPC
    |  JNI (System.loadLibrary)
    v
Rust FFI (libmihomo_android_ffi.so)
    |  netstack-smoltcp tun2socks
    |  Per-socket VpnService.protect() via JNI
    v
meow-rs (Cargo dependency)
    |  Tunnel, Config, Proxy, API
    v
Network
```

## Features

- **Proxy Protocols**: Shadowsocks, Trojan, Direct
  - Shadowsocks plugins: built-in `simple-obfs` (HTTP/TLS) and `v2ray-plugin`
    (WebSocket, optional TLS) — no external SIP003 binary required
- **Rule Engine**: Domain, IP, port, geo-based routing, rule-providers
- **tun2socks**: Pure Rust via netstack-smoltcp (no C dependencies)
- **DNS**: DoH forwarding through proxy chain
- **Socket Protection**: Per-socket `VpnService.protect(fd)` via JNI callback
- **Flutter UI**: Shadowrocket-style tab view
  - Home: VPN toggle, proxy node selection, connection status
  - Subscribe: Add/edit/remove subscriptions, view proxy nodes, YAML editor
  - Traffic: Real-time speed chart, session upload/download stats
  - Settings: Version, network config, per-app VPN proxy/bypass, about
- **i18n**: English, Chinese (zh_CN)
- **E2E Tests**: Automated with ssserver + Android emulator

## Building

### Prerequisites

- Android SDK (API 36) with NDK
- Rust toolchain with Android targets:
  ```
  rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
  ```
- Flutter SDK (3.x)
- JDK 17

### Build

```bash
# Generate Flutter module files
cd flutter_module && flutter pub get && cd ..

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
    mihomo-android-ffi/         Rust FFI crate (JNI + tun2socks)
flutter_module/                 Flutter UI module
  lib/screens/                  Home, Subscriptions, Traffic, Settings
  lib/l10n/                     Localization (en, zh_CN)
mobile/                         Android app module (FlutterActivity host)
test-e2e.sh                     End-to-end test script
```

## License

[MIT](LICENSE) - Max Lv <max.c.lv@gmail.com>
