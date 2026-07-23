#!/usr/bin/env bash
# End-to-end verification of the HarmonyOS (OpenHarmony) support.
#
# Stages (later stages skip gracefully when their toolchain is absent, but
# stage failures always fail the script):
#
#   1. host-e2e   — REQUIRED. Runs the host end-to-end tests that drive the
#                   real engine + lwip netstack through a fake TUN fd:
#                     * mihomo-ffi-core tests/e2e_tun.rs — raw-packet DNS
#                       fake-IP round-trip + a full smoltcp TCP echo flow
#                       through netstack → tunnel → DIRECT.
#                     * mihomo-ohos-ffi tests/c_abi_e2e.rs — the exact C ABI
#                       the ArkTS/NAPI layer calls: lifecycle, validation,
#                       protect plumbing, DNS-over-TUN round-trip.
#   1b. harmony-static — REQUIRED. Cross-checks the four hand-maintained
#                   FFI layers (Rust C ABI = C header = NAPI exports =
#                   ArkTS d.ts ⊇ ArkTS call sites), the module manifest's
#                   resource references, and the HarmonyOS NEXT conventions
#                   (@kit imports, API 12 SDK pinning).
#   2. ohos-check — REQUIRED (self-contained). Type-checks the full native
#                   stack for aarch64-unknown-linux-ohos. Uses the OHOS SDK
#                   when OHOS_NDK_HOME is set; otherwise C build-script code
#                   (lwip, aws-lc, mimalloc) is compiled against zig's
#                   aarch64-linux-musl toolchain (OpenHarmony's libc is
#                   musl), which needs no SDK. Requires `zig` on PATH in
#                   that fallback mode.
#   3. ohos-build — OPTIONAL. Full cdylib build + install into
#                   harmony/entry/libs/ when OHOS_NDK_HOME is set.
#   4. device     — OPTIONAL. When `hdc` sees a connected device/emulator
#                   AND the HAP has been built (harmony/entry/build/...),
#                   installs and launches it. Requires DevEco/hvigor
#                   artifacts; skipped otherwise.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
CORE_CRATE="$ROOT/core/src/main/rust/mihomo-ffi-core"
OHOS_CRATE="$ROOT/core/src/main/rust/mihomo-ohos-ffi"
PASS=()
SKIP=()

note() { printf '\n==== %s ====\n' "$*"; }

# ---------------------------------------------------------------- 1. host-e2e
note "stage 1: host end-to-end tests (engine + tun2socks over fake TUN)"
(cd "$CORE_CRATE" && cargo test --test e2e_tun)
(cd "$OHOS_CRATE" && cargo test --test c_abi_e2e)
PASS+=("host-e2e")

# ----------------------------------------------------------- 1b. harmony-static
note "stage 1b: harmony app static consistency (FFI layers, manifest, NEXT conventions)"
python3 "$ROOT/harmony/scripts/check_harmony_consistency.py"
PASS+=("harmony-static")

# --------------------------------------------------------------- 2. ohos-check
note "stage 2: type-check for aarch64-unknown-linux-ohos"
TARGET=aarch64-unknown-linux-ohos
TARGET_U="$(echo "$TARGET" | tr '-' '_')"
rustup target add "$TARGET" >/dev/null 2>&1 || true

if [[ -n "${OHOS_NDK_HOME:-}" && -x "$OHOS_NDK_HOME/native/llvm/bin/clang" ]]; then
  LLVM_BIN="$OHOS_NDK_HOME/native/llvm/bin"
  export "CC_${TARGET_U}"="$LLVM_BIN/aarch64-unknown-linux-ohos-clang"
  export "CXX_${TARGET_U}"="$LLVM_BIN/aarch64-unknown-linux-ohos-clang++"
  export "AR_${TARGET_U}"="$LLVM_BIN/llvm-ar"
  export "BINDGEN_EXTRA_CLANG_ARGS_${TARGET_U}"="--target=$TARGET --sysroot=$OHOS_NDK_HOME/native/sysroot"
  echo "using OHOS SDK toolchain at $LLVM_BIN"
else
  command -v zig >/dev/null || { echo "FAIL: neither OHOS_NDK_HOME nor zig available for stage 2" >&2; exit 1; }
  ZIG_LIB="$(zig env | sed -n 's/^ *\.lib_dir = "\(.*\)",*/\1/p')"
  ZIG_INC="$ZIG_LIB/libc/include"
  [[ -d "$ZIG_INC/aarch64-linux-musl" ]] || { echo "FAIL: zig musl headers not found under $ZIG_INC" >&2; exit 1; }
  export "CC_${TARGET_U}"="$ROOT/harmony/scripts/zig-cc-aarch64-ohos.sh"
  export "CXX_${TARGET_U}"="$ROOT/harmony/scripts/zig-cc-aarch64-ohos.sh"
  export "AR_${TARGET_U}"="$ROOT/harmony/scripts/zig-ar.sh"
  export "BINDGEN_EXTRA_CLANG_ARGS_${TARGET_U}"="-isystem $ZIG_INC/aarch64-linux-musl -isystem $ZIG_INC/generic-musl -isystem $ZIG_INC/any-linux-any"
  echo "using zig musl stand-in toolchain (no OHOS SDK found)"
fi
(cd "$OHOS_CRATE" && cargo check --target "$TARGET")
PASS+=("ohos-check")

# --------------------------------------------------------------- 3. ohos-build
if [[ -n "${OHOS_NDK_HOME:-}" && -x "$OHOS_NDK_HOME/native/llvm/bin/clang" ]]; then
  note "stage 3: full cdylib build via OHOS SDK"
  "$ROOT/harmony/scripts/build-rust-ohos.sh" --profile release --abi arm64-v8a
  PASS+=("ohos-build")
else
  SKIP+=("ohos-build (OHOS_NDK_HOME not set)")
fi

# ------------------------------------------------------------------ 4. device
HAP="$(ls "$ROOT"/harmony/entry/build/default/outputs/default/*.hap 2>/dev/null | head -1 || true)"
if command -v hdc >/dev/null && hdc list targets 2>/dev/null | grep -vq '^\[Empty\]' && [[ -n "$HAP" ]]; then
  note "stage 4: install + launch on device"
  hdc install -r "$HAP"
  hdc shell aa start -a EntryAbility -b io.github.madeye.meow
  PASS+=("device")
else
  SKIP+=("device (needs hdc + connected device + built HAP)")
fi

note "summary"
for s in "${PASS[@]}"; do echo "PASS: $s"; done
for s in "${SKIP[@]:-}"; do [[ -n "$s" ]] && echo "SKIP: $s"; done
echo "OK"
