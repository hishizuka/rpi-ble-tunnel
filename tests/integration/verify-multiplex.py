#!/usr/bin/env python3
"""Exercise multiplexed SSH through BLE or a substituted wire transport."""

import argparse
import concurrent.futures
import hashlib
import json
import re
from pathlib import Path
import socket
import socketserver
import struct
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
TAIL = b"\x00FIN-tail\xff"


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_ready(process, log, marker):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if marker in log.read_text():
            return
        if process.poll() is not None:
            raise RuntimeError(log.read_text())
        time.sleep(0.02)
    raise TimeoutError(f"Missing {marker}: {log.read_text()}")


def read_all(sock):
    result = bytearray()
    while True:
        data = sock.recv(4096)
        if not data:
            return bytes(result)
        result.extend(data)


def roundtrip(port, payload):
    with socket.create_connection(("127.0.0.1", port), timeout=20) as sock:
        # Read while writing so a payload larger than both windows cannot deadlock.
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            writer = pool.submit(lambda: (sock.sendall(payload), sock.shutdown(socket.SHUT_WR)))
            received = read_all(sock)
            writer.result(timeout=20)
        assert received == payload + TAIL, "Binary payload or EOF tail changed"
        return hashlib.sha256(payload).hexdigest()


class EchoHandler(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            while True:
                data = self.request.recv(4096)
                if not data:
                    self.request.sendall(TAIL)
                    return
                self.request.sendall(data)
        except (OSError, ConnectionError):
            pass


class EchoServer(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 32


def local_checks(port, record):
    payload = bytes((i * 37 + 11) % 256 for i in range(131072))

    def parallel():
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            hashes = list(pool.map(lambda n: roundtrip(port, payload + bytes([n])), range(8)))
        assert len(set(hashes)) == 8
    record("eight_parallel_binary_streams", parallel)

    def stalled():
        with socket.create_connection(("127.0.0.1", port), timeout=20) as slow:
            slow.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
            slow.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
            def fill():
                try:
                    slow.sendall(payload * 64)
                except OSError:
                    pass
            worker = threading.Thread(target=fill, daemon=True)
            worker.start()
            time.sleep(0.5)
            assert worker.is_alive(), "The slow stream did not reach backpressure"
            started = time.monotonic()
            roundtrip(port, b"independent SSH-like request\x00\xff")
            assert time.monotonic() - started < 3, "Slow reader blocked another stream"
            slow.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
            slow.shutdown(socket.SHUT_RDWR)
        worker.join(timeout=2)
        assert not worker.is_alive()
        roundtrip(port, b"survives reset")
    record("stalled_reader_and_abrupt_reset_isolation", stalled)

    def limit():
        held = []
        try:
            for i in range(8):
                sock = socket.create_connection(("127.0.0.1", port), timeout=10)
                sock.sendall(bytes([i]))
                assert sock.recv(1) == bytes([i])
                held.append(sock)
            with socket.create_connection(("127.0.0.1", port), timeout=10) as extra:
                assert extra.recv(1) == b"", "Ninth TCP connection was accepted"
            for sock in held:
                sock.shutdown(socket.SHUT_WR)
                assert read_all(sock) == TAIL
        finally:
            for sock in held:
                sock.close()
        time.sleep(0.1)
        roundtrip(port, b"slot reuse with fresh stream ID")
    record("eight_connection_limit_and_slot_reuse", limit)


def ssh_checks(port, directory, record, wait_idle, size=262144, timeout=90, *, host_key_alias):
    ssh_options = ["-o", "BatchMode=yes", "-o", "HostKeyAlias=" + host_key_alias,
                   "-o", "StrictHostKeyChecking=yes", "-o", f"ConnectTimeout={min(timeout, 60)}",
                   "-o", "ControlMaster=no", "-o", "ControlPath=none"]
    ssh = ["ssh", *ssh_options, "-p", str(port), "pi@127.0.0.1"]
    scp = ["scp", *ssh_options, "-P", str(port)]

    def run(command, **kwargs):
        try:
            return subprocess.run(command, check=True, capture_output=True, timeout=timeout, **kwargs)
        except subprocess.CalledProcessError as error:
            detail = error.stderr.decode(errors="replace").strip()
            raise RuntimeError(f"SSH command exited {error.returncode}: {detail}") from error

    remote = run(ssh + ["mktemp -d /tmp/pilink-mux-test.XXXXXXXX"]).stdout.decode().strip()
    assert remote.startswith("/tmp/pilink-mux-test.") and "/" not in remote[len("/tmp/"):]
    local = directory / "binary.dat"
    local.write_bytes(bytes((i * 37 + 11) % 256 for i in range(size)))
    try:
        def concurrent_scp():
            # Keep an authenticated console open throughout both file transfers.
            console = subprocess.Popen(ssh + ["printf 'CONSOLE_READY\\n'; read release; printf CONSOLE_OK"],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
                    ready = pool.submit(console.stdout.readline)
                    try:
                        assert ready.result(timeout=timeout) == b"CONSOLE_READY\n"
                    except BaseException:
                        console.kill()
                        console.wait(timeout=10)
                        raise
                    upload = pool.submit(run, scp + [str(local), f"pi@127.0.0.1:{remote}/binary.dat"])
                    command = pool.submit(run, ssh + ["printf SECOND_SSH_OK"])
                    upload.result()
                    assert command.result().stdout == b"SECOND_SSH_OK"
                downloaded = directory / "download.dat"
                run(scp + [f"pi@127.0.0.1:{remote}/binary.dat", str(downloaded)])
                assert downloaded.read_bytes() == local.read_bytes()
                assert console.poll() is None, "Console disconnected during SCP"
                output, error = console.communicate(input=b"release\n", timeout=timeout)
                assert console.returncode == 0 and output == b"CONSOLE_OK", error.decode(errors="replace")
            finally:
                if console.poll() is None:
                    console.terminate()
                    console.communicate(timeout=10)
        record(f"real_pi_parallel_ssh_scp_{size // 1024}KiB_roundtrip", concurrent_scp)
        wait_idle()

        def parallel_ssh():
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                futures = [pool.submit(run, ssh + [f"printf STREAM_{i}; sleep 1"]) for i in range(8)]
                assert [f.result().stdout for f in futures] == [f"STREAM_{i}".encode() for i in range(8)]
        record("real_pi_eight_parallel_openssh_sessions", parallel_ssh)
        wait_idle()

        def sftp():
            batch = f"put {local} {remote}/sftp.dat\nget {remote}/sftp.dat {directory}/sftp.dat\nquit\n"
            run(["sftp", *ssh_options, "-P", str(port), "-b", "-", "pi@127.0.0.1"], input=batch.encode())
            assert (directory / "sftp.dat").read_bytes() == local.read_bytes()
        record("real_pi_sftp_binary_roundtrip", sftp)
        wait_idle()

        def pty():
            result = subprocess.run(ssh[:-1] + ["-tt", ssh[-1], "printf PTY_OK; exit 7"],
                                    capture_output=True, timeout=timeout)
            assert result.returncode == 7 and b"PTY_OK" in result.stdout
        record("real_pi_pty_exit_status", pty)
        wait_idle()

        def limit_and_reset():
            held = []
            try:
                for _ in range(8):
                    sock = socket.create_connection(("127.0.0.1", port), timeout=min(timeout, 60))
                    held.append(sock)
                    banner = bytearray()
                    while b"\n" not in banner and len(banner) < 1024:
                        data = sock.recv(256)
                        assert data, "SSH connection closed before its banner"
                        banner.extend(data)
                    assert banner.startswith(b"SSH-2.0-"), "Unexpected SSH server banner"
                with socket.create_connection(("127.0.0.1", port), timeout=10) as extra:
                    assert extra.recv(1) == b"", "Ninth TCP connection was accepted"
                # Abort one stream while seven others still occupy their slots.
                reset = held.pop()
                try:
                    reset.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                finally:
                    reset.close()
                time.sleep(0.5)
                assert run(ssh + ["printf RESET_ISOLATION_OK"]).stdout == b"RESET_ISOLATION_OK"
            finally:
                for sock in held:
                    sock.close()
            wait_idle()
            assert run(ssh + ["printf SLOT_REUSE_OK"]).stdout == b"SLOT_REUSE_OK"
        record("real_pi_eight_limit_reset_isolation_and_slot_reuse", limit_and_reset)
        wait_idle()
    finally:
        run(ssh + [f"rm -rf -- {remote}"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    transport = parser.add_mutually_exclusive_group()
    transport.add_argument("--wire-port", type=int, help="Existing C test server through loopback SSH forwarding")
    transport.add_argument("--proxy-port", type=int, help="Already-running Mac/Android multiplex SSH proxy on localhost")
    parser.add_argument("--proxy-log", type=Path, help="Optional proxy log used to wait for completed FIN processing")
    parser.add_argument("--size", type=int, help="SSH binary roundtrip bytes (default 64 KiB for external proxy; 256 KiB otherwise)")
    parser.add_argument("--timeout", type=int, help="SSH command timeout (default 300s for external proxy; 90s otherwise)")
    parser.add_argument("--ssh", action="store_true", help="Use Pi OpenSSH instead of the local echo endpoint")
    parser.add_argument("--host-key-alias", help="Required when using --ssh")
    parser.add_argument("--output", type=Path, default=ROOT / "build/multiplex-results.json")
    args = parser.parse_args()
    if args.ssh and not (args.wire_port or args.proxy_port):
        parser.error("--ssh requires --wire-port or --proxy-port")
    if args.ssh and not args.host_key_alias:
        parser.error("--ssh requires --host-key-alias")
    if args.proxy_port and not args.ssh:
        parser.error("--proxy-port requires --ssh")
    if any(p is not None and not 1 <= p <= 65535 for p in (args.wire_port, args.proxy_port)):
        parser.error("Ports must be in 1..65535")
    size = args.size if args.size is not None else (65536 if args.proxy_port else 262144)
    timeout = args.timeout if args.timeout is not None else (300 if args.proxy_port else 90)
    if not 1024 <= size <= 1048576 or not 1 <= timeout <= 3600:
        parser.error("Size must be 1024..1048576 and timeout 1..3600")
    results = []
    def record(name, action):
        started = time.monotonic()
        action()
        result = {"case": name, "status": "PASS", "seconds": round(time.monotonic() - started, 3)}
        results.append(result)
        print(json.dumps(result), flush=True)
    bin_path = None
    if not args.proxy_port:
        bin_path = Path(subprocess.check_output(["swift", "build", "--package-path", str(ROOT / "macos"),
                                                "--show-bin-path"], text=True).strip())
    with tempfile.TemporaryDirectory(prefix="pilink-mux-") as temp:
        directory = Path(temp)
        processes, logs = [], []
        echo = None
        try:
            wire_port = args.wire_port or free_port()
            if not (args.wire_port or args.proxy_port):
                echo = EchoServer(("127.0.0.1", 0), EchoHandler)
                threading.Thread(target=echo.serve_forever, daemon=True).start()
                log = directory / "server.log"
                handle = log.open("w")
                logs.append(handle)
                server = subprocess.Popen([str(ROOT / "build/local/mux_test_server"),
                                           str(wire_port), str(echo.server_address[1])], stdout=handle, stderr=handle)
                processes.append(server)
                wait_ready(server, log, "READY")
            local_port = args.proxy_port or free_port()
            log = args.proxy_log or directory / "proxy.log"
            proxy = None
            if not args.proxy_port:
                handle = log.open("w")
                logs.append(handle)
                proxy = subprocess.Popen([str(bin_path / "pilink-mux-test"), str(wire_port), str(local_port)],
                                         stdout=handle, stderr=handle)
                processes.append(proxy)
                wait_ready(proxy, log, "READY")
            if args.ssh:
                def wait_idle():
                    if args.proxy_port and not args.proxy_log:
                        time.sleep(0.5)
                        return
                    deadline = time.monotonic() + 15
                    while time.monotonic() < deadline:
                        active = set()
                        last_count = None
                        for line in log.read_text().splitlines():
                            opened = re.search(r"接続: stream=(\d+)", line)
                            ended = re.search(r"終了: stream=(\d+)", line)
                            if opened:
                                active.add(opened.group(1))
                            if ended:
                                active.discard(ended.group(1))
                            count = re.search(r"MUX connections=(\d+)", line)
                            if count:
                                last_count = int(count.group(1))
                        if not active and last_count in (None, 0):
                            return
                        if proxy is not None and proxy.poll() is not None:
                            raise RuntimeError(log.read_text())
                        time.sleep(0.02)
                    raise TimeoutError("SSH process exited but multiplex FIN was not drained")
                ssh_checks(local_port, directory, record, wait_idle, size, timeout,
                           host_key_alias=args.host_key_alias)
            else:
                local_checks(local_port, record)
                def loss():
                    with socket.create_connection(("127.0.0.1", local_port), timeout=10) as sock:
                        sock.sendall(b"live")
                        assert sock.recv(4) == b"live"
                        server.terminate()
                        assert server.wait(timeout=10) == 0
                        assert proxy.wait(timeout=10) == 1
                        assert sock.recv(1) == b"", "TCP remained open after wire loss"
                record("transport_loss_closes_all_streams", loss)
            assert proxy is None or proxy.poll() is None or not args.ssh
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=10)
            for handle in logs:
                handle.close()
            if echo:
                echo.shutdown()
                echo.server_close()
            ROOT.joinpath("build").mkdir(exist_ok=True)
            ROOT.joinpath("build/multiplex-last-proxy.log").write_text(log.read_text() if log.exists() else "")
            server_log = directory / "server.log"
            if server_log.exists():
                ROOT.joinpath("build/multiplex-last-server.log").write_text(server_log.read_text())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"transport": "external proxy (caller-controlled transport)" if args.proxy_port
                                      else "loopback TCP substitution; BLE not exercised",
                                      "endpoint": "Pi OpenSSH" if args.ssh else "local binary echo",
                                      "results": results}, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
