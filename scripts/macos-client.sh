#!/bin/sh
set -eu
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
task_binary="$task_root/build/PiLink.app/Contents/MacOS/pilink"
task_pid_file="$task_root/build/pilink-connect.pid"
task_log="$task_root/build/pilink-connect.log"
task_action=${1:-status}
task_name=${2:-}
task_pid=

client_alive() {
    [ -r "$task_pid_file" ] || return 1
    read -r task_pid < "$task_pid_file"
    case "$task_pid" in ''|0|*[!0-9]*) return 1 ;; esac
    task_command=$(ps -ww -p "$task_pid" -o command= 2>/dev/null) || return 1
    case "$task_command" in "$task_binary connect "*) return 0 ;; *) return 1 ;; esac
}

case "$task_action" in
    start)
        if [ -z "$task_name" ]; then
            printf 'Usage: %s start HOSTNAME\n' "$0" >&2
            exit 2
        fi
        if client_alive; then
            printf 'PiLink client is running (PID %s)\n' "$task_pid"
            exit 0
        fi
        if [ ! -x "$task_binary" ]; then
            printf 'Run scripts/build-macos.sh first\n' >&2
            exit 1
        fi
        mkdir -p "$task_root/build"
        task_pid=$(python3 - "$task_binary" "$task_log" "$task_name" <<'PY'
import subprocess
import sys

# A separate session keeps the client alive when the invoking terminal exits.
with open(sys.argv[2], "wb") as log:
    child = subprocess.Popen([sys.argv[1], "connect", "--name", sys.argv[3], "--timeout", "60"],
                             stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                             start_new_session=True)
print(child.pid)
PY
)
        printf '%s\n' "$task_pid" > "$task_pid_file"
        task_count=0
        while [ "$task_count" -lt 65 ]; do
            if ! kill -0 "$task_pid" 2>/dev/null; then
                cat "$task_log"
                rm -f "$task_pid_file"
                exit 1
            fi
            if /usr/bin/grep -q '^READY ' "$task_log"; then
                printf 'PiLink SSH ready: 127.0.0.1:2222 (PID %s)\nLog: %s\n' "$task_pid" "$task_log"
                exit 0
            fi
            sleep 1
            task_count=$((task_count + 1))
        done
        if client_alive; then kill -TERM "$task_pid"; fi
        printf 'PiLink did not become ready; see %s\n' "$task_log" >&2
        exit 1
        ;;
    stop)
        if client_alive; then
            kill -TERM "$task_pid"
            task_count=0
            while client_alive && [ "$task_count" -lt 5 ]; do
                sleep 1
                task_count=$((task_count + 1))
            done
            if client_alive; then
                printf 'PiLink is still shutting down (PID %s)\n' "$task_pid" >&2
                exit 1
            fi
        fi
        rm -f "$task_pid_file"
        printf 'PiLink client stopped\n'
        ;;
    status)
        if client_alive; then
            printf 'PiLink client is running (PID %s)\nLog: %s\n' "$task_pid" "$task_log"
        else
            printf 'PiLink client is not running\n'
            exit 1
        fi
        ;;
    *) printf 'Usage: %s start HOSTNAME|stop|status\n' "$0" >&2; exit 2 ;;
esac
