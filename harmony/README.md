# Meow for HarmonyOS NEXT / OpenHarmony

Port of the meow VPN client for **HarmonyOS NEXT** (HarmonyOS 5.0+, API 12,
pure ArkTS — no AOSP/APK compatibility layer) and the open-source
**OpenHarmony** runtime it is based on. The project targets NEXT by default
(`runtimeOS: "HarmonyOS"`, `compatibleSdkVersion`/`targetSdkVersion`
`5.0.0(12)`, `@kit.*` imports) while using only APIs that exist in both
runtimes, so it also builds against an OpenHarmony SDK by switching
`runtimeOS` to `OpenHarmony`. Devices on HarmonyOS 4.x and earlier are
AOSP-based and are covered by the regular Android APK instead.

It reuses the exact same native engine as the Android app —
`mihomo-ffi-core` (meow-rs engine + lwip tun2socks, see
`../core/src/main/rust/`) — behind a C ABI crate (`mihomo-ohos-ffi`)
instead of the Android JNI crate.

```
ArkTS UI (entry/src/main/ets)          vpnExtension.startVpnExtensionAbility
    ↕                                  REST API http://127.0.0.1:9090
MeowVpnAbility (VpnExtensionAbility)   VpnConnection.create → TUN fd
    ↕ NAPI (libmeow.so, entry/src/main/cpp)
libmihomo_ohos_ffi.so (Rust)           lwip netstack tun2socks + meow-rs engine
```

## Layout

- `AppScope/`, `entry/` — standard DevEco Studio (hvigor, API 12 / 5.0)
  project. `entry/src/main/cpp` holds the NAPI glue (`libmeow.so`) that
  wraps the Rust C ABI; `entry/src/main/cpp/types/libmeow/index.d.ts` is the
  ArkTS surface.
- `entry/src/main/ets/vpnability/MeowVpnAbility.ets` — the VPN extension:
  creates the TUN (172.19.0.1/30, DNS 172.19.0.2, default route — same plan
  as Android `VpnService.kt`), then `meow.startEngine()` +
  `meow.startTun2Socks(tunFd)`.
- `scripts/build-rust-ohos.sh` — builds `libmihomo_ohos_ffi.so` with the
  OHOS SDK and installs it into `entry/libs/<abi>/`.
- `scripts/zig-cc-aarch64-ohos.sh` — zig-based musl stand-in C compiler used
  by `../test-e2e-ohos.sh` to type-check the whole native stack for
  `aarch64-unknown-linux-ohos` on hosts without the OHOS SDK.

## Build

1. Rust cdylib (needs the OpenHarmony native SDK):

   ```bash
   export OHOS_NDK_HOME=~/Library/OpenHarmony/Sdk/12   # dir containing native/
   harmony/scripts/build-rust-ohos.sh --profile release --abi arm64-v8a
   ```

2. HAP: open `harmony/` in DevEco Studio (or `hvigorw assembleHap`) — the
   CMake step links `libmeow.so` against the installed
   `entry/libs/arm64-v8a/libmihomo_ohos_ffi.so`.

## Tests

`../test-e2e-ohos.sh` verifies the port end-to-end without a device:
host e2e tests drive the real engine + netstack through a fake TUN fd
(DNS fake-IP round-trip, full TCP echo flow, C ABI lifecycle), then
`scripts/check_harmony_consistency.py` cross-checks the hand-maintained
FFI layers (Rust C ABI = C header = NAPI exports = ArkTS d.ts ⊇ call
sites), manifest resource references, and the NEXT conventions (@kit
imports, API 12 pinning), and finally the whole native stack is
type-checked for `aarch64-unknown-linux-ohos`. With `OHOS_NDK_HOME` set it
also builds the device .so, and with `hdc` + a device + a built HAP it
installs and launches the app.

## Known engine gap: per-fd socket protection

meow-rs v0.18.0 compiles its `SocketProtector` registry (the hook behind
`VpnService.protect` on Android) only for `target_os = "android"`; the
OpenHarmony targets are `target_os = "linux"` / `target_env = "ohos"`. The
NAPI protect bridge (ArkTS `VpnConnection.protect` ← threadsafe-function ←
Rust callback) is wired end-to-end here, but the engine will not invoke it
per-fd until the meow-rs cfg is widened to include `target_env = "ohos"`
and the tag pin is bumped. Until then a full default route through the TUN
would loop the proxy server's own traffic. The cross-platform
`HostResolver` hook IS installed on ohos, so DNS lookups already stay
in-process. See `../core/src/main/rust/mihomo-ohos-ffi/src/protect.rs`.

## Not yet ported

- Flutter UI: the OpenHarmony Flutter SDK (OpenHarmony-SIG flutter_flutter)
  can host `flutter_module` largely unchanged, but this port ships a native
  ArkUI seed page (toggle + traffic) instead; subscriptions/connections/
  logs/rules screens should follow either via Flutter-ohos or ArkUI reusing
  the engine REST API on 127.0.0.1:9090.
- Profile management (Room database on Android) — on HarmonyOS the config
  is read from `<filesDir>/meow/config.yaml`; put a config there manually
  for now.
- Geodata seeding (`Country.mmdb` / `GeoLite2-ASN.mmdb` next to the
  config) — required only for GEOIP/GEOIP-ASN rules.
