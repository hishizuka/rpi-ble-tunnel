#!/usr/bin/env python3
"""Check that loss of an established BLE proxy terminates every SSH session."""

import argparse
import concurrent.futures
import json
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proxy-port", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--host-key-alias", required=True)
    parser.add_argument("--stop-command", nargs=argparse.REMAINDER, required=True,
                        help="Command and arguments that stop the test BLE daemon or client")
    args = parser.parse_args()
    if not 1 <= args.proxy_port <= 65535 or not args.stop_command:
        parser.error("A valid proxy port and stop command are required")
    ssh = ["ssh", "-o", "BatchMode=yes", "-o", "HostKeyAlias=" + args.host_key_alias,
           "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=60",
           "-o", "ControlMaster=no", "-o", "ControlPath=none", "-p", str(args.proxy_port),
           "pi@127.0.0.1", "printf 'SESSION_READY\\n'; read release; printf SESSION_FINISHED"]
    sessions = []
    try:
        for _ in range(3):
            sessions.append(subprocess.Popen(ssh, stdin=subprocess.PIPE,
                                             stdout=subprocess.PIPE, stderr=subprocess.PIPE))
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            ready = [pool.submit(session.stdout.readline) for session in sessions]
            try:
                for future in ready:
                    assert future.result(timeout=60) == b"SESSION_READY\n", "SSH did not become ready"
            except BaseException:
                for session in sessions:
                    if session.poll() is None:
                        session.kill()
                raise
        assert all(session.poll() is None for session in sessions)
        started = time.monotonic()
        subprocess.run(args.stop_command, check=True, capture_output=True, timeout=30)
        for session in sessions:
            output, error = session.communicate(timeout=20)
            assert session.returncode == 255, (session.returncode, output, error)
            assert b"SESSION_FINISHED" not in output, "Session completed instead of disconnecting"
        result = {"case": "ble_loss_closes_three_authenticated_ssh_sessions", "status": "PASS",
                  "seconds": round(time.monotonic() - started, 3),
                  "ssh_exit_codes": [session.returncode for session in sessions]}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({"transport": "externally established BLE proxy",
                                         "results": [result]}, indent=2) + "\n")
        print(json.dumps(result), flush=True)
    finally:
        for session in sessions:
            if session.poll() is None:
                session.kill()
            session.communicate(timeout=10)


if __name__ == "__main__":
    main()
