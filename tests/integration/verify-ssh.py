#!/usr/bin/env python3
"""Verify OpenSSH and file transfers through the running BLE bridge."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import socket
import subprocess
import sys
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description="PiLink SSH 実機検証（pilink connect を先に起動）")
    parser.add_argument("--port", type=int, default=2222)
    parser.add_argument("--user", default="pi")
    parser.add_argument("--host-key-alias", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = root / "build/ssh-results.json"
    output.parent.mkdir(exist_ok=True)
    results = []
    target = f"{args.user}@127.0.0.1"
    options = ["-F", "/dev/null", "-o", "BatchMode=yes", "-o", "ConnectTimeout=30",
               "-o", f"HostKeyAlias={args.host_key_alias}", "-o", "StrictHostKeyChecking=yes",
               "-o", "ControlMaster=no", "-o", "ControlPath=none"]
    ssh = ["ssh", "-p", str(args.port), *options]
    scp = ["scp", "-P", str(args.port), *options]
    sftp = ["sftp", "-P", str(args.port), *options]
    remote = None

    def record(name, code, log, elapsed, command=None):
        results.append({"name": name, "recorded_at_utc": datetime.now(timezone.utc).isoformat(),
                        "exit_code": code, "elapsed_seconds": round(elapsed, 3),
                        "command": command, "output": log})
        output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")

    def run(name, command, cwd=None, stdin=None, expected=0):
        print(f"TEST {name}", flush=True)
        started = time.monotonic()
        process = subprocess.run(command, input=stdin, cwd=cwd, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, text=True, timeout=240)
        record(name, process.returncode, process.stdout, time.monotonic() - started, command)
        if process.returncode != expected:
            raise RuntimeError(f"{name}: exit={process.returncode}\n{process.stdout}")
        print(f"PASS {name} ({time.monotonic() - started:.1f}s)", flush=True)
        return process.stdout

    try:
        log = run("SSH login", [*ssh, target, "hostname; id -un; mktemp -d /tmp/pilink-phase2.XXXXXXXX"])
        lines = log.strip().splitlines()
        if len(lines) != 3 or lines[1] != args.user or not re.fullmatch(r"/tmp/pilink-phase2\.[A-Za-z0-9]+", lines[2]):
            raise RuntimeError(f"Unexpected login output: {log}")
        remote = lines[2]
        print(f"Pi hostname={lines[0]}", flush=True)
        log = run("PTY and exit status 7", [*ssh, "-tt", target, "test -t 0 && printf 'PTY_OK\\n'; exit 7"], expected=7)
        if "PTY_OK" not in log:
            raise RuntimeError("PTY was not allocated")
        with tempfile.TemporaryDirectory(prefix="pilink-phase2-", dir=root / "build") as temporary:
            work = Path(temporary)
            payload = os.urandom(65536)
            source = work / "source.bin"
            source.write_bytes(payload)
            digest = hashlib.sha256(payload).hexdigest()
            run("SCP upload 64 KiB", [*scp, str(source), f"{target}:{remote}/scp.bin"])
            run("SCP download 64 KiB", [*scp, f"{target}:{remote}/scp.bin", str(work / "scp-returned.bin")])
            if (work / "scp-returned.bin").read_bytes() != payload:
                raise RuntimeError("SCP binary mismatch")
            batch = f"put source.bin {remote}/sftp.bin\nget {remote}/sftp.bin sftp-returned.bin\nbye\n"
            run("SFTP round trip 64 KiB", [*sftp, "-b", "-", target], cwd=work, stdin=batch)
            if (work / "sftp-returned.bin").read_bytes() != payload:
                raise RuntimeError("SFTP binary mismatch")
            shell = shlex.join(ssh)
            run("rsync upload 64 KiB", ["rsync", "-a", "-e", shell, str(source), f"{target}:{remote}/rsync.bin"])
            run("rsync download 64 KiB", ["rsync", "-a", "-e", shell, f"{target}:{remote}/rsync.bin", str(work / "rsync-returned.bin")])
            if (work / "rsync-returned.bin").read_bytes() != payload:
                raise RuntimeError("rsync binary mismatch")
            record("64 KiB file integrity", 0, f"SCP/SFTP/rsync all identical; SHA-256={digest}", 0)

        print("TEST concurrent connection rejection and abrupt close", flush=True)
        started = time.monotonic()
        with socket.create_connection(("127.0.0.1", args.port), timeout=30) as first:
            banner = first.recv(256)
            if not banner.startswith(b"SSH-"):
                raise RuntimeError(f"No SSH banner: {banner!r}")
            with socket.create_connection(("127.0.0.1", args.port), timeout=5) as second:
                try:
                    if second.recv(1):
                        raise RuntimeError("Extra concurrent TCP connection was accepted")
                except ConnectionResetError:
                    pass
        record("concurrent rejection and abrupt close", 0, "Extra connection rejected; first connection closed before SSH authentication",
               time.monotonic() - started)
        time.sleep(1)
        log = run("SSH reconnect after abrupt close", [*ssh, target, "printf 'RECONNECT_OK\\n'"])
        if "RECONNECT_OK" not in log:
            raise RuntimeError("Reconnect output missing")
    except Exception as error:
        record("verification failure", 1, str(error), 0)
        print(f"FAIL: {error}", file=sys.stderr)
    finally:
        if remote is not None:
            try:
                # Only remove the private directory created and validated by this run.
                run("temporary directory cleanup", [*ssh, target, f"rm -rf -- {shlex.quote(remote)}"])
            except Exception as error:
                record("cleanup failure", 1, str(error), 0)
                print(f"Cleanup failed: {remote}: {error}", file=sys.stderr)
    failed = any(item["name"] in ["verification failure", "cleanup failure"] for item in results)
    print(f"Hardware SSH suite: {'FAIL' if failed else 'PASS'} ({output})", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
