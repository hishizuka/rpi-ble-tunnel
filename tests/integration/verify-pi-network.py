#!/usr/bin/env python3
"""Verify the real NetworkManager lifecycle in private network/mount namespaces."""

import argparse
import asyncio
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import struct
import subprocess
import time
import uuid


BODY = b"PiLink isolated NetworkManager test\n" * 1024


def command(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


async def wait_for(predicate, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        await asyncio.sleep(0.1)
    raise TimeoutError("Condition timed out")


def status(runtime):
    try:
        return json.loads((runtime / "status.json").read_text())
    except (OSError, ValueError):
        return {}


def request(host, port):
    connection = http.client.HTTPConnection(host, port, timeout=10)
    try:
        connection.request("GET", "/probe")
        response = connection.getresponse()
        body = response.read()
        assert response.status == 200 and body == BODY
        return {"host": host, "bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()}
    finally:
        connection.close()


def snapshot():
    return {"ipv4": json.loads(command("ip", "-4", "-j", "route")),
            "ipv6": json.loads(command("ip", "-6", "-j", "route")),
            "dns": Path("/etc/resolv.conf").read_text()}


async def verify(args):
    assert os.geteuid() == 0
    for kind in ("net", "mnt", "uts"):
        assert os.readlink(f"/proc/self/ns/{kind}") != os.readlink(f"/proc/1/ns/{kind}"), kind
    # These mounts are intentionally reachable only from the verified private namespace.
    work = args.work.resolve()
    work.mkdir(mode=0o700, parents=True, exist_ok=False)
    etc = work / "etc"
    etc.mkdir()
    for name in ("nsswitch.conf", "passwd", "group", "hosts", "services", "protocols"):
        shutil.copyfile(Path("/etc") / name, etc / name)
    (etc / "resolv.conf").write_text("# isolated test\n")
    (etc / "NetworkManager/conf.d").mkdir(parents=True)
    (etc / "NetworkManager/system-connections").mkdir()
    (etc / "machine-id").write_text("c8e65f9d6e69480c8e3cffdf4c34db87\n")
    command("mount", "--bind", str(etc), "/etc")
    for target in ("/run/NetworkManager", "/var/lib/NetworkManager", "/run/netplan", "/run/systemd/network"):
        if not Path(target).exists():
            continue
        private = work / target.strip("/").replace("/", "-")
        private.mkdir()
        command("mount", "--bind", str(private), target)
    # Remount sysfs with the private network namespace's visibility.
    command("mount", "-t", "sysfs", "sysfs", "/sys")
    command("ip", "link", "set", "lo", "up")
    bus_config = work / "bus.conf"
    bus_socket = work / "bus.sock"
    bus_config.write_text(f"""<busconfig><type>system</type><listen>unix:path={bus_socket}</listen>
<policy context="default"><allow user="root"/><allow own="*"/><allow send_destination="*"/>
<allow receive_sender="*"/></policy></busconfig>""")
    os.environ["DBUS_SYSTEM_BUS_ADDRESS"] = f"unix:path={bus_socket}"
    nm_config = work / "nm.conf"
    nm_config.write_text("[main]\nplugins=keyfile\ndns=default\nrc-manager=file\n")
    empty = work / "empty"
    empty.mkdir()
    processes, handles = [], []
    records, errors, tasks = [], [], set()
    active, peak_active = 0, 0
    result = {"status": "FAIL", "upstream": "isolated SOCKS5 stub", "checks": {}}
    runtime = work / "helper"
    state_file = work / "link.json"
    spec = importlib.util.spec_from_file_location("network", args.helper)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    def start(command_args, name):
        handle = (work / name).open("w")
        handles.append(handle)
        process = subprocess.Popen(command_args, stdout=handle, stderr=handle)
        processes.append(process)
        return process

    def nm(*nm_args):
        return command("nmcli", "--wait", "8", *nm_args)

    def link(connected, stale=False):
        data = {"connected": connected, "pid": os.getpid(),
                "start_ticks": module.process_ticks(os.getpid()) + (1 if stale else 0),
                "session": str(uuid.uuid4()), "socks_port": 11080}
        temp = state_file.with_suffix(".tmp")
        temp.write_text(json.dumps(data))
        temp.chmod(0o644)
        temp.replace(state_file)
        return data

    async def socks(reader, writer):
        nonlocal active, peak_active
        task = asyncio.current_task()
        tasks.add(task)
        try:
            version, count = await reader.readexactly(2)
            assert version == 5 and 0 in await reader.readexactly(count)
            writer.write(b"\x05\x00")
            await writer.drain()
            version, method, reserved, atyp = await reader.readexactly(4)
            assert version == 5 and method == 1 and reserved == 0
            if atyp == 3:
                length = (await reader.readexactly(1))[0]
                host = (await reader.readexactly(length)).decode("ascii")
            elif atyp in (1, 4):
                family = socket.AF_INET if atyp == 1 else socket.AF_INET6
                host = socket.inet_ntop(family, await reader.readexactly(4 if atyp == 1 else 16))
            else:
                raise AssertionError(atyp)
            port = struct.unpack("!H", await reader.readexactly(2))[0]
            records.append({"host": host, "port": port, "atyp": atyp})
            writer.write(b"\x05\x00\x00\x01\x7f\x00\x00\x01\x2b\x48")
            await writer.drain()
            headers = await reader.readuntil(b"\r\n\r\n")
            assert headers.startswith(b"GET /probe HTTP/1.1\r\n")
            active += 1
            peak_active = max(peak_active, active)
            try:
                await asyncio.sleep(0.2)
                writer.write(b"HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: "
                             + str(len(BODY)).encode() + b"\r\n\r\n" + BODY)
                await writer.drain()
            finally:
                active -= 1
        except Exception as error:
            errors.append(repr(error))
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except ConnectionError:
                pass
            tasks.discard(task)

    server = await asyncio.start_server(socks, "127.0.0.1", 11080)
    try:
        start(["dbus-daemon", "--nofork", f"--config-file={bus_config}"], "dbus.log")
        await wait_for(bus_socket.exists)
        nm_process = start(["/usr/sbin/NetworkManager", "--debug", f"--config={nm_config}",
               f"--config-dir={empty}", f"--system-config-dir={empty}",
               f"--intern-config={work / 'intern.conf'}", f"--pid-file={work / 'nm.pid'}",
               f"--state-file={work / 'nm.state'}"], "nm.log")
        def nm_ready():
            if nm_process.poll() is not None:
                raise RuntimeError(f"NetworkManager exited: {nm_process.returncode}")
            return subprocess.run(["nmcli", "general", "status"], stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL).returncode == 0
        await wait_for(nm_ready, timeout=60)
        nm("connection", "add", "save", "no", "type", "dummy", "ifname", "base0",
           "con-name", "Baseline", "ipv4.method", "manual", "ipv4.addresses", "192.0.2.1/24",
           "ipv4.gateway", "192.0.2.254", "ipv4.dns", "192.0.2.53",
           "ipv6.method", "manual", "ipv6.addresses", "fd10::1/64", "ipv6.gateway", "fd10::2",
           "ipv6.dns", "fd10::53")
        nm("connection", "up", "id", "Baseline")
        await asyncio.sleep(0.3)
        baseline = snapshot()
        link(False)
        helper_args = ["python3", str(args.helper.resolve()), "run", "--state-file", str(state_file),
                       "--runtime", str(runtime), "--binary", str(args.binary.resolve())]
        helper = start(helper_args, "helper.log")
        await wait_for(lambda: (runtime / "status.json").exists())
        assert snapshot() == baseline
        result["checks"]["idle_preserves_routes_and_dns"] = True
        link(True)
        running = await wait_for(lambda: status(runtime) if status(runtime).get("active") else None)
        assert "dev tun0" in command("ip", "-4", "route", "get", "203.0.113.10")
        assert "dev tun0" in command("ip", "-6", "route", "get", "2001:db8::10")
        assert "dev base0" in command("ip", "-4", "route", "get", "192.0.2.10")
        nameservers = [line for line in Path("/etc/resolv.conf").read_text().splitlines()
                       if line.startswith("nameserver")]
        assert nameservers == ["nameserver 198.18.0.2"], nameservers
        result["checks"]["ipv4_ipv6_defaults_local_route_and_dns"] = True
        result["http"] = [await asyncio.to_thread(request, "example.invalid", 18080),
                          await asyncio.to_thread(request, "203.0.113.10", 18081),
                          await asyncio.to_thread(request, "2001:db8::10", 18082)]
        result["parallel"] = await asyncio.gather(
            asyncio.to_thread(request, "first.invalid", 18083),
            asyncio.to_thread(request, "second.invalid", 18084))
        assert peak_active >= 2 and not errors
        result["checks"]["normal_http_dns_ipv4_ipv6_parallel"] = True
        link(False)
        await wait_for(lambda: not status(runtime).get("active") and snapshot() == baseline)
        assert subprocess.run(["ip", "link", "show", "tun0"], stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL).returncode != 0
        result["checks"]["disconnect_restores_routes_dns_and_removes_tun"] = True
        link(True)
        running = await wait_for(lambda: status(runtime) if status(runtime).get("active") else None)
        result["checks"]["reconnect"] = True
        old_pid = running["engine_pid"]
        os.kill(old_pid, signal.SIGKILL)
        await wait_for(lambda: not status(runtime).get("active") and snapshot() == baseline)
        await wait_for(lambda: status(runtime).get("active") and status(runtime).get("engine_pid") != old_pid)
        result["checks"]["engine_crash_restores_and_recovers"] = True
        link(True, stale=True)
        await wait_for(lambda: not status(runtime).get("active") and snapshot() == baseline)
        result["checks"]["stale_pid_identity_is_rejected"] = True
        link(True)
        await wait_for(lambda: status(runtime).get("active"))
        helper.terminate()
        await asyncio.to_thread(helper.wait, 20)
        assert helper.returncode == 0 and snapshot() == baseline
        result["checks"]["helper_sigterm_restores_routes_and_dns"] = True
        result["socks_requests"] = records
        result["socks_errors"] = errors
        result["peak_active_http"] = peak_active
        result["status"] = "PASS"
    finally:
        # Stop helper/engine before the private NetworkManager and bus.
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    await asyncio.to_thread(process.wait, 20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    await asyncio.to_thread(process.wait)
        server.close()
        await server.wait_closed()
        for task in list(tasks):
            task.cancel()
        await asyncio.gather(*list(tasks), return_exceptions=True)
        for handle in handles:
            handle.close()
        (work / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--helper", type=Path, required=True)
    parser.add_argument("--binary", type=Path, required=True)
    asyncio.run(verify(parser.parse_args()))


if __name__ == "__main__":
    main()
