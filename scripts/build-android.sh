#!/bin/sh
set -eu
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$task_root/build"
# The JVM tests use the same C mux engine as the Pi daemon, without BlueZ dependencies.
cc -std=gnu11 -Wall -Wextra -Wpedantic -I "$task_root/pi" \
    "$task_root/pi/mux.c" "$task_root/tests/mux_test_server.c" -o "$task_root/build/mux-interop-server"
if [ -z "${JAVA_HOME:-}" ] && [ -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]; then
    JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
    export JAVA_HOME
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    ANDROID_HOME="$HOME/Library/Android/sdk"
    export ANDROID_HOME
fi
"$task_root/android/gradlew" -p "$task_root/android" --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:writeMuxClasspath
python3 "$task_root/tests/integration/verify-internet.py" --client android --server "$task_root/build/mux-interop-server" \
    --output "$task_root/build/android-internet-results.json"
mkdir -p "$task_root/build"
cp "$task_root/android/app/build/outputs/apk/debug/app-debug.apk" "$task_root/build/rpi-ble-tunnel-android-debug.apk"
printf 'APK: %s/build/rpi-ble-tunnel-android-debug.apk\n' "$task_root"
