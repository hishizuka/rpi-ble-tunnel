#!/usr/bin/env python3
"""Supervise PiLink BLE and manage its ephemeral NetworkManager TUN profile."""

import argparse
import fcntl
import json
import logging
import os
from pathlib import Path
import signal
import stat
import subprocess
import threading
import time
import uuid


PROFILE_UUID = "c7cf1339-9037-4d4a-aa55-6af99efa034b"
INTERFACE = "tun0"
IPV4 = "198.18.0.1/30"
IPV6 = "fd00:7069:6c69:6e6b::1/128"
DNS = "198.18.0.2"


def process_ticks(pid):
    try:
        text = Path(f"/proc/{pid}/stat").read_text()
        fields = text.rsplit(")", 1)[1].split()
        if fields[0] == "Z":
            return None
        return int(fields[19])
    except (OSError, ValueError, IndexError):
        return None


def read_link(path):
    try:
        metadata = path.stat(follow_symlinks=False)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != 0 or metadata.st_mode & 0o022:
            return None
        with path.open() as handle:
            data = json.load(handle)
        if data.get("connected") is not True:
            return None
        pid, ticks, port = data["pid"], data["start_ticks"], data["socks_port"]
        if any(type(value) is not int for value in (pid, ticks, port)):
            return None
        if pid <= 1 or ticks <= 0 or not 1024 <= port <= 65535:
            return None
        session = str(uuid.UUID(data["session"]))
        if process_ticks(pid) != ticks:
            return None
        return {"pid": pid, "start_ticks": ticks, "session": session, "socks_port": port}
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return None


def run_command(*args, check=True):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=12)
    if check and result.returncode:
        raise RuntimeError(f"{args[0]}: {result.stderr.strip() or result.stdout.strip()}")
    return result


