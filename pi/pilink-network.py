#!/usr/bin/env python3
"""Supervise PiLink BLE and manage its ephemeral NetworkManager TUN profile."""

import argparse
import ctypes
import errno
import fcntl
import json
import logging
import os
from pathlib import Path
import signal
import selectors
import socket
import stat
import struct
import subprocess
import threading
import time
import uuid


PROFILE_UUID = "c7cf1339-9037-4d4a-aa55-6af99efa034b"
INTERFACE = "tun0"
IPV4 = "198.18.0.1/30"
IPV6 = "fd00:7069:6c69:6e6b::1/128"
DNS = "198.18.0.2"
IN_ATTRIB = 0x00000004
IN_CLOSE_WRITE = 0x00000008
IN_MOVED_FROM = 0x00000040
IN_MOVED_TO = 0x00000080
IN_CREATE = 0x00000100
IN_DELETE = 0x00000200
IN_DELETE_SELF = 0x00000400
IN_MOVE_SELF = 0x00000800
IN_Q_OVERFLOW = 0x00004000
IN_IGNORED = 0x00008000


class StopEvent(threading.Event):
    def __init__(self):
        super().__init__()
        self.wakeup = None

    def set(self):
        super().set()
        if self.wakeup is not None:
            self.wakeup()


class NetworkEvents:
    """Wait for Linux file, process, link and NetworkManager notifications."""

    def __init__(self, state_file, stop):
        self.state_file = state_file
        self.stop = stop
        self.selector = selectors.DefaultSelector()
        self.control = self.sender = self.route = None
        self.inotify = -1
        self.monitor = None
        self.monitor_retry = 0
        self.processes = {}
        self.exited = set()
        try:
            if not hasattr(os, "pidfd_open"):
                raise RuntimeError("Network supervision requires Python 3.9+ and Linux 5.3+.")
            self.control, self.sender = socket.socketpair()
            self.control.setblocking(False)
            self.sender.setblocking(False)
            self.selector.register(self.control, selectors.EVENT_READ, "stop")
            self.stop.wakeup = self.wake
            libc = ctypes.CDLL(None, use_errno=True)
            libc.inotify_init1.argtypes = [ctypes.c_int]
            libc.inotify_init1.restype = ctypes.c_int
            libc.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
            libc.inotify_add_watch.restype = ctypes.c_int
            self.inotify = libc.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC)
            if self.inotify < 0:
                raise OSError(ctypes.get_errno(), "inotify_init1")
            # Watch the directory because pilinkd atomically replaces the state file.
            mask = (IN_ATTRIB | IN_CLOSE_WRITE | IN_MOVED_FROM | IN_MOVED_TO | IN_CREATE |
                    IN_DELETE | IN_DELETE_SELF | IN_MOVE_SELF)
            if libc.inotify_add_watch(self.inotify, os.fsencode(state_file.parent), mask) < 0:
                raise OSError(ctypes.get_errno(), "inotify_add_watch")
            self.selector.register(self.inotify, selectors.EVENT_READ, "file")
            self.route = socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, socket.NETLINK_ROUTE)
            self.route.setblocking(False)
            self.route.bind((0, 1))  # RTMGRP_LINK includes TUN carrier changes.
            self.selector.register(self.route, selectors.EVENT_READ, "route")
            self.start_monitor()
        except BaseException:
            self.close()
            raise

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def wake(self):
        try:
            self.sender.send(b"\0")
        except (BlockingIOError, OSError):
            pass

    def unwatch_process(self, tag):
        previous = self.processes.pop(tag, None)
        if previous is not None:
            self.selector.unregister(previous[1])
            os.close(previous[1])

    def watch_process(self, tag, pid):
        previous = self.processes.get(tag)
        if previous is not None and previous[0] == pid:
            return
        self.unwatch_process(tag)
        if pid is None:
            return
        try:
            fd = os.pidfd_open(pid)
        except OSError as error:
            if error.errno != errno.ESRCH:
                raise
            self.exited.add(tag)
            return
        self.processes[tag] = (pid, fd)
        self.selector.register(fd, selectors.EVENT_READ, ("process", tag))

    def start_monitor(self):
        environment = dict(os.environ, LC_ALL="C")
        self.monitor = subprocess.Popen(["nmcli", "monitor"], stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, env=environment)
        os.set_blocking(self.monitor.stdout.fileno(), False)
        self.selector.register(self.monitor.stdout, selectors.EVENT_READ, "network")
        self.watch_process("monitor", self.monitor.pid)
        self.monitor_retry = 0

    def stop_monitor(self):
        self.unwatch_process("monitor")
        if self.monitor is not None:
            try:
                self.selector.unregister(self.monitor.stdout)
            except KeyError:
                pass
            self.monitor.stdout.close()
            if self.monitor.poll() is None:
                self.monitor.terminate()
            try:
                self.monitor.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.monitor.kill()
                self.monitor.wait(timeout=3)
            self.monitor = None

    def file_events(self):
        changed = False
        while True:
            try:
                data = os.read(self.inotify, 65536)
            except BlockingIOError:
                break
            offset = 0
            while offset < len(data):
                _, mask, _, length = struct.unpack_from("iIII", data, offset)
                name = data[offset + 16:offset + 16 + length].split(b"\0", 1)[0]
                offset += 16 + length
                if mask & (IN_DELETE_SELF | IN_MOVE_SELF | IN_IGNORED):
                    raise RuntimeError("BLE state directory notification watch was lost.")
                if name == os.fsencode(self.state_file.name) or mask & IN_Q_OVERFLOW:
                    changed = True
        return changed

    def wait(self, timeout=None):
        if self.stop.is_set():
            return {"stop"}
        if self.exited:
            result, self.exited = self.exited, set()
            return result
        if self.monitor_retry:
            remaining = max(0, self.monitor_retry - time.monotonic())
            timeout = remaining if timeout is None else min(timeout, remaining)
        result = set()
        for key, _ in self.selector.select(timeout):
            if key.data == "stop":
                while True:
                    try:
                        self.control.recv(4096)
                    except BlockingIOError:
                        break
                result.add("stop")
            elif key.data == "file":
                if self.file_events():
                    result.add("link")
            elif key.data == "route":
                while True:
                    try:
                        self.route.recv(65536)
                    except BlockingIOError:
                        break
                result.add("network")
            elif key.data == "network":
                if self.monitor is None or key.fileobj is not self.monitor.stdout:
                    continue
                try:
                    data = os.read(key.fd, 65536)
                except BlockingIOError:
                    continue
                if not data:
                    self.stop_monitor()
                    self.monitor_retry = time.monotonic() + 5
                result.add("network")
            else:
                _, tag = key.data
                previous = self.processes.get(tag)
                if previous is None or previous[1] != key.fd:
                    continue
                self.unwatch_process(tag)
                result.add(tag)
                if tag == "monitor":
                    self.stop_monitor()
                    self.monitor_retry = time.monotonic() + 5
                    result.add("network")
        if self.monitor_retry and time.monotonic() >= self.monitor_retry:
            self.start_monitor()
            result.add("network")
        return result

    def close(self):
        self.stop.wakeup = None
        self.stop_monitor()
        for tag in list(self.processes):
            self.unwatch_process(tag)
        self.selector.close()
        for stream in (self.control, self.sender, self.route):
            if stream is not None:
                stream.close()
        if self.inotify >= 0:
            os.close(self.inotify)
            self.inotify = -1


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
        self.events = None

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
        self.events.watch_process("engine", self.engine.pid)
        deadline = time.monotonic() + 5
        while True:
            self.current(link)
            if self.engine.poll() is not None:
                raise RuntimeError("hev exited during startup.")
            interfaces = json.loads(run_command("ip", "-j", "link", "show", "dev", INTERFACE).stdout)
            if "LOWER_UP" in interfaces[0]["flags"]:
                break
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError("TUN engine readiness timed out.")
            self.events.wait(remaining)
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
        self.status(active=True)
        logging.info("TUN active for BLE session %s", link["session"])

    def healthy(self):
        if self.engine is None or self.engine.poll() is not None:
            return False
        result = self.nm("-g", "GENERAL.CON-UUID", "device", "show", INTERFACE, check=False)
        return result.returncode == 0 and result.stdout.strip() == PROFILE_UUID

    def serve(self, daemon=None):
        self.cleanup()
        retry_at = 0
        with NetworkEvents(self.state_file, self.stop) as events:
            self.events = events
            events.watch_process("daemon", None if daemon is None else daemon.pid)
            try:
                while not self.stop.is_set():
                    if daemon is not None and daemon.poll() is not None:
                        raise RuntimeError(f"BLE daemon exited with status {daemon.returncode}.")
                    link = read_link(self.state_file)
                    events.watch_process("link", None if link is None else link["pid"])
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
                    events.watch_process("engine", None if self.engine is None else self.engine.pid)
                    timeout = max(0, retry_at - time.monotonic()) if link is not None and self.session is None else None
                    events.wait(timeout)
            finally:
                self.cleanup()
                self.events = None


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
        stop = StopEvent()
        signal.signal(signal.SIGTERM, lambda *_: stop.set())
        signal.signal(signal.SIGINT, lambda *_: stop.set())
        network = Network(args.state_file, args.runtime, args.binary, stop)
        daemon = None
        try:
            if args.action == "cleanup":
                network.cleanup()
            else:
                args.state_file.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
                if args.action == "service":
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
