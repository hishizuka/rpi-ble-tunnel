#!/bin/bash
# Install rpi-ble-tunnel and its dependencies on Raspberry Pi OS.
set -Eeuo pipefail
task_script=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/$(basename -- "${BASH_SOURCE[0]}")
task_root=$(CDPATH= cd -- "$(dirname -- "$task_script")/.." && pwd)

usage() {
    printf 'Usage: bash %s [--skip-deps] [--caller-managed]\n' "$task_script"
    printf 'Install dependencies, build and test, then enable rpi-ble-tunneld.service.\n'
    printf '  --skip-deps  Use dependencies already installed by the caller.\n'
    printf '  --caller-managed  Let the caller select the BLE adapter and start rpi-ble-tunnel.\n'
}
if [[ $# == 1 && ( "$1" == --help || "$1" == -h ) ]]; then usage; exit 0; fi
task_skip_deps=false
task_caller_managed=false
while [[ $# -gt 0 && "$1" == --* ]]; do
    case "$1" in
        --skip-deps) task_skip_deps=true ;;
        --caller-managed) task_caller_managed=true ;;
        *) usage >&2; exit 2 ;;
    esac
    shift
done
if [[ $(uname -s) != Linux || ! -d /run/systemd/system ]]; then
    printf 'This installer requires Raspberry Pi OS with systemd.\n' >&2
    exit 2
fi
export PATH="${PATH:-/usr/bin:/bin}:/usr/sbin:/sbin"
if [[ -f /etc/systemd/system/rpi-ble-tunneld.service.d/20-caller-adapter.conf ]]; then
    task_caller_managed=true
fi

if [[ $# == 0 ]]; then
    task_sudo=()
    if [[ $EUID -ne 0 ]]; then
        task_sudo=(sudo -n)
        if ! "${task_sudo[@]}" true; then
            printf 'Administrator access is required. Run sudo -v first, or run this script with sudo.\n' >&2
            exit 2
        fi
    fi
    test -f "$task_root/CMakeLists.txt"
    test -f "$task_root/LICENSE"
    test -f "$task_root/pi/rpi-ble-tunneld.service"
    test -d "$task_root/tests"
    task_cache="$task_root/build/pi-install"
    mkdir -p "$task_cache"
    # Keep the lock in the parent while the same script installs as root.
    exec 9>"$task_cache/install.lock"
    flock -n 9 || { printf 'Another rpi-ble-tunnel installer is running for this checkout.\n' >&2; exit 2; }

    if [[ "$task_skip_deps" == false ]]; then
        printf 'Installing build and runtime dependencies...\n'
        "${task_sudo[@]}" apt-get update
        "${task_sudo[@]}" env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends --no-upgrade \
            build-essential cmake pkg-config libglib2.0-dev libbluetooth-dev \
            curl ca-certificates xz-utils network-manager python3 iproute2 \
            bluez openssh-server rfkill kmod util-linux
    fi
    for task_command in cmake ctest make cc pkg-config curl sha256sum tar nmcli \
                        systemctl journalctl busctl rfkill modprobe; do
        command -v "$task_command" >/dev/null || {
            printf 'Missing dependency: %s. Run without --skip-deps.\n' "$task_command" >&2
            exit 2
        }
    done
    test -x /usr/bin/python3

    task_hev_version=2.18.0
    task_hev_sha=93b3b33127436b4eab669798f1d50e019008585a27880df8cbdeffe5e70cb665
    task_archive="$task_cache/hev-socks5-tunnel-$task_hev_version.tar.xz"
    if [[ ! -f "$task_archive" ]]; then
        printf 'Downloading hev-socks5-tunnel %s...\n' "$task_hev_version"
        curl --fail --location --retry 3 --connect-timeout 15 --max-time 600 \
            "https://github.com/heiher/hev-socks5-tunnel/releases/download/$task_hev_version/hev-socks5-tunnel-$task_hev_version.tar.xz" \
            --output "$task_archive.part"
        printf '%s  %s\n' "$task_hev_sha" "$task_archive.part" | sha256sum -c -
        mv "$task_archive.part" "$task_archive"
    fi
    printf '%s  %s\n' "$task_hev_sha" "$task_archive" | sha256sum -c -
    # Re-extract the verified source before each incremental native build.
    tar -xJf "$task_archive" -C "$task_cache" --no-same-owner
    task_hev="$task_cache/hev-socks5-tunnel-$task_hev_version/bin/hev-socks5-tunnel"
    task_build="$task_cache/rpi-ble-tunnel"
    printf 'Building and testing rpi-ble-tunnel (one compiler job for Pi Zero)...\n'
    cmake -S "$task_root" -B "$task_build" -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON
    cmake --build "$task_build" --parallel 1
    ctest --test-dir "$task_build" --output-on-failure
    PYTHONPYCACHEPREFIX="$task_cache/pycache" /usr/bin/python3 -m py_compile "$task_root/pi/rpi-ble-tunnel-network.py"
    PYTHONPYCACHEPREFIX="$task_cache/pycache" /usr/bin/python3 -m py_compile "$task_root/pi/rpi-ble-tunnel-service-control.py"
    /usr/bin/python3 -m unittest discover -s "$task_root/tests" -p 'test_*.py'
    printf 'Building hev-socks5-tunnel...\n'
    make -C "$task_cache/hev-socks5-tunnel-$task_hev_version" -j1

    if [[ $EUID -ne 0 ]]; then
        task_install_options=()
        if [[ "$task_caller_managed" == true ]]; then task_install_options+=(--caller-managed); fi
        "${task_sudo[@]}" bash "$task_script" "${task_install_options[@]}" "$task_build" "$task_hev"
        exit 0
    fi
elif [[ $# == 2 && "$1" != -* && "$2" != -* ]]; then
    # Preserve the prebuilt installation entry point and use it for sudo above.
    task_build=$1
    task_hev=$2
else
    usage >&2
    exit 2
fi

if [[ $EUID -ne 0 ]]; then
    printf 'Installing prebuilt binaries requires root.\n' >&2
    exit 2
fi
exec 8>/run/lock/rpi-ble-tunnel-install.lock
flock -n 8 || { printf 'Another rpi-ble-tunnel service installation is running.\n' >&2; exit 2; }
test -x "$task_build/rpi-ble-tunneld"
test -x "$task_hev"
command -v nmcli >/dev/null
test -x /usr/bin/python3
if [[ ! -c /dev/net/tun ]]; then modprobe tun; fi
test -c /dev/net/tun
task_help=$("$task_build/rpi-ble-tunneld" --help)
[[ "$task_help" == *--state-file* ]]
# Prepare only the dependencies required for the BLE and SSH endpoints.
systemctl start bluetooth.service NetworkManager.service
if [[ "$task_caller_managed" == false ]]; then
    rfkill unblock bluetooth
    busctl --system set-property org.bluez /org/bluez/hci0 org.bluez.Adapter1 Powered b true
fi
systemctl enable --now ssh.service
task_daemon_active=$(systemctl is-active rpi-ble-tunneld.service || true)
task_daemon_enabled=$(systemctl is-enabled rpi-ble-tunneld.service 2>/dev/null || true)
task_network_enabled=$(systemctl is-enabled rpi-ble-tunnel-network.service 2>/dev/null || true)
task_network_active=$(systemctl is-active rpi-ble-tunnel-network.service 2>/dev/null || true)
install -d -m 755 /var/backups
task_backup=$(mktemp -d /var/backups/rpi-ble-tunnel-0.1.0-XXXXXXXX)
task_paths=(
    /usr/local/bin/rpi-ble-tunneld
    /usr/local/libexec/rpi-ble-tunnel-network
    /usr/local/libexec/rpi-ble-tunnel-service-control
    /usr/local/libexec/hev-socks5-tunnel
    /usr/local/share/doc/rpi-ble-tunnel/LICENSE
    /usr/local/share/doc/rpi-ble-tunnel/hev-socks5-tunnel-LICENSE.txt
    /etc/systemd/system/rpi-ble-tunneld.service
    /etc/systemd/system/rpi-ble-tunnel-network.service
    /etc/systemd/system/rpi-ble-tunneld.service.d/20-caller-adapter.conf
)
for task_path in "${task_paths[@]}"; do
    if [[ -e "$task_path" ]]; then cp -a --parents "$task_path" "$task_backup/"; fi
done
printf '%s\n' "daemon_active=$task_daemon_active" "daemon_enabled=$task_daemon_enabled" \
    "network_active=$task_network_active" "network_enabled=$task_network_enabled" > "$task_backup/services.txt"

rollback() {
    local task_exit=$1
    trap - ERR INT TERM
    set +e
    printf 'Installation failed; restoring %s\n' "$task_backup" >&2
    systemctl stop rpi-ble-tunnel-network.service rpi-ble-tunneld.service 2>/dev/null || true
    for task_path in "${task_paths[@]}"; do
        if [[ -e "$task_backup$task_path" ]]; then
            cp -a --remove-destination "$task_backup$task_path" "$task_path"
        else
            rm -f "$task_path"
        fi
    done
    systemctl daemon-reload
    if [[ "$task_daemon_enabled" == enabled ]]; then
        systemctl enable rpi-ble-tunneld.service
    else
        systemctl disable rpi-ble-tunneld.service 2>/dev/null || true
    fi
    if [[ "$task_network_enabled" == enabled ]]; then
        systemctl enable rpi-ble-tunnel-network.service
    else
        systemctl disable rpi-ble-tunnel-network.service 2>/dev/null || true
        if [[ ! -e /etc/systemd/system/rpi-ble-tunnel-network.service ]]; then
            rm -f /etc/systemd/system/multi-user.target.wants/rpi-ble-tunnel-network.service
        fi
    fi
    if [[ "$task_daemon_active" == active ]]; then systemctl restart rpi-ble-tunneld.service; fi
    if [[ "$task_network_active" == active ]]; then systemctl start rpi-ble-tunnel-network.service; fi
    exit "$task_exit"
}
trap 'rollback "$?"' ERR
trap 'rollback 130' INT
trap 'rollback 143' TERM
# Stop the old split services before replacing their files.
if [[ "$task_daemon_active" == active || -e /etc/systemd/system/rpi-ble-tunneld.service ]]; then
    systemctl stop rpi-ble-tunneld.service
fi
if [[ "$task_network_active" == active || -e /etc/systemd/system/rpi-ble-tunnel-network.service ]]; then
    systemctl disable --now rpi-ble-tunnel-network.service
    systemctl reset-failed rpi-ble-tunnel-network.service
fi
install -d -m 755 /usr/local/libexec /usr/local/share/doc/rpi-ble-tunnel
install -m 755 "$task_build/rpi-ble-tunneld" /usr/local/bin/rpi-ble-tunneld
install -m 755 "$task_root/pi/rpi-ble-tunnel-network.py" /usr/local/libexec/rpi-ble-tunnel-network
install -m 755 "$task_root/pi/rpi-ble-tunnel-service-control.py" /usr/local/libexec/rpi-ble-tunnel-service-control
if [[ "$(readlink -f "$task_hev")" != /usr/local/libexec/hev-socks5-tunnel ]]; then
    install -m 755 "$task_hev" /usr/local/libexec/hev-socks5-tunnel
fi
install -m 644 "$task_root/LICENSE" "$task_root/pi/hev-socks5-tunnel-LICENSE.txt" /usr/local/share/doc/rpi-ble-tunnel/
install -m 644 "$task_root/pi/rpi-ble-tunneld.service" /etc/systemd/system/rpi-ble-tunneld.service
if [[ "$task_caller_managed" == true ]]; then
    install -d -m 755 /etc/systemd/system/rpi-ble-tunneld.service.d
    install -m 644 "$task_root/pi/rpi-ble-tunnel-caller-adapter.conf" /etc/systemd/system/rpi-ble-tunneld.service.d/20-caller-adapter.conf
fi
rm -f /etc/systemd/system/rpi-ble-tunnel-network.service
systemctl daemon-reload
if [[ "$task_caller_managed" == true ]]; then
    trap - ERR INT TERM
    printf 'Installed caller-managed rpi-ble-tunnel. Select an adapter through rpi-ble-tunnel-service-control to start. Backup: %s\n' "$task_backup"
    exit 0
fi
systemctl enable rpi-ble-tunneld.service
systemctl restart rpi-ble-tunneld.service
task_deadline=$((SECONDS + 60))
task_ready=false
while (( SECONDS < task_deadline )); do
    if systemctl is-active --quiet rpi-ble-tunneld.service && \
       [[ -f /run/rpi-ble-tunnel/link.json && -f /run/rpi-ble-tunnel-network/status.json ]]; then
        task_invocation=$(systemctl show rpi-ble-tunneld.service -p InvocationID --value)
        task_log=$(journalctl -b "_SYSTEMD_INVOCATION_ID=$task_invocation" --no-pager -o cat)
        if [[ "$task_log" == *"READY name="*"mode=internet"* ]]; then
            task_ready=true
            break
        fi
    fi
    sleep 2
done
if [[ "$task_ready" != true ]]; then
    printf 'rpi-ble-tunnel did not finish registering its BLE service.\n' >&2
    journalctl -u rpi-ble-tunneld.service -n 40 --no-pager >&2
    false
fi
trap - ERR INT TERM
printf 'Installed rpi-ble-tunnel as rpi-ble-tunneld.service. Backup: %s\n' "$task_backup"
