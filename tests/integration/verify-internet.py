#!/usr/bin/env python3
"""Exercise production C SOCKS5 and Swift/Kotlin relay through a TCP test wire."""

import concurrent.futures
import select
import argparse
import importlib.util
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("mux_checks", ROOT / "tests/integration/verify-multiplex.py")
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


def exact(sock, count):
    result = bytearray()
    while len(result) < count:
        data = sock.recv(count - len(result))
        assert data, "Unexpected SOCKS EOF"
        result.extend(data)
    return bytes(result)


def request(port, host="127.0.0.1", atyp=1):
    if atyp == 1:
        address = socket.inet_pton(socket.AF_INET, host)
    elif atyp == 4:
        address = socket.inet_pton(socket.AF_INET6, host)
    else:
        address = bytes([len(host.encode())]) + host.encode()
    return bytes([5, 1, 0, atyp]) + address + struct.pack("!H", port)


def open_socks(proxy, destination, host="127.0.0.1", atyp=1, early=b""):
    sock = socket.create_connection(("127.0.0.1", proxy), timeout=20)
    try:
        # Fragment the negotiation and pipeline data with the CONNECT request.
        for byte in b"\x05\x02\x02\x00":
            sock.sendall(bytes([byte]))
        assert exact(sock, 2) == b"\x05\x00"
        sock.sendall(request(destination, host, atyp) + early)
        reply = exact(sock, 10)
        assert reply[0] == 5 and reply[1] == 0 and reply[3] == 1, reply
        return sock
    except BaseException:
        sock.close()
        raise


