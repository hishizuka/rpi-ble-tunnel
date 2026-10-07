#!/usr/bin/env python3
"""Verify BLE echo on real hardware and save inspectable results."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys
import time


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description="PiLink BLE 実機 echo 検証")
    parser.add_argument("--name", required=True, help="Pi hostname advertised over BLE")
    parser.add_argument("--large", action="store_true", help="1 MiB の検証を追加（最大 10 分）")
    args = parser.parse_args()
    binary = root / "build/PiLink.app/Contents/MacOS/pilink"
    if not binary.is_file():
        parser.error("先に ./scripts/build-macos.sh を実行してください")
    cases = [(65, 1, 120), (65536, 511, 120)]
    if args.large:
        cases.append((1048576, 16384, 600))
    results = []
    output = root / "build/echo-results.json"
    for size, chunk, timeout in cases:
        command = [str(binary), "echo", "--name", args.name, "--size", str(size),
                   "--chunk-size", str(chunk), "--timeout", str(timeout)]
        print(f"TEST size={size}, chunk={chunk}", flush=True)
        started = datetime.now(timezone.utc).isoformat()
        start = time.monotonic()
        try:
            process = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, timeout=timeout + 10)
            code, log = process.returncode, process.stdout
        except subprocess.TimeoutExpired as error:
            code = 124
            log = (error.stdout or b"").decode(errors="replace") + "\nProcess timeout\n"
        print(log, flush=True)
        results.append({"started_at_utc": started, "size": size, "chunk_size": chunk,
                        "elapsed_seconds": time.monotonic() - start, "exit_code": code, "output": log})
        output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
        if code:
            print(f"FAIL: 詳細は {output}", file=sys.stderr)
            return 1
    print(f"Hardware echo suite: PASS ({output})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
