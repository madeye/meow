#!/usr/bin/env bash
# Build libmihomo_ohos_ffi.so for OpenHarmony/HarmonyOS devices and drop it
# into entry/libs/<abi>/ where the hvigor native build (CMakeLists.txt)
# links libmeow.so against it.
#
# Requires the OpenHarmony native SDK (the "native" folder of the OHOS SDK,
# shipped with DevEco Studio or the ohos-sdk command-line package). Point
# OHOS_NDK_HOME at the directory that CONTAINS the `native/` folder, e.g.
#   export OHOS_NDK_HOME=~/Library/OpenHarmony/Sdk/12   (DevEco layout)
#
# Usage: harmony/scripts/build-rust-ohos.sh [--profile release|debug] [--abi arm64-v8a|x86_64]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CRATE_DIR="$REPO_ROOT/core/src/main/rust/mihomo-ohos-ffi"
LIBS_DIR="$REPO_ROOT/harmony/entry/libs"

PROFILE=release
ABI=arm64-v8a
while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --abi) ABI="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

case "$ABI" in
  arm64-v8a) TARGET=aarch64-unknown-linux-ohos; OHOS_CLANG_PREFIX=aarch64-unknown-linux-ohos ;;
  x86_64) TARGET=x86_64-unknown-linux-ohos; OHOS_CLANG_PREFIX=x86_64-unknown-linux-ohos ;;
  *) echo "unsupported abi: $ABI" >&2; exit 2 ;;
esac

: "${OHOS_NDK_HOME:?set OHOS_NDK_HOME to the OHOS SDK dir containing native/ (see header)}"
NATIVE="$OHOS_NDK_HOME/native"
LLVM_BIN="$NATIVE/llvm/bin"
[[ -x "$LLVM_BIN/clang" ]] || { echo "no clang under $LLVM_BIN — wrong OHOS_NDK_HOME?" >&2; exit 1; }

rustup target add "$TARGET" >/dev/null

# The OHOS SDK ships per-target clang wrapper scripts that pin --target and
# --sysroot; fall back to raw clang + explicit flags if they're absent.
CC_WRAPPER="$LLVM_BIN/$OHOS_CLANG_PREFIX-clang"
if [[ ! -x "$CC_WRAPPER" ]]; then
  CC_WRAPPER="$LLVM_BIN/clang --target=$TARGET --sysroot=$NATIVE/sysroot"
fi

env_name() { echo "$1" | tr '[:lower:]-' '[:upper:]_'; }

export "CC_$(echo "$TARGET" | tr '-' '_')"="$CC_WRAPPER"
export "CXX_$(echo "$TARGET" | tr '-' '_')"="$CC_WRAPPER"
export "AR_$(echo "$TARGET" | tr '-' '_')"="$LLVM_BIN/llvm-ar"
export "CARGO_TARGET_$(env_name "$TARGET")_LINKER"="$LLVM_BIN/$OHOS_CLANG_PREFIX-clang"
export BINDGEN_EXTRA_CLANG_ARGS_$(echo "$TARGET" | tr '-' '_')="--target=$TARGET --sysroot=$NATIVE/sysroot"

CARGO_FLAGS=(--target "$TARGET")
[[ "$PROFILE" == release ]] && CARGO_FLAGS+=(--release)

echo ">> cargo build ${CARGO_FLAGS[*]} (in $CRATE_DIR)"
(cd "$CRATE_DIR" && cargo build "${CARGO_FLAGS[@]}")

OUT="$CRATE_DIR/target/$TARGET/$PROFILE/libmihomo_ohos_ffi.so"
[[ -f "$OUT" ]] || { echo "build produced no $OUT" >&2; exit 1; }
mkdir -p "$LIBS_DIR/$ABI"
cp "$OUT" "$LIBS_DIR/$ABI/"
echo ">> installed $LIBS_DIR/$ABI/libmihomo_ohos_ffi.so"