def roundtrip(proxy, target, payload, host="127.0.0.1", atyp=1):
    with open_socks(proxy, target, host, atyp) as sock:
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            sender = pool.submit(lambda: (sock.sendall(payload), sock.shutdown(socket.SHUT_WR)))
            received = checks.read_all(sock)
            sender.result(timeout=20)
        assert received == payload + checks.TAIL, "SOCKS binary payload or half-close tail changed"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, default=ROOT / "build/local/mux_test_server")
    parser.add_argument("--client", choices=("macos", "android"), default="macos")
    parser.add_argument("--output", type=Path, default=ROOT / "build/internet-results.json")
    args = parser.parse_args()
    results = []
    def record(name, action):
        started = time.monotonic()
        action()
        result = {"case": name, "status": "PASS", "seconds": round(time.monotonic() - started, 3)}
        results.append(result)
        print(json.dumps(result), flush=True)
    echo = checks.EchoServer(("127.0.0.1", 0), checks.EchoHandler)
    class IPv6Echo(checks.EchoServer):
        address_family = socket.AF_INET6
    echo6 = IPv6Echo(("::1", 0), checks.EchoHandler)
    for endpoint in (echo, echo6):
        threading.Thread(target=endpoint.serve_forever, daemon=True).start()
    wire, socks, ssh = (checks.free_port() for _ in range(3))
    target, target6 = echo.server_address[1], echo6.server_address[1]
    binary = None
    if args.client == "macos":
        binary = Path(subprocess.check_output(["swift", "build", "--package-path", str(ROOT / "macos"),
                                              "--show-bin-path"], text=True).strip()) / "rpi-ble-tunnel-mux-test"
    processes = []
    handles = []
    with tempfile.TemporaryDirectory(prefix="rpi-ble-tunnel-internet-") as temp:
        directory = Path(temp)
        try:
            def start(command, filename, marker):
                log = directory / filename
                handle = log.open("w")
                handles.append(handle)
                process = subprocess.Popen(command, stdout=handle, stderr=handle)
                processes.append(process)
                checks.wait_ready(process, log, marker)
                return process
            server = start([str(args.server.resolve()), str(wire), str(target), str(socks)],
                           "server.log", "READY")
            if args.client == "android":
                java_home = Path(os.environ.get("JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home"))
                classpath = (ROOT / "build/android-mux-classpath.txt").read_text().strip()
                command = [str(java_home / "bin/java"), "-cp", classpath, "org.rpibletunnel.android.MuxTransportMain", str(wire), str(ssh), "internet"]
            else:
                command = [str(binary), str(wire), str(ssh), "internet"]
            proxy = start(command, "proxy.log", "READY")
            checks.wait_ready(server, directory / "server.log", "SOCKS5 READY")
            payload = bytes((i * 37 + 11) % 256 for i in range(131073))
            record("ipv4_binary_half_close", lambda: roundtrip(socks, target, payload))
            record("remote_dns_domain_binary_half_close", lambda: roundtrip(socks, target, payload, "localhost", 3))
            record("ipv6_binary_half_close", lambda: roundtrip(socks, target6, payload, "::1", 4))

            def pipelined():
                with open_socks(socks, target, early=b"early\x00\xff") as sock:
                    sock.shutdown(socket.SHUT_WR)
                    assert checks.read_all(sock) == b"early\x00\xff" + checks.TAIL
            record("pipelined_connect_and_early_data", pipelined)

            def rejection():
                with socket.create_connection(("127.0.0.1", socks), timeout=20) as sock:
                    sock.sendall(b"\x05\x01\x02")
                    assert exact(sock, 2) == b"\x05\xff"
                    assert sock.recv(1) == b""
                cases = [(b"\x05\x03\x00\x01", 7), (b"\x05\x01\x00\x02", 8),
                         (b"\x05\x01\x01\x01", 1), (b"\x05\x01\x00\x03\x00", 8),
                         (b"\x05\x01\x00\x03\x01\x00\x00\x16", 8),
                         (request(checks.free_port()), 5),
                         (request(443, "rpi-ble-tunnel-test.invalid", 3), 4),
                         (request(443, "x" * 255, 3), 4)]
                for command, reason in cases:
                    with socket.create_connection(("127.0.0.1", socks), timeout=20) as sock:
                        sock.sendall(b"\x05\x01\x00")
                        assert exact(sock, 2) == b"\x05\x00"
                        for byte in command:
                            sock.sendall(bytes([byte]))
                        reply = exact(sock, 10)
                        assert reply[1] == reason, (command, reply)
                        assert sock.recv(1) == b""
                roundtrip(socks, target, b"survives failures")
            record("unsupported_auth_command_address_dns_refusal_and_maximum_domain", rejection)

            def parallel():
                with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                    futures = [pool.submit(roundtrip, socks, target, payload + bytes([i])) for i in range(8)]
                    for future in futures:
                        future.result(timeout=30)
            record("eight_reverse_requests_with_seven_active_and_queue", parallel)
            time.sleep(0.2)

            def limit():
                held = []
                try:
                    for _ in range(4):
                        sock = open_socks(socks, target)
                        held.append(sock)
                        sock.sendall(b"r")
                        assert exact(sock, 1) == b"r"
                    for _ in range(4):
                        sock = socket.create_connection(("127.0.0.1", ssh), timeout=20)
                        held.append(sock)
                        sock.sendall(b"f")
                        assert exact(sock, 1) == b"f"
                    with socket.create_connection(("127.0.0.1", socks), timeout=20) as extra:
                        extra.sendall(b"\x05\x01\x00")
                        assert exact(extra, 2) == b"\x05\x00"
                        extra.sendall(request(target))
                        assert not select.select([extra], [], [], 0.2)[0], "Full proxy request was not queued"
                        with socket.create_connection(("127.0.0.1", ssh), timeout=20) as extra_ssh:
                            assert extra_ssh.recv(1) == b""
                        held.pop(0).close()
                        assert exact(extra, 10)[1] == 0
                        extra.sendall(b"queued")
                        assert exact(extra, 6) == b"queued"
                finally:
                    for sock in held:
                        sock.close()
                time.sleep(0.2)
                roundtrip(socks, target, b"new even stream ID")
                checks.roundtrip(ssh, b"new odd stream ID")
            record("shared_eight_limit_queue_forward_overflow_and_slot_reuse", limit)

            def reserved_ssh():
                held = []
                try:
                    for _ in range(7):
                        held.append(open_socks(socks, target))
                    with socket.create_connection(("127.0.0.1", socks), timeout=20) as queued:
                        queued.sendall(b"\x05\x01\x00")
                        assert exact(queued, 2) == b"\x05\x00"
                        queued.sendall(request(target))
                        assert not select.select([queued], [], [], 0.2)[0]
                        with socket.create_connection(("127.0.0.1", ssh), timeout=20) as shell:
                            shell.sendall(b"reserved SSH")
                            assert exact(shell, 12) == b"reserved SSH"
                            held.pop(0).close()
                            assert exact(queued, 10)[1] == 0
                finally:
                    for sock in held:
                        sock.close()
                time.sleep(0.2)
            record("ssh_slot_reserved_when_seven_external_streams_are_busy", reserved_ssh)

            def stalled():
                stopped = threading.Event()
                blocked = threading.Event()
                with open_socks(socks, target) as slow:
                    slow.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
                    slow.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
                    slow.setblocking(False)
                    def fill():
                        data = memoryview(payload * 64)
                        offset = 0
                        while offset < len(data) and not stopped.is_set():
                            try:
                                count = slow.send(data[offset:])
                                if not count: break
                                offset += count
                            except BlockingIOError:
                                blocked.set()
                                stopped.wait(0.05)
                            except OSError: break
                    worker = threading.Thread(target=fill, daemon=True)
                    worker.start()
                    try:
                        time.sleep(0.5)
                        assert blocked.is_set() and worker.is_alive(), "Reverse stream did not reach backpressure"
                        roundtrip(socks, target, b"independent reverse")
                        checks.roundtrip(ssh, b"SSH survives stalled reverse")
                        slow.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                        slow.shutdown(socket.SHUT_RDWR)
                    finally:
                        stopped.set()
                worker.join(timeout=5)
                assert not worker.is_alive()
                roundtrip(socks, target, b"survives reverse reset")
            record("reverse_backpressure_reset_and_ssh_isolation", stalled)

            def loss():
                held = [open_socks(socks, target), socket.create_connection(("127.0.0.1", ssh), timeout=20)]
                try:
                    for sock in held:
                        sock.sendall(b"live")
                        assert exact(sock, 4) == b"live"
                    server.terminate()
                    assert server.wait(timeout=10) == 0
                    assert proxy.wait(timeout=10) == 1
                    for sock in held:
                        assert sock.recv(1) == b""
                finally:
                    for sock in held: sock.close()
            record("transport_loss_closes_forward_reverse_and_socks_listener", loss)
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=10)
            for handle in handles: handle.close()
            for filename in ("proxy.log", "server.log"):
                log = directory / filename
                if log.exists(): (ROOT / f"build/internet-last-{filename}").write_text(log.read_text())
            for endpoint in (echo, echo6):
                endpoint.shutdown()
                endpoint.server_close()
    output = {"transport": "loopback TCP test wire; production C/" + ("Kotlin" if args.client == "android" else "Swift") + " logic; BLE not exercised", "results": results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2) + "\n")


if __name__ == "__main__":
    main()
