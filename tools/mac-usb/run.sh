#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
SERIAL="${DIPLAY_EMULATOR:-emulator-5554}"
APP=com.shihab.diplay.hudtest
APK="$ROOT/mobile/build/outputs/apk/debug/mobile-debug.apk"
if [[ "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
    echo "Refusing to use a non-emulator Android target" >&2; exit 1
fi
if [[ "$(idevice_id -l | awk 'NF {n++} END {print n+0}')" != 1 ]]; then
    echo "Connect exactly one USB iPhone and trust this Mac" >&2; exit 1
fi
IFACE="$(python3 "$ROOT/tools/mac-usb/find-ncm.py")"
[[ -f "$APK" ]] || { echo "Build :mobile:assembleStandaloneDebug first" >&2; exit 1; }
# Test bind availability without connecting: a connection would consume the
# relay's single client slot. SO_REUSEADDR avoids mistaking TIME_WAIT for a listener.
port_in_use() {
    python3 - "$1" <<'PYPORT'
import errno
import socket
import sys
with socket.socket() as probe:
    probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        probe.bind(("127.0.0.1", int(sys.argv[1])))
    except OSError as error:
        if error.errno == errno.EADDRINUSE:
            sys.exit(0)
        raise
sys.exit(1)
PYPORT
}
if port_in_use 19777 || port_in_use 19778; then
    echo "A lab relay is already listening; let it finish before restarting" >&2; exit 1
fi
BUILD="$ROOT/build/mac-usb"
mkdir -p "$BUILD"
clang -Wall -Wextra -Werror -I/opt/homebrew/include -L/opt/homebrew/lib \
    "$ROOT/tools/mac-usb/carkit-relay.c" -o "$BUILD/carkit-relay" -limobiledevice-1.0
clang -Wall -Wextra -Werror "$ROOT/tools/mac-usb/ncm-relay.c" -o "$BUILD/ncm-relay" -lpcap
RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/diplay-mac-usb.XXXXXX")"
CARKIT_PID=""
ADMIN_PID=""
cleanup() {
    "$ADB" -s "$SERIAL" shell am force-stop "$APP" >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" reverse --remove tcp:19777 >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" reverse --remove tcp:19778 >/dev/null 2>&1 || true
    [[ -z "$CARKIT_PID" ]] || kill "$CARKIT_PID" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"$ADB" -s "$SERIAL" install -r "$APK"
"$ADB" -s "$SERIAL" reverse tcp:19777 tcp:19777
"$ADB" -s "$SERIAL" reverse tcp:19778 tcp:19778
echo "USB NCM interface: $IFACE. macOS will request administrator authentication."
echo "Only this USB interface is used; the relays stop after at most three minutes of connection."
echo "Logs: $RUN_DIR"
osascript "$ROOT/tools/mac-usb/start-admin.applescript" "$BUILD/ncm-relay" "$IFACE" >"$RUN_DIR/ncm.log" 2>&1 &
ADMIN_PID=$!
READY=0
for ((i=0; i<120; i++)); do
    if port_in_use 19778; then READY=1; break; fi
    if ! kill -0 "$ADMIN_PID" 2>/dev/null; then cat "$RUN_DIR/ncm.log"; exit 1; fi
    sleep 1
done
[[ "$READY" == 1 ]] || { echo "NCM helper did not start" >&2; exit 1; }
"$BUILD/carkit-relay" >"$RUN_DIR/carkit.log" 2>&1 &
CARKIT_PID=$!
for ((i=0; i<50; i++)); do
    grep -q '^LISTEN' "$RUN_DIR/carkit.log" && break
    if ! kill -0 "$CARKIT_PID" 2>/dev/null; then cat "$RUN_DIR/carkit.log"; exit 1; fi
    sleep 0.1
done
grep -q '^LISTEN' "$RUN_DIR/carkit.log" || { echo "CarKit helper did not start" >&2; exit 1; }
"$ADB" -s "$SERIAL" shell am start -W -n "$APP/com.shilapi.xcertplay.MacUsbCarPlayActivity"
echo "Accept the local VPN dialog in the emulator on the first run."
wait "$CARKIT_PID" || true
wait "$ADMIN_PID" || true
"$ADB" -s "$SERIAL" shell run-as "$APP" cat files/mac-usb-media.log >"$RUN_DIR/android.log" || true
echo "Session ended. Logs: $RUN_DIR"