class Network:
    def __init__(self, state_file, runtime, binary, stop):
        self.state_file = state_file
        self.runtime = runtime
        self.binary = binary
        self.stop = stop
        self.engine = None
        self.session = None
        self.last_health = 0

    def nm(self, *args, check=True):
        return run_command("nmcli", "--wait", "8", *args, check=check)

    def profile(self):
        result = self.nm("-g", "connection.id,connection.type,connection.interface-name",
                         "connection", "show", "uuid", PROFILE_UUID, check=False)
        if result.returncode == 10:
            return False
        if result.returncode:
            raise RuntimeError(f"NetworkManager unavailable: {result.stderr.strip()}")
        if result.stdout.strip().splitlines() != ["PiLink", "tun", INTERFACE]:
            raise RuntimeError("The reserved profile UUID belongs to another connection.")
        return True

    def status(self, active=False, error=None):
        data = {"active": active, "interface": INTERFACE,
                "engine_pid": None if self.engine is None else self.engine.pid,
                "session": self.session, "error": error}
        temp = self.runtime / "status.tmp"
        temp.write_text(json.dumps(data) + "\n")
        temp.chmod(0o644)
        temp.replace(self.runtime / "status.json")

    def interface_index(self):
        result = run_command("ip", "-d", "-j", "link", "show", "dev", INTERFACE, check=False)
        if result.returncode:
            return None
        data = json.loads(result.stdout)[0]
        if data.get("linkinfo", {}).get("info_kind") != "tun":
            raise RuntimeError(f"{INTERFACE} is not a TUN device; leaving it untouched.")
        return data["ifindex"]

    def remember_interface(self):
        index = self.interface_index()
        if index is not None:
            temp = self.runtime / "owned.tmp"
            temp.write_text(json.dumps({"ifindex": index}) + "\n")
            temp.replace(self.runtime / "owned.json")

    def cleanup(self):
        error = None
        try:
            if self.profile():
                self.remember_interface()
                self.nm("connection", "delete", "uuid", PROFILE_UUID)
        except (RuntimeError, subprocess.TimeoutExpired) as exception:
            error = exception
        finally:
            if self.engine is not None:
                if self.engine.poll() is None:
                    self.engine.terminate()
                    try:
                        self.engine.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        self.engine.kill()
                        self.engine.wait(timeout=3)
                self.engine = None
            # NM creates a persistent TUN. Removing the profile alone can leave the device behind.
            ownership = self.runtime / "owned.json"
            if ownership.exists():
                try:
                    owned = json.loads(ownership.read_text())["ifindex"]
                    current = self.interface_index()
                    if current is not None:
                        if current != owned:
                            raise RuntimeError("TUN interface identity changed; leaving it untouched.")
                        run_command("ip", "tuntap", "del", "dev", INTERFACE, "mode", "tun")
                    ownership.unlink()
                except (RuntimeError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as exception:
                    error = exception
            self.session = None
            self.status(error=None if error is None else str(error))
        if error:
            raise error

    def current(self, expected):
        if self.stop.is_set() or read_link(self.state_file) != expected:
            raise RuntimeError("BLE session changed during network setup.")

    def start(self, link):
        if self.profile():
            raise RuntimeError("A previous PiLink profile has not been cleaned up.")
        if run_command("ip", "link", "show", "dev", INTERFACE, check=False).returncode == 0:
            raise RuntimeError(f"{INTERFACE} is already in use; leaving it untouched.")
        self.current(link)
        # Activate addresses first. Default routes and DNS are added after the engine is ready.
        self.nm("connection", "add", "save", "no", "type", "tun", "mode", "tun",
                "ifname", INTERFACE, "con-name", "PiLink", "connection.uuid", PROFILE_UUID,
                "connection.autoconnect", "no", "tun.pi", "no", "tun.multi-queue", "no",
                "ipv4.method", "manual", "ipv4.addresses", IPV4, "ipv4.never-default", "yes",
                "ipv6.method", "manual", "ipv6.addresses", IPV6, "ipv6.never-default", "yes")
        self.remember_interface()
        self.current(link)
        self.nm("connection", "up", "uuid", PROFILE_UUID)
        self.current(link)
        config = self.runtime / "hev.yml"
        config.write_text(f"""tunnel:
  name: {INTERFACE}
  mtu: 1500
  multi-queue: false
  ipv4: {IPV4.split('/')[0]}
  ipv6: '{IPV6.split('/')[0]}'
  icmp: 'off'
socks5:
  address: 127.0.0.1
  port: {link['socks_port']}
  udp: 'udp'
mapdns:
  address: {DNS}
  port: 53
  network: 198.19.0.0
  netmask: 255.255.0.0
  cache-size: 1024
misc:
  task-stack-size: 24576
  tcp-buffer-size: 4096
  max-session-count: 256
  connect-timeout: 65000
  log-file: stdout
  log-level: warn
""")
        self.engine = subprocess.Popen([str(self.binary), str(config)])
        for _ in range(50):
            self.current(link)
            if self.engine.poll() is not None:
                raise RuntimeError("hev exited during startup.")
            interfaces = json.loads(run_command("ip", "-j", "link", "show", "dev", INTERFACE).stdout)
            if "LOWER_UP" in interfaces[0]["flags"]:
                break
            self.stop.wait(0.1)
        else:
            raise RuntimeError("TUN engine readiness timed out.")
        self.current(link)
        self.nm("connection", "modify", "--temporary", "uuid", PROFILE_UUID,
                "ipv4.never-default", "no", "ipv4.routes", "0.0.0.0/0 0.0.0.0 50",
                "ipv4.dns", DNS, "ipv4.dns-priority", "-32768", "ipv4.dns-search", "~.",
                "ipv6.never-default", "no", "ipv6.routes", "::/0 :: 50",
                "ipv6.dns-priority", "-32768")
        self.current(link)
        self.nm("device", "reapply", INTERFACE)
        self.current(link)
        if self.engine.poll() is not None:
            raise RuntimeError("hev exited while applying network settings.")
        self.session = link
        self.last_health = time.monotonic()
        self.status(active=True)
        logging.info("TUN active for BLE session %s", link["session"])

    def healthy(self):
        if self.engine is None or self.engine.poll() is not None:
            return False
        if time.monotonic() - self.last_health < 5:
            return True
        self.last_health = time.monotonic()
        result = self.nm("-g", "GENERAL.CON-UUID", "device", "show", INTERFACE, check=False)
        return result.returncode == 0 and result.stdout.strip() == PROFILE_UUID

    def serve(self, daemon=None):
        self.cleanup()
        retry_at = 0
        try:
            while not self.stop.is_set():
                if daemon is not None and daemon.poll() is not None:
                    raise RuntimeError(f"BLE daemon exited with status {daemon.returncode}.")
                link = read_link(self.state_file)
                try:
                    if self.session is not None and (link != self.session or not self.healthy()):
                        self.cleanup()
                        logging.info("TUN removed; NetworkManager restored the other connections.")
                    if link is not None and self.session is None and time.monotonic() >= retry_at:
                        self.start(link)
                except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
                    logging.error("Network setup: %s", error)
                    try:
                        self.cleanup()
                    except (RuntimeError, OSError, subprocess.TimeoutExpired) as cleanup_error:
                        logging.error("Network cleanup: %s", cleanup_error)
                    self.status(error=str(error))
                    retry_at = time.monotonic() + 5
                self.stop.wait(0.5)
        finally:
            self.cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("service", "run", "cleanup", "status"))
    parser.add_argument("--state-file", type=Path, default=Path("/run/pilink/link.json"))
    parser.add_argument("--runtime", type=Path, default=Path("/run/pilink-network"))
    parser.add_argument("--binary", type=Path, default=Path("/usr/local/libexec/hev-socks5-tunnel"))
    parser.add_argument("--daemon", type=Path, default=Path("/usr/local/bin/pilinkd"))
    parser.add_argument("--adapter", default="hci0")
    parser.add_argument("--mode", choices=("internet", "mux", "ssh", "echo"), default="internet")
    args = parser.parse_args()
    if args.action == "status":
        print((args.runtime / "status.json").read_text(), end="")
        return
    if os.geteuid() != 0:
        parser.error("Network management requires root.")
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    args.runtime.mkdir(mode=0o755, parents=True, exist_ok=True)
    with (args.runtime / "lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        stop = threading.Event()
        signal.signal(signal.SIGTERM, lambda *_: stop.set())
        signal.signal(signal.SIGINT, lambda *_: stop.set())
        network = Network(args.state_file, args.runtime, args.binary, stop)
        daemon = None
        try:
            if args.action == "cleanup":
                network.cleanup()
            else:
                if args.action == "service":
                    args.state_file.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
                    daemon = subprocess.Popen([str(args.daemon), "--adapter", args.adapter,
                                               "--mode", args.mode, "--state-file", str(args.state_file)])
                    logging.info("BLE daemon started (PID %s)", daemon.pid)
                network.serve(daemon)
        finally:
            if daemon is not None:
                if daemon.poll() is None:
                    daemon.terminate()
                    try:
                        daemon.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        daemon.kill()
                        daemon.wait(timeout=3)


if __name__ == "__main__":
    main()
