# Repository Guidelines

Guidance for contributors and coding agents working on **meow** — a Clash/mihomo Android VPN client with a Flutter UI, Kotlin VPN service, and Rust FFI engine.

## Project Structure & Module Organization

Three-layer stack: **Flutter UI → Kotlin VPN Service → Rust FFI**.

- `core/` — Kotlin Android library + Rust FFI.
  - `core/src/main/java/io/github/madeye/meow/` — VPN service, AIDL, Room DB, JNI bridge.
  - `core/src/main/rust/mihomo-android-ffi/` — Rust crate (`libmihomo_android_ffi.so`): JNI entry points, tun2socks, lwip netstack, socket-protect shim.
- `mobile/` — Android app module (manifest, launcher icons, release signing).
- `flutter_module/` — Flutter add-to-app UI (Dart screens, services, i18n).
- `buildSrc/` — shared Gradle logic; `fastlane/` — Play Store metadata.
- `test-e2e.sh`, `test-e2e-http.sh` — E2E harness; `.github/workflows/` — `lint`, `tests`, `e2e`, `release`.
- `CLAUDE.md` holds the full architecture deep-dive.

## Build, Test, and Development Commands

**JDK 17 is required** (newer JDKs break the Kotlin compiler); set `JAVA_HOME` explicitly. One-time: `cd flutter_module && flutter pub get && cd ..`.

```bash
./gradlew :mobile:assembleDebug -PTARGET_ABI=arm64 -PCARGO_PROFILE=release  # debug APK
./gradlew :core:cargoBuildArm64 -PCARGO_PROFILE=release                     # Rust-only iteration
./gradlew clean                                                             # incl. cargo clean
SKIP_EMULATOR_BOOT=true ./test-e2e.sh                                       # E2E
```

## Coding Style & Naming Conventions

Run the matching lint before considering a change complete; fix all errors before committing.

```bash
./gradlew :mobile:lintDebug -PTARGET_ABI=arm64 -PCARGO_PROFILE=release                                  # Kotlin
cd core/src/main/rust/mihomo-android-ffi && cargo clippy -- -D warnings && cargo fmt --check && cd -      # Rust
cd flutter_module && flutter analyze && cd -                                                            # Dart
```

Keep existing formatting; no license headers or inline comments unless asked. JNI symbols stay under `io.github.madeye.meow` (`namespace`), regardless of the `applicationId`.

## Testing Guidelines

No host-side unit tests yet; verification is E2E-driven. `test-e2e.sh` runs 5 checks (tun0, DNS, two TCP probes, HTTP 204) using `ssserver` + an Android emulator, configurable via `EMULATOR`, `ADB`, `AVD`, `APK`, `SSSERVER`, `SKIP_EMULATOR_BOOT`. Add CI coverage in `.github/workflows/`, not ad-hoc scripts.

## Commit & Pull Request Guidelines

Use **Conventional Commits** with a scope, matching the Git history:

- `feat(ui): adopt meow-ios theme design and peeking-cat app icon (#47)`
- `chore(deps): bump meow-rs engine v0.16.0 -> v0.18.0 (#46)`
- Also `docs:`, `fix:`, `refactor(ui):`, `test(e2e):`; version bumps use `Bump version to X.Y.Z (versionCode N) (#NN)`.

Reference the issue/PR number in parentheses. When bumping the engine, update the pinned git **tag** on every `meow-*` line in `Cargo.toml`. PRs should describe the change, link the issue, and include screenshots for UI work.

## Agent-Specific Instructions

Do not commit `local.properties`, `google-services.json`, or `*.jks` (all gitignored). Release builds with a placeholder `google-services.json` need Crashlytics upload disabled. Keep changes minimal and scoped.

- **PRs require explicit user approval**: never create, force-push, or edit a pull request without the user's prior consent. Build/compile and local verification are fine; PR submission is not.
