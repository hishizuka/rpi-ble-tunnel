#!/bin/bash
set -euo pipefail
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
task_target=${1:-}
task_alias=${2:-}
if [[ -z "$task_target" ]]; then
    printf 'Usage: %s USER@PI_HOST [HOST_KEY_ALIAS]\n' "$0" >&2
    exit 2
fi
if [[ ! "$task_target" =~ ^[a-zA-Z0-9_.@:-]+$ || "$task_target" == -* ]]; then
    printf 'Invalid SSH target\n' >&2
    exit 2
fi
task_ssh_options=(-o BatchMode=yes -o ConnectTimeout=10)
if [[ -n "$task_alias" ]]; then
    if [[ ! "$task_alias" =~ ^[a-zA-Z0-9_.-]+$ || "$task_alias" == -* ]]; then
        printf 'Invalid SSH host key alias\n' >&2
        exit 2
    fi
    task_ssh_options+=(-o "HostKeyAlias=$task_alias")
fi
ssh "${task_ssh_options[@]}" "$task_target" 'mkdir -p ~/rpi-ble-tunnel/scripts'
scp "${task_ssh_options[@]}" -r "$task_root/pi" "$task_root/tests" "$task_root/CMakeLists.txt" "$task_root/LICENSE" \
    "$task_target:rpi-ble-tunnel/"
scp "${task_ssh_options[@]}" "$task_root/scripts/install-pi-network.sh" \
    "$task_target:rpi-ble-tunnel/scripts/"
ssh "${task_ssh_options[@]}" "$task_target" 'set -eu
bash ~/rpi-ble-tunnel/scripts/install-pi-network.sh
sudo -n systemctl --no-pager --full status rpi-ble-tunneld.service'
