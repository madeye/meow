#!/bin/sh
# C-compiler shim for `cargo check --target aarch64-unknown-linux-ohos` on
# hosts without the OpenHarmony SDK: OpenHarmony's libc is musl, so zig's
# aarch64-linux-musl toolchain is a faithful stand-in for compiling the C
# build-script outputs (lwip, ring, mimalloc, aws-lc) during type-checking.
# Real device builds must use the OHOS SDK clang (see build-rust-ohos.sh).
#
# Build systems (cc crate, cmake) append `--target=aarch64-unknown-linux-ohos`
# / `-target <triple>`, which zig's clang does not know — strip those and pin
# the musl triple instead.
set -e
args=""
skip_next=0
for a in "$@"; do
  if [ "$skip_next" = 1 ]; then skip_next=0; continue; fi
  case "$a" in
    --target=*|-U_FORTIFY_SOURCE|-Wp,*) continue ;;
    -target) skip_next=1; continue ;;
  esac
  args="$args \"$a\""
done
eval "set -- $args"
exec zig cc -target aarch64-linux-musl "$@"
