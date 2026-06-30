#!/usr/bin/env bash
#
# End-to-end test: meow-android routing all traffic through a local HTTP proxy
# on the host (a `type: http` outbound), and proof that app traffic egresses
# through it (the proxy logs every CONNECT/forward request).
#
# Companion to test-e2e.sh (which uses a Shadowsocks server). Reuses the same
# emulator / APK-install / Room-DB-injection / VPN-consent flow.
#
# Configurable via env: EMULATOR, ADB, AVD, APK, PROXY_PORT, EMU_SKIN,
# SKIP_EMULATOR_BOOT.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EMULATOR="${EMULATOR:-/Volumes/Data/workspace/android/emulator/emulator}"
ADB="${ADB:-/Volumes/Data/workspace/android/platform-tools/adb}"
AVD="${AVD:-meow_api35}"
APK="${APK:-$SCRIPT_DIR/mobile/build/outputs/apk/debug/mobile-debug.apk}"
PKG="io.github.madeye.meow"

PROXY_PY="$SCRIPT_DIR/http_proxy.py"
PROXY_PORT="${PROXY_PORT:-8889}"
PROXY_HOST_FROM_EMU="10.0.2.2"   # host loopback as seen from the emulator
PROXY_LOG="$SCRIPT_DIR/e2e-http-proxy.log"
LOGCAT_FILE="$SCRIPT_DIR/e2e-logcat.log"

# Render at a real phone resolution. The meow_api35 AVD ships a WVGA800 skin
# that forces the panel down to 480x800; override it to the native 1080x2400.
EMU_SKIN="${EMU_SKIN:-1080x2400}"

PROXY_PID=""; EMU_PID=""; LOGCAT_PID=""

