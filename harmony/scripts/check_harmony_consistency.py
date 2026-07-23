#!/usr/bin/env python3
"""Static consistency checks for the HarmonyOS NEXT app scaffold.

The FFI surface crosses four hand-maintained layers that no single compiler
sees end-to-end:

    Rust C ABI (mihomo-ohos-ffi/src/lib.rs)
      = C header (harmony/entry/src/main/cpp/mihomo_ohos_ffi.h)
      = NAPI exports (napi_init.cpp)
      = ArkTS declarations (types/libmeow/index.d.ts)
      ⊇ ArkTS call sites (entry/src/main/ets/**.ets)

plus the app manifest's resource references and the HarmonyOS NEXT
conventions (@kit imports, NEXT SDK pinning). Run by ../../test-e2e-ohos.sh;
exits non-zero on any mismatch.
"""

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
HARMONY = REPO / "harmony"
ENTRY = HARMONY / "entry" / "src" / "main"
RUST_LIB = REPO / "core/src/main/rust/mihomo-ohos-ffi/src/lib.rs"
C_HEADER = ENTRY / "cpp" / "mihomo_ohos_ffi.h"
NAPI_CPP = ENTRY / "cpp" / "napi_init.cpp"
DTS = ENTRY / "cpp" / "types" / "libmeow" / "index.d.ts"

failures: list[str] = []


def fail(msg: str) -> None:
    failures.append(msg)


def compare(name: str, left_label: str, left: set, right_label: str, right: set) -> None:
    for sym in sorted(left - right):
        fail(f"{name}: {sym!r} in {left_label} but missing from {right_label}")
    for sym in sorted(right - left):
        fail(f"{name}: {sym!r} in {right_label} but missing from {left_label}")


# ---------------------------------------------------------------- A. C ABI
rust_fns = set(
    re.findall(
        r'#\[no_mangle\]\s*pub extern "C" fn\s+(meow_\w+)', RUST_LIB.read_text()
    )
)
header_fns = set(
    re.findall(r"^\s*[\w][\w\s\*]*?\b(meow_\w+)\s*\(", C_HEADER.read_text(), re.M)
)
if not rust_fns:
    fail(f"no extern C fns found in {RUST_LIB} — regex or file drift")
compare("C ABI", "Rust lib.rs", rust_fns, "mihomo_ohos_ffi.h", header_fns)

# ---------------------------------------------------- B. NAPI exports ↔ d.ts
napi_exports = set(re.findall(r'\{"(\w+)",\s*nullptr,\s*\w+', NAPI_CPP.read_text()))
dts_exports = set(re.findall(r"^export const (\w+):", DTS.read_text(), re.M))
if not napi_exports:
    fail(f"no NAPI property descriptors found in {NAPI_CPP}")
compare("NAPI surface", "napi_init.cpp", napi_exports, "index.d.ts", dts_exports)

# ----------------------------------------------- C. ArkTS call sites ⊆ d.ts
ets_files = sorted((ENTRY / "ets").rglob("*.ets"))
for ets in ets_files:
    for call in set(re.findall(r"\bmeow\.(\w+)\s*\(", ets.read_text())):
        if call not in dts_exports:
            fail(f"{ets.relative_to(REPO)}: meow.{call}() not declared in index.d.ts")

# ------------------------------------- D. manifest resource refs must exist
def json5_load(path: Path):
    text = re.sub(r"//[^\n]*", "", path.read_text())
    text = re.sub(r",\s*([}\]])", r"\1", text)
    return json.loads(text)


module = json5_load(ENTRY / "module.json5")["module"]
res = ENTRY / "resources" / "base"
strings = {s["name"] for s in json5_load(res / "element" / "string.json")["string"]}
colors = {c["name"] for c in json5_load(res / "element" / "color.json")["color"]}

manifest_text = (ENTRY / "module.json5").read_text()
for kind, name in re.findall(r"\$(media|string|color|profile):(\w+)", manifest_text):
    if kind == "media" and not list((res / "media").glob(f"{name}.*")):
        fail(f"module.json5: $media:{name} has no file in resources/base/media/")
    elif kind == "string" and name not in strings:
        fail(f"module.json5: $string:{name} missing from element/string.json")
    elif kind == "color" and name not in colors:
        fail(f"module.json5: $color:{name} missing from element/color.json")
    elif kind == "profile" and not (res / "profile" / f"{name}.json").exists():
        fail(f"module.json5: $profile:{name} missing from resources/base/profile/")

for ability in module.get("abilities", []) + module.get("extensionAbilities", []):
    src = ability.get("srcEntry", "")
    if src and not (ENTRY / src.lstrip("./")).exists():
        fail(f"module.json5: srcEntry {src} does not exist")

pages_profile = re.search(r"\$profile:(\w+)", module.get("pages", ""))
if pages_profile:
    for page in json5_load(res / "profile" / f"{pages_profile.group(1)}.json")["src"]:
        if not (ENTRY / "ets" / f"{page}.ets").exists():
            fail(f"main_pages: {page} has no ets/{page}.ets")

app = json5_load(HARMONY / "AppScope" / "app.json5")["app"]
app_res = HARMONY / "AppScope" / "resources" / "base"
for kind, name in re.findall(r"\$(media|string):(\w+)", json.dumps(app)):
    if kind == "media" and not list((app_res / "media").glob(f"{name}.*")):
        fail(f"app.json5: $media:{name} has no file in AppScope media/")
    if kind == "string":
        app_strings = {
            s["name"] for s in json5_load(app_res / "element" / "string.json")["string"]
        }
        if name not in app_strings:
            fail(f"app.json5: $string:{name} missing from AppScope string.json")

# --------------------------------------------- E. HarmonyOS NEXT conventions
for ets in ets_files:
    text = ets.read_text()
    for legacy in re.findall(r"from '(@ohos\.[\w.]+)'", text):
        fail(
            f"{ets.relative_to(REPO)}: legacy import {legacy} — use the "
            f"HarmonyOS NEXT @kit.* form"
        )

profile = json5_load(HARMONY / "build-profile.json5")
product = profile["app"]["products"][0]
if product.get("runtimeOS") != "HarmonyOS":
    fail("build-profile.json5: products[0].runtimeOS must be 'HarmonyOS' (NEXT)")
for key in ("compatibleSdkVersion", "targetSdkVersion"):
    if "12" not in str(product.get(key, "")):
        fail(f"build-profile.json5: products[0].{key} must pin the NEXT API 12 SDK")

# -------------------------------------------------------------------- report
if failures:
    print(f"FAIL: {len(failures)} harmony consistency error(s):")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)

print(
    f"OK: C ABI {len(rust_fns)} fns consistent across Rust/C header; "
    f"NAPI surface {len(napi_exports)} exports consistent with d.ts; "
    f"{len(ets_files)} ArkTS files use NEXT @kit imports; manifest resources resolve"
)
