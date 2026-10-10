#!/bin/sh
set -eu
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
task_serial=${1:-}
task_apk="$task_root/build/rpi-ble-tunnel-android-debug.apk"
task_adb() {
    if [ -n "$task_serial" ]; then adb -s "$task_serial" "$@"; else adb "$@"; fi
}
if [ ! -f "$task_apk" ]; then "$task_root/scripts/build-android.sh"; fi
task_adb install -r "$task_apk"
task_adb shell am start -n org.rpibletunnel.android/.MainActivity
