#!/usr/bin/env bash
#
# End-to-end test: mihomo-android on Android emulator
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EMULATOR="${EMULATOR:-/Volumes/Data/workspace/android/emulator/emulator}"
ADB="${ADB:-/Volumes/Data/workspace/android/platform-tools/adb}"
AVD="${AVD:-meow_api35}"
APK="${APK:-$SCRIPT_DIR/mobile/build/outputs/apk/debug/mobile-arm64-v8a-debug.apk}"
SSSERVER="${SSSERVER:-ssserver}"
V2RAY_PLUGIN="${V2RAY_PLUGIN:-v2ray-plugin}"
PKG="io.github.madeye.meow"

SS_ADDR="0.0.0.0:8388"
SS_PASSWORD="testpassword123"
SS_METHOD="aes-256-gcm"
SS_HOST_FROM_EMU="10.0.2.2"
SS_PORT=8388
SUB_PORT=8080

SSSERVER_PID=""
HTTPD_PID=""
EMU_PID=""
LOGCAT_PID=""
LOGCAT_FILE="$SCRIPT_DIR/e2e-logcat.log"

cleanup() {
    echo ""
    echo "=== Cleanup ==="
    for pid_var in LOGCAT_PID SSSERVER_PID HTTPD_PID EMU_PID; do
        pid="${!pid_var}"
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            echo "Killing $pid_var (PID $pid)"
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
        fi
    done
    rm -rf /tmp/test-sub
    echo "Cleanup done."
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
info() { echo "--- $*"; }

ensure_emulator() {
    # Check emulator process is alive
    if [[ -n "$EMU_PID" ]] && ! kill -0 "$EMU_PID" 2>/dev/null; then
        fail "Emulator process died (PID $EMU_PID)"
    fi
    # Check adb sees the device
    local state
    if command -v timeout &>/dev/null; then
        state=$(timeout 5 "$ADB" get-state 2>&1 || echo "timeout")
    else
        state=$("$ADB" get-state 2>&1 || echo "timeout")
    fi
    if [[ "$state" != "device" ]]; then
        fail "Emulator not responding (adb state: $state)"
    fi
}

wait_for_boot() {
    info "Waiting for emulator to boot..."
    "$ADB" wait-for-device
    local n=0
    while [[ $n -lt 120 ]]; do
        # Check if emulator process is still alive
        if ! "$ADB" get-state 2>/dev/null | grep -q "device"; then
            if [[ $n -gt 10 ]]; then
                fail "Emulator process died during boot"
            fi
        fi
        local val
        val=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')
        if [[ "$val" == "1" ]]; then
            info "Emulator booted."
            return 0
        fi
        sleep 2
        n=$((n + 2))
    done
    fail "Emulator did not boot within 120s"
}

screenshot() {
    local name="$1"
    "$ADB" shell screencap -p /sdcard/screen_${name}.png 2>/dev/null || true
    "$ADB" pull /sdcard/screen_${name}.png "$SCRIPT_DIR/screen_${name}.png" 2>/dev/null || true
    info "  Screenshot saved: screen_${name}.png"
}

# Step 1: Prerequisites
info "Step 1: Verify prerequisites"
command -v "$SSSERVER" &>/dev/null || [[ -f "$SSSERVER" ]] || fail "ssserver not found"
[[ -f "$APK" ]] || fail "APK not found at $APK"
[[ "${SKIP_EMULATOR_BOOT:-}" == "true" ]] || [[ -x "$EMULATOR" ]] || command -v "$EMULATOR" &>/dev/null || fail "Emulator not found"
[[ -x "$ADB" ]] || command -v "$ADB" &>/dev/null || fail "adb not found"
info "All prerequisites OK."

# Step 2: ssserver (plain SS, no plugin — mihomo-rust can't spawn v2ray-plugin on Android)
info "Step 2: Starting ssserver on $SS_ADDR ..."
"$SSSERVER" -s "$SS_ADDR" -k "$SS_PASSWORD" -m "$SS_METHOD" -U &
SSSERVER_PID=$!
sleep 1
kill -0 "$SSSERVER_PID" 2>/dev/null || fail "ssserver failed to start"
info "ssserver running (PID $SSSERVER_PID)"

# Step 3: Subscription HTTP server
info "Step 3: Starting subscription HTTP server on port $SUB_PORT ..."
mkdir -p /tmp/test-sub
cat > /tmp/test-sub/config.yaml <<SUBEOF
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
  - name: test-ss
    type: ss
    server: $SS_HOST_FROM_EMU
    port: $SS_PORT
    cipher: $SS_METHOD
    password: $SS_PASSWORD
proxy-groups:
  - name: Proxy
    type: select
    proxies:
      - test-ss
rules:
  - MATCH,test-ss
SUBEOF

cd /tmp/test-sub && python3 -m http.server "$SUB_PORT" &
HTTPD_PID=$!
cd "$SCRIPT_DIR"
sleep 1
kill -0 "$HTTPD_PID" 2>/dev/null || fail "HTTP server failed to start"
info "Subscription HTTP server running (PID $HTTPD_PID)"

# Step 4: Boot emulator
if [[ "${SKIP_EMULATOR_BOOT:-}" == "true" ]]; then
    info "Step 4: Skipping emulator boot"
    "$ADB" wait-for-device
else
    info "Step 4: Booting emulator ($AVD) ..."
    "$EMULATOR" -avd "$AVD" -no-snapshot-load -no-audio -gpu auto &
    EMU_PID=$!
    info "Emulator PID: $EMU_PID"
    wait_for_boot
    sleep 5
    "$ADB" shell input keyevent KEYCODE_HOME
    sleep 2
fi

"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0

# Start logcat in background for real-time diagnostics
info "Starting background logcat -> $LOGCAT_FILE"
"$ADB" logcat -c 2>/dev/null || true
"$ADB" logcat -v threadtime > "$LOGCAT_FILE" 2>&1 &
LOGCAT_PID=$!

# Step 5: Install APK and tools
ensure_emulator
info "Step 5: Installing debug APK ..."
"$ADB" uninstall "$PKG" 2>/dev/null || true
"$ADB" install -g "$APK" || fail "APK install failed"
info "APK installed."

# No external binaries needed — tests use nc (netcat) built into Android

# Step 6: Configure subscription
info "Step 6: Configuring subscription..."
info "  Launching app to initialize databases..."
"$ADB" shell am start -W -n "$PKG/.MainActivity"
sleep 8
screenshot "01_init"
"$ADB" shell am force-stop "$PKG"
sleep 2

info "  Creating database with subscription profile on host..."
SUB_YAML=$(cat /tmp/test-sub/config.yaml)

# Create a fresh Room database on the host with the correct schema.
# Schema must match core/schemas/io.github.madeye.meow.database.PrivateDatabase/4.json —
# Room refuses to open a pre-packaged DB whose identity hash or column set drifts
# from the generated schema, and the process crashes in Application.onCreate().
rm -f /tmp/mihomo.db /tmp/mihomo.db-wal /tmp/mihomo.db-shm
sqlite3 /tmp/mihomo.db <<DBEOF
PRAGMA user_version = 4;
CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT);
INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42,'0ad45cbdd12706e49d09c67996a18e92');
CREATE TABLE IF NOT EXISTS clash_profile (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    yaml_content TEXT NOT NULL,
    selected INTEGER NOT NULL,
    last_updated INTEGER NOT NULL,
    tx INTEGER NOT NULL,
    rx INTEGER NOT NULL,
    selected_proxy TEXT NOT NULL,
    yaml_backup TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS daily_traffic (
    date TEXT NOT NULL,
    tx INTEGER NOT NULL,
    rx INTEGER NOT NULL,
    PRIMARY KEY(date)
);
INSERT INTO clash_profile (name, url, yaml_content, selected, last_updated, tx, rx, selected_proxy, yaml_backup)
VALUES ('Test Sub', 'http://$SS_HOST_FROM_EMU:$SUB_PORT/config.yaml', '$(echo "$SUB_YAML" | sed "s/'/''/g")', 1, $(date +%s), 0, 0, '', '');
DBEOF

info "  Verifying profile..."
sqlite3 /tmp/mihomo.db "SELECT id, name, selected FROM clash_profile;" | while IFS= read -r line; do
    info "    Profile: $line"
done

# Push to device — use run-as to place it in app's database dir
"$ADB" push /tmp/mihomo.db /data/local/tmp/mihomo.db
"$ADB" shell "cat /data/local/tmp/mihomo.db | run-as $PKG sh -c 'cat > databases/mihomo.db'"
"$ADB" shell "run-as $PKG rm -f databases/mihomo.db-wal databases/mihomo.db-shm"
"$ADB" shell rm -f /data/local/tmp/mihomo.db
info "  Subscription configuration done."

# Step 7: Enable VPN
ensure_emulator
info "Step 7: Enabling VPN..."

# Launch app with auto_connect=true intent extra — triggers VPN start once service reports Stopped
"$ADB" shell am start -W -n "$PKG/.MainActivity" --ez auto_connect true

# Wait for Flutter UI to load (splash screen takes several seconds)
info "  Waiting for Flutter UI to render..."
FLUTTER_READY=false
for i in $(seq 1 30); do
    "$ADB" shell uiautomator dump /sdcard/ui_dump.xml 2>/dev/null || true
    UI_CHECK=$("$ADB" shell cat /sdcard/ui_dump.xml 2>/dev/null || true)
    # Flutter home screen has the app title "Meow" or Chinese equivalent
    if echo "$UI_CHECK" | grep -qiE 'text="Meow"|text=".*断开.*"|text=".*连接.*"'; then
        FLUTTER_READY=true
        info "  Flutter UI loaded (attempt $i)"
        break
    fi
    sleep 1
done
if [[ "$FLUTTER_READY" != "true" ]]; then
    info "  WARNING: Flutter UI not detected after 30s, proceeding anyway"
fi
screenshot "02_app_launched"

# Handle VPN consent dialog
info "  Checking for VPN consent dialog..."
VPN_ACCEPTED=false

# Helper: dump UI and find the VPN consent dialog's positive button.
# Taps it and returns 0. Returns 1 if no suitable button found.
try_dismiss_vpn_dialog() {
    "$ADB" shell uiautomator dump /sdcard/ui_dump.xml 2>/dev/null || true
    "$ADB" pull /sdcard/ui_dump.xml /tmp/ui_dump.xml 2>/dev/null || true
    local ui_xml
    ui_xml=$(cat /tmp/ui_dump.xml 2>/dev/null || true)

    if [[ -z "$ui_xml" ]]; then
        info "  uiautomator dump returned empty"
        return 1
    fi

    info "  UI dump size: ${#ui_xml} bytes"
    cp /tmp/ui_dump.xml "$SCRIPT_DIR/ui_dump_vpn_dialog.xml" 2>/dev/null || true

    # Only match buttons that belong to the VPN dialog (com.android.vpndialogs package)
    # Strategy 1: resource-id android:id/button1 (standard positive button)
    local ok_line
    ok_line=$(echo "$ui_xml" | tr '>' '\n' | grep -F 'resource-id="android:id/button1"' | head -1 || true)

    # Strategy 2: text match for OK/Allow/确定/允许 — only within vpndialogs package
    if [[ -z "$ok_line" ]]; then
        ok_line=$(echo "$ui_xml" | tr '>' '\n' | grep 'package="com.android.vpndialogs"' | grep -iE 'text="(OK|Ok|ok|Allow|ALLOW|Got it|GOT IT|Okay|OKAY|确定|允许)"' | head -1 || true)
    fi

    # No Strategy 3 — tapping arbitrary buttons is dangerous (can hit Flutter nav bar)

    if [[ -n "$ok_line" ]]; then
        local ok_bounds
        ok_bounds=$(echo "$ok_line" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' || true)
        if [[ -n "$ok_bounds" ]]; then
            local nums x1 y1 x2 y2
            nums=$(echo "$ok_bounds" | grep -o '[0-9]*')
            x1=$(echo "$nums" | sed -n '1p'); y1=$(echo "$nums" | sed -n '2p')
            x2=$(echo "$nums" | sed -n '3p'); y2=$(echo "$nums" | sed -n '4p')
            local cx=$(( (x1 + x2) / 2 )) cy=$(( (y1 + y2) / 2 ))
            info "  Tapping VPN dialog button at ($cx, $cy)"
            "$ADB" shell input tap "$cx" "$cy"
            return 0
        fi
    fi
    return 1
}

# Wait for the VPN consent dialog to appear, then dismiss it
for i in $(seq 1 20); do
    ACTIVITIES=$("$ADB" shell dumpsys activity activities 2>/dev/null || true)
    if echo "$ACTIVITIES" | grep -qi "vpndialogs\|com.android.vpndialogs"; then
        info "  VPN consent dialog detected (attempt $i), accepting..."
        screenshot "03_vpn_dialog"
        sleep 1

        # Try to find and tap the OK button (up to 3 attempts for flaky dumps)
        for attempt in 1 2 3; do
            if try_dismiss_vpn_dialog; then
                # Button was tapped — wait for the dialog activity to finish
                sleep 3
                VPN_ACCEPTED=true
                break 2
            fi
            info "  No dialog button found on attempt $attempt, retrying..."
            sleep 1
        done

        # Fallback: blind tap at the "确定" button's known position on 1080x2400
        if [[ "$VPN_ACCEPTED" != "true" ]]; then
            info "  Trying blind tap at known button position..."
            "$ADB" shell input tap 894 1494; sleep 3
            VPN_ACCEPTED=true
        fi

        screenshot "04_after_vpn_accept"
        break
    fi
    sleep 1
done

if [[ "$VPN_ACCEPTED" != "true" ]]; then
    info "  No VPN consent dialog (may already be approved)"
fi

# Step 8: Verify connectivity
ensure_emulator
info "Step 8: Verifying VPN connection..."
sleep 8
screenshot "05_vpn_status"

PASS=0
TOTAL=5
TEST_NAMES=()
TEST_RESULTS=()
TEST_DETAILS=()

ensure_emulator
info "  Test 1: tun0 interface..."
TUN_CHECK=$("$ADB" shell ip addr show tun0 2>&1 || true)
TEST_NAMES+=("TUN interface")
if echo "$TUN_CHECK" | grep -q "inet "; then
    TEST_RESULTS+=("PASS"); TEST_DETAILS+=("tun0 up"); PASS=$((PASS + 1))
else
    TEST_RESULTS+=("FAIL"); TEST_DETAILS+=("tun0 not found")
fi

ensure_emulator
info "  Test 2: DNS resolution..."
DNS_OUT=$("$ADB" shell "ping -c 1 -W 5 google.com 2>&1" || true)
TEST_NAMES+=("DNS resolution")
if echo "$DNS_OUT" | grep -qE "PING google\.com \([0-9]+\.[0-9]+"; then
    TEST_RESULTS+=("PASS"); TEST_DETAILS+=("google.com resolved"); PASS=$((PASS + 1))
else
    TEST_RESULTS+=("FAIL"); TEST_DETAILS+=("ping google.com failed")
fi

ensure_emulator
info "  Test 3: TCP 1.1.1.1:80..."
NC1=$("$ADB" shell "echo '' | nc -w 5 1.1.1.1 80 >/dev/null 2>&1; echo \$?" | tr -d '\r' | tail -1)
TEST_NAMES+=("TCP 1.1.1.1:80")
if [[ "$NC1" == "0" ]]; then
    TEST_RESULTS+=("PASS"); TEST_DETAILS+=("connected"); PASS=$((PASS + 1))
else
    TEST_RESULTS+=("FAIL"); TEST_DETAILS+=("exit=$NC1")
fi

ensure_emulator
info "  Test 4: TCP 8.8.8.8:443..."
NC2=$("$ADB" shell "echo '' | nc -w 5 8.8.8.8 443 >/dev/null 2>&1; echo \$?" | tr -d '\r' | tail -1)
TEST_NAMES+=("TCP 8.8.8.8:443")
if [[ "$NC2" == "0" ]]; then
    TEST_RESULTS+=("PASS"); TEST_DETAILS+=("connected"); PASS=$((PASS + 1))
else
    TEST_RESULTS+=("FAIL"); TEST_DETAILS+=("exit=$NC2")
fi

ensure_emulator
info "  Test 5: HTTP request (Google generate_204)..."
HTTP_OUT=$("$ADB" shell "{ printf 'GET /generate_204 HTTP/1.0\r\nHost: connectivitycheck.gstatic.com\r\nConnection: close\r\n\r\n'; sleep 5; } | nc connectivitycheck.gstatic.com 80 2>/dev/null | head -1" | tr -d '\r' || true)
TEST_NAMES+=("HTTP generate_204")
if echo "$HTTP_OUT" | grep -qE "HTTP/.* (200|204|301|302)"; then
    HTTP_CODE=$(echo "$HTTP_OUT" | grep -oE "[0-9]{3}" | head -1)
    TEST_RESULTS+=("PASS"); TEST_DETAILS+=("HTTP $HTTP_CODE"); PASS=$((PASS + 1))
else
    TEST_RESULTS+=("FAIL"); TEST_DETAILS+=("no valid response")
fi

# Stop logcat collection
if [[ -n "$LOGCAT_PID" ]] && kill -0 "$LOGCAT_PID" 2>/dev/null; then
    kill "$LOGCAT_PID" 2>/dev/null || true
    wait "$LOGCAT_PID" 2>/dev/null || true
    LOGCAT_PID=""
fi

# Print results table
echo ""
echo "+-----+--------------------+--------+------------------------+"
echo "| #   | Test               | Result | Details                |"
echo "+-----+--------------------+--------+------------------------+"
for i in $(seq 0 $((TOTAL - 1))); do
    printf "| %-3s | %-18s | %-6s | %-22s |\n" \
        "$((i + 1))" "${TEST_NAMES[$i]}" "${TEST_RESULTS[$i]}" "${TEST_DETAILS[$i]}"
done
echo "+-----+--------------------+--------+------------------------+"
printf "| %-55s |\n" "$PASS/$TOTAL passed"
echo "+-----+--------------------+--------+------------------------+"

echo ""
if [[ $PASS -eq $TOTAL ]]; then
    info "Relevant logcat (VPN/mihomo):"
    grep -iE "mihomo|meow|vpn|tun" "$LOGCAT_FILE" | tail -30 || true
    echo "  ALL TESTS PASSED"
    echo "  Full logcat: $LOGCAT_FILE"
    exit 0
else
    info "Relevant logcat (VPN/mihomo):"
    grep -iE "mihomo|meow|vpn|tun" "$LOGCAT_FILE" | tail -50 || true
    echo "  SOME TESTS FAILED"
    echo "  Full logcat: $LOGCAT_FILE"
    exit 1
fi
