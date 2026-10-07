#!/usr/bin/env python3
"""Verify Android PiLink through adb forwarding while the display is asleep."""

import argparse
import concurrent.futures
import json
from pathlib import Path
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--proxy-port", type=int, default=2223)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--host-key-alias", required=True)
    args = parser.parse_args()
    if not 1 <= args.proxy_port <= 65535:
        parser.error("Proxy port must be in 1..65535")
    adb = ["adb", "-s", args.serial]

    def device(*command):
        return subprocess.check_output(adb + ["shell", *command], text=True, timeout=15)

    def power():
        data = device("dumpsys", "power")
        state = re.search(r"mWakefulness=(\w+)", data)
        locks = data.split("Wake Locks: size=", 1)[-1].split("Suspend Blockers:", 1)[0]
        return {"wakefulness": state.group(1) if state else None,
                "pilink_wake_lock_held": "'PiLink:SSH'" in locks}

    ssh = ["ssh", "-o", "BatchMode=yes", "-o", "HostKeyAlias=" + args.host_key_alias,
           "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=60",
           "-o", "ControlMaster=no", "-o", "ControlPath=none", "-p", str(args.proxy_port),
           "pi@127.0.0.1"]
    console = subprocess.Popen(ssh + ["printf 'SCREEN_READY\\n'; read release; printf SCREEN_OK"],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            ready = pool.submit(console.stdout.readline)
            try:
                assert ready.result(timeout=60) == b"SCREEN_READY\n", "SSH did not become ready"
            except BaseException:
                console.kill()
                raise
        device("input", "keyevent", "223")
        deadline = time.monotonic() + 15
        while True:
            asleep = power()
            if asleep["wakefulness"] in ("Asleep", "Dozing"):
                break
            assert time.monotonic() < deadline, "Display did not enter sleep"
            time.sleep(0.2)
        assert asleep["pilink_wake_lock_held"], "Active SSH has no partial wake lock"
        started = time.monotonic()
        second = subprocess.run(ssh + ["sleep 12; printf SECOND_SCREEN_OK"],
                                capture_output=True, check=True, timeout=90)
        assert second.stdout == b"SECOND_SCREEN_OK"
        still_asleep = power()
        assert still_asleep["wakefulness"] in ("Asleep", "Dozing")
        assert still_asleep["pilink_wake_lock_held"]
        output, error = console.communicate(input=b"release\n", timeout=30)
        assert console.returncode == 0 and output == b"SCREEN_OK", error.decode(errors="replace")
        elapsed = time.monotonic() - started
        deadline = time.monotonic() + 10
        while True:
            idle = power()
            if not idle["pilink_wake_lock_held"]:
                break
            assert time.monotonic() < deadline, "Wake lock remained after all SSH streams ended"
            time.sleep(0.2)
        result = {"case": "screen_off_parallel_ssh_and_wake_lock_release", "status": "PASS",
                  "seconds": round(elapsed, 3), "asleep": asleep, "still_asleep": still_asleep,
                  "idle": idle}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({"transport": "Mac OpenSSH / adb USB forwarding / Android BLE / Pi",
                                         "results": [result]}, indent=2) + "\n")
        print(json.dumps(result), flush=True)
    finally:
        if console.poll() is None:
            console.kill()
        console.communicate(timeout=10)
        device("input", "keyevent", "224")


if __name__ == "__main__":
    main()