cleanup() {
    echo ""; echo "=== Cleanup ==="
    for v in LOGCAT_PID PROXY_PID EMU_PID; do
        pid="${!v}"
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            echo "Killing $v ($pid)"; kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true
        fi
    done
    rm -rf /tmp/test-sub-http /tmp/mihomo-http.db /tmp/ui-http.xml
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
info() { echo "--- $*"; }

wait_for_boot() {
    info "Waiting for emulator to boot..."
    "$ADB" wait-for-device
    local n=0
    while [[ $n -lt 180 ]]; do
        local val
        val=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')
        [[ "$val" == "1" ]] && { info "Emulator booted."; return 0; }
        sleep 2; n=$((n + 2))
    done
    fail "Emulator did not boot within 180s"
}

screenshot() {
    "$ADB" shell screencap -p "/sdcard/screen_$1.png" 2>/dev/null || true
    "$ADB" pull "/sdcard/screen_$1.png" "$SCRIPT_DIR/screen_$1.png" 2>/dev/null || true
}

# Step 1: prerequisites
info "Step 1: prerequisites"
[[ -f "$APK" ]] || fail "APK not found at $APK (build with: ./gradlew :mobile:assembleDebug -PTARGET_ABI=arm64 -PCARGO_PROFILE=release)"
[[ -f "$PROXY_PY" ]] || fail "proxy script missing at $PROXY_PY"
command -v python3 >/dev/null || fail "python3 missing"
command -v sqlite3 >/dev/null || fail "sqlite3 missing"
info "OK (APK: $(basename "$APK"))"

# Step 2: start host HTTP proxy (bind all interfaces so emulator 10.0.2.2 reaches it)
info "Step 2: starting host HTTP proxy on 0.0.0.0:$PROXY_PORT"
: > "$PROXY_LOG"
python3 "$PROXY_PY" 0.0.0.0 "$PROXY_PORT" > "$PROXY_LOG" 2>&1 &
PROXY_PID=$!
sleep 1
kill -0 "$PROXY_PID" 2>/dev/null || { cat "$PROXY_LOG"; fail "proxy failed to start"; }
grep -q "PROXY listening" "$PROXY_LOG" || { cat "$PROXY_LOG"; fail "proxy not listening"; }
info "proxy running (PID $PROXY_PID)"

# Step 3: subscription config — route everything through the HTTP proxy
info "Step 3: writing subscription config (type: http)"
mkdir -p /tmp/test-sub-http
cat > /tmp/test-sub-http/config.yaml <<SUBEOF
mixed-port: 7890
mode: rule
log-level: info
allow-lan: false
dns:
  enable: true
  listen: 127.0.0.1:1053
  nameserver:
    - 114.114.114.114
proxies:
  - name: test-http
    type: http
    server: $PROXY_HOST_FROM_EMU
    port: $PROXY_PORT
proxy-groups:
  - name: Proxy
    type: select
    proxies:
      - test-http
rules:
  - MATCH,test-http
SUBEOF

# Step 4: boot emulator (unless one is already attached / SKIP_EMULATOR_BOOT)
if [[ "${SKIP_EMULATOR_BOOT:-}" == "true" ]] || "$ADB" get-state 2>/dev/null | grep -q device; then
    info "Step 4: using already-attached emulator"
    "$ADB" wait-for-device
else
    info "Step 4: booting emulator ($AVD) at skin $EMU_SKIN"
    "$EMULATOR" -avd "$AVD" -skin "$EMU_SKIN" -no-snapshot -no-audio -gpu auto &
    # shellcheck disable=SC2034 # read indirectly in cleanup() via ${!v}
    EMU_PID=$!
    wait_for_boot
    sleep 5
    "$ADB" shell input keyevent KEYCODE_HOME; sleep 2
fi
"$ADB" shell settings put global window_animation_scale 0 || true
"$ADB" shell settings put global transition_animation_scale 0 || true
"$ADB" shell settings put global animator_duration_scale 0 || true

info "starting logcat -> $LOGCAT_FILE"
"$ADB" logcat -c 2>/dev/null || true
"$ADB" logcat -v threadtime > "$LOGCAT_FILE" 2>&1 &
LOGCAT_PID=$!

# Step 5: install APK
info "Step 5: installing APK"
"$ADB" uninstall "$PKG" 2>/dev/null || true
"$ADB" install -g "$APK" || fail "APK install failed"
info "installed"

# Step 6: inject subscription DB (Room schema v4 — see
# core/schemas/io.github.madeye.meow.database.PrivateDatabase/4.json)
info "Step 6: injecting subscription DB"
"$ADB" shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 8
screenshot "01_init"
"$ADB" shell am force-stop "$PKG"; sleep 2

SUB_YAML=$(cat /tmp/test-sub-http/config.yaml)
rm -f /tmp/mihomo-http.db
sqlite3 /tmp/mihomo-http.db <<DBEOF
PRAGMA user_version = 4;
CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT);
INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42,'0ad45cbdd12706e49d09c67996a18e92');
CREATE TABLE IF NOT EXISTS clash_profile (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    name TEXT NOT NULL, url TEXT NOT NULL, yaml_content TEXT NOT NULL,
    selected INTEGER NOT NULL, last_updated INTEGER NOT NULL,
    tx INTEGER NOT NULL, rx INTEGER NOT NULL,
    selected_proxy TEXT NOT NULL, yaml_backup TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS daily_traffic (
    date TEXT NOT NULL, tx INTEGER NOT NULL, rx INTEGER NOT NULL, PRIMARY KEY(date)
);
INSERT INTO clash_profile (name, url, yaml_content, selected, last_updated, tx, rx, selected_proxy, yaml_backup)
VALUES ('HTTP Proxy Test', 'http://$PROXY_HOST_FROM_EMU:9/config.yaml', '$(echo "$SUB_YAML" | sed "s/'/''/g")', 1, $(date +%s), 0, 0, '', '');
DBEOF

"$ADB" push /tmp/mihomo-http.db /data/local/tmp/mihomo.db >/dev/null
"$ADB" shell "cat /data/local/tmp/mihomo.db | run-as $PKG sh -c 'cat > databases/mihomo.db'"
"$ADB" shell "run-as $PKG rm -f databases/mihomo.db-wal databases/mihomo.db-shm"
"$ADB" shell rm -f /data/local/tmp/mihomo.db
info "DB injected"

# Step 7: enable VPN + accept consent
info "Step 7: enabling VPN"
"$ADB" shell am start -W -n "$PKG/.MainActivity" --ez auto_connect true >/dev/null
sleep 6
screenshot "02_launched"

info "  handling VPN consent dialog"
for i in $(seq 1 20); do
    ACT=$("$ADB" shell dumpsys activity activities 2>/dev/null || true)
    if echo "$ACT" | grep -qi vpndialogs; then
        screenshot "03_vpn_dialog"; sleep 1
        "$ADB" shell uiautomator dump /sdcard/ui.xml 2>/dev/null || true
        "$ADB" pull /sdcard/ui.xml /tmp/ui-http.xml 2>/dev/null || true
        line=$(tr '>' '\n' < /tmp/ui-http.xml | grep -F 'resource-id="android:id/button1"' | head -1 || true)
        [[ -z "$line" ]] && line=$(tr '>' '\n' < /tmp/ui-http.xml | grep 'package="com.android.vpndialogs"' | grep -iE 'text="(OK|Allow|确定|允许)"' | head -1 || true)
        if [[ -n "$line" ]]; then
            b=$(echo "$line" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"')
            nums=$(echo "$b" | grep -o '[0-9]*')
            x1=$(sed -n 1p <<<"$nums"); y1=$(sed -n 2p <<<"$nums"); x2=$(sed -n 3p <<<"$nums"); y2=$(sed -n 4p <<<"$nums")
            "$ADB" shell input tap $(((x1+x2)/2)) $(((y1+y2)/2))
            info "  tapped consent OK"; sleep 3
        fi
        screenshot "04_after_consent"
        break
    fi
    sleep 1
done

# Step 8: connectivity tests (through the tunnel) + egress verification
info "Step 8: connectivity + proxy-egress verification"
sleep 8
screenshot "05_status"

PASS=0; NAMES=(); RES=(); DET=()
add() { NAMES+=("$1"); RES+=("$2"); DET+=("$3"); [[ "$2" == PASS ]] && PASS=$((PASS+1)); }

TUN=$("$ADB" shell ip addr show tun0 2>&1 || true)
echo "$TUN" | grep -q "inet " && add "tun0 up" PASS "tun0 present" || add "tun0 up" FAIL "no tun0"

NC1=$("$ADB" shell "echo '' | nc -w 8 1.1.1.1 80 >/dev/null 2>&1; echo \$?" | tr -d '\r' | tail -1)
[[ "$NC1" == 0 ]] && add "TCP 1.1.1.1:80" PASS "connected" || add "TCP 1.1.1.1:80" FAIL "exit=$NC1"

NC2=$("$ADB" shell "echo '' | nc -w 8 8.8.8.8 443 >/dev/null 2>&1; echo \$?" | tr -d '\r' | tail -1)
[[ "$NC2" == 0 ]] && add "TCP 8.8.8.8:443" PASS "connected" || add "TCP 8.8.8.8:443" FAIL "exit=$NC2"

HTTP_OUT=$("$ADB" shell "{ printf 'GET /generate_204 HTTP/1.0\r\nHost: connectivitycheck.gstatic.com\r\nConnection: close\r\n\r\n'; sleep 6; } | nc connectivitycheck.gstatic.com 80 2>/dev/null | head -1" | tr -d '\r' || true)
echo "$HTTP_OUT" | grep -qE "HTTP/.* (200|204|301|302)" && add "HTTP generate_204" PASS "$(echo "$HTTP_OUT" | grep -oE '[0-9]{3}' | head -1)" || add "HTTP generate_204" FAIL "no response"

# Stop logcat before reading proxy log
[[ -n "$LOGCAT_PID" ]] && kill "$LOGCAT_PID" 2>/dev/null || true; LOGCAT_PID=""

# Proof of egress: the host proxy must have logged CONNECT/forward requests
CONNECTS=$(grep -cE "CONNECT |GET http" "$PROXY_LOG" || true)
if [[ "${CONNECTS:-0}" -gt 0 ]]; then
    add "egress via HTTP proxy" PASS "$CONNECTS proxied reqs"
else
    add "egress via HTTP proxy" FAIL "0 proxied reqs"
fi

TOTAL=${#NAMES[@]}
echo ""
printf '+-----+------------------------+--------+----------------------+\n'
printf '| %-3s | %-22s | %-6s | %-20s |\n' "#" "Test" "Result" "Details"
printf '+-----+------------------------+--------+----------------------+\n'
for i in $(seq 0 $((TOTAL-1))); do
    printf '| %-3s | %-22s | %-6s | %-20s |\n' "$((i+1))" "${NAMES[$i]}" "${RES[$i]}" "${DET[$i]}"
done
printf '+-----+------------------------+--------+----------------------+\n'
printf '| %d/%d passed\n' "$PASS" "$TOTAL"

echo ""
info "Host proxy log (egress proof):"
grep -E "CONNECT |GET http|! " "$PROXY_LOG" | head -25 || true

if [[ "$PASS" -eq "$TOTAL" ]]; then
    echo "  ALL TESTS PASSED"; exit 0
else
    echo "  SOME TESTS FAILED"
    info "Relevant logcat:"; grep -iE "mihomo|meow|vpn|tun|http" "$LOGCAT_FILE" | tail -40 || true
    exit 1
fi
