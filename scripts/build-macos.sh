#!/bin/sh
set -eu
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
swift build --package-path "$task_root/macos" -c release --product rpi-ble-tunnel
task_bin_dir=$(swift build --package-path "$task_root/macos" -c release --show-bin-path)
task_app="$task_root/build/rpi-ble-tunnel.app"
mkdir -p "$task_app/Contents/MacOS" "$task_app/Contents/Resources"
cp "$task_root/macos/Info.plist" "$task_app/Contents/Info.plist"
cp "$task_bin_dir/rpi-ble-tunnel" "$task_app/Contents/MacOS/rpi-ble-tunnel"
cp "$task_root/LICENSE" "$task_app/Contents/Resources/LICENSE"
codesign --force --sign - --identifier org.rpibletunnel.client "$task_app"
printf 'Mac client: %s\n' "$task_app/Contents/MacOS/rpi-ble-tunnel"
