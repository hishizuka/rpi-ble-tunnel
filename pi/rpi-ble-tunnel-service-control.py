#!/usr/bin/python3
"""Control a caller-managed rpi-ble-tunnel service without application dependencies."""

import argparse
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile

UNIT = "rpi-ble-tunneld.service"
PARAMETERS = Path("/run/rpi-ble-tunnel-control/adapter.env")
DROP_IN = Path("/etc/systemd/system/rpi-ble-tunneld.service.d/20-caller-adapter.conf")
LOCK = Path("/run/lock/rpi-ble-tunnel-control.lock")


class ControlError(RuntimeError):
    def __init__(self, reason, message):
        super().__init__(message)
        self.reason = reason


def run(*args, timeout=10):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        detail = result.stderr.strip() or result.stdout.strip()
        reason = (
            "permission_denied"
            if any(
                value in detail
                for value in ("AccessDenied", "NotAuthorized", "Access denied")
            )
            else "command_failed"
        )
        raise ControlError(reason, detail or f"{args[0]} failed")
    return result.stdout.strip()


def status():
    values = dict(
        line.split("=", 1)
        for line in run(
            "systemctl",
            "show",
            UNIT,
            "--property=LoadState,UnitFileState,ActiveState,SubState,MainPID,Job",
        ).splitlines()
        if "=" in line
    )
    job = values.get("Job", "").split()
    adapter = None
    pid = int(values.get("MainPID", "0"))
    if pid:
        try:
            args = Path(f"/proc/{pid}/cmdline").read_bytes().decode().split("\0")
            if "--adapter" in args:
                adapter = args[args.index("--adapter") + 1]
        except (OSError, UnicodeError, IndexError):
            pass
    return {
        "installed": values.get("LoadState") == "loaded" and DROP_IN.is_file(),
        "state": values.get("ActiveState"),
        "adapter": adapter,
        "enabled": values.get("UnitFileState") in ("enabled", "enabled-runtime"),
        "job": bool(job and job[0] != "0"),
    }


def property_value(adapter, name):
    output = run(
        "busctl",
        "--system",
        "get-property",
        "org.bluez",
        f"/org/bluez/{adapter}",
        "org.bluez.Adapter1",
        name,
    )
    return shlex.split(output)[1]


def prepare(adapter):
    if not re.fullmatch(r"hci[0-9]+", adapter):
        raise ControlError(
            "invalid_adapter", "Expected hci followed by a controller number"
        )
    if not Path(f"/sys/class/bluetooth/{adapter}").exists():
        raise ControlError("adapter_unavailable", f"{adapter} is not present")
    devices = json.loads(run("rfkill", "--json"))
    for device in devices.get("rfkilldevices", devices.get("", [])):
        if device["type"] == "bluetooth" and device["device"] == adapter:
            if device["soft"] != "unblocked" or device["hard"] != "unblocked":
                raise ControlError(
                    "adapter_unavailable", f"{adapter} is blocked by rfkill"
                )
    try:
        interfaces = run(
            "busctl", "--system", "introspect", "org.bluez", f"/org/bluez/{adapter}"
        )
        if not all(
            name in interfaces
            for name in ("org.bluez.GattManager1", "org.bluez.LEAdvertisingManager1")
        ):
            raise ControlError(
                "adapter_unavailable",
                f"{adapter} cannot provide BLE peripheral services",
            )
        if property_value(adapter, "Powered") != "true":
            try:
                run(
                    "busctl",
                    "--system",
                    "set-property",
                    "org.bluez",
                    f"/org/bluez/{adapter}",
                    "org.bluez.Adapter1",
                    "Powered",
                    "b",
                    "true",
                )
            except ControlError as error:
                if error.reason == "permission_denied":
                    raise
                raise ControlError("adapter_unavailable", str(error)) from error
            except subprocess.TimeoutExpired as error:
                raise ControlError(
                    "adapter_unavailable", "Controller power preparation timed out"
                ) from error
        if property_value(adapter, "Powered") != "true":
            raise ControlError("adapter_unavailable", f"{adapter} did not power on")
        address = property_value(adapter, "Address").upper()
        if not re.fullmatch(r"(?:[0-9A-F]{2}:){5}[0-9A-F]{2}", address):
            raise ControlError("command_failed", "Invalid controller address")
        return address
    except ControlError as error:
        if any(
            value in str(error)
            for value in ("UnknownObject", "Unknown object", "No such object")
        ):
            raise ControlError("adapter_unavailable", str(error)) from error
        raise


def publish(adapter, address):
    PARAMETERS.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", dir=PARAMETERS.parent, delete=False
        ) as stream:
            temporary = Path(stream.name)
            stream.write(
                f"RPI_BLE_TUNNEL_ADAPTER={adapter}\nRPI_BLE_TUNNEL_ADAPTER_ADDRESS={address}\n"
            )
            os.fchmod(stream.fileno(), 0o644)
        temporary.replace(PARAMETERS)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


@contextmanager
def mutation_lock():
    with LOCK.open("w") as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise ControlError("busy", "Another rpi-ble-tunnel operation is running") from error
        yield


def control(action, adapter=None):
    current = status()
    if not current["installed"]:
        raise ControlError(
            "not_installed", "rpi-ble-tunnel caller-managed mode is not installed"
        )
    if current["job"]:
        raise ControlError("busy", "A systemd operation is still running")
    if action == "apply":
        # Finish preparation before stopping a working tunnel.
        address = prepare(adapter)
        expected = f"RPI_BLE_TUNNEL_ADAPTER={adapter}\nRPI_BLE_TUNNEL_ADAPTER_ADDRESS={address}\n"
        if (
            current["state"] == "active"
            and current["adapter"] == adapter
            and PARAMETERS.is_file()
            and PARAMETERS.read_text() == expected
        ):
            run("systemctl", "enable", UNIT)
            return
        run("systemctl", "stop", UNIT, timeout=55)
        publish(adapter, address)
        run("systemctl", "enable", UNIT)
        run("systemctl", "reset-failed", UNIT)
        run("systemctl", "start", UNIT, timeout=20)
    elif action == "disable":
        run("systemctl", "disable", UNIT)
        run("systemctl", "stop", UNIT, timeout=55)
        PARAMETERS.unlink(missing_ok=True)
    elif action == "stop":
        run("systemctl", "stop", UNIT, timeout=55)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    for action in ("apply", "validate"):
        command = commands.add_parser(action)
        command.add_argument("--adapter", required=True)
        if action == "validate":
            command.add_argument("--address", required=True)
    for action in ("disable", "stop", "status"):
        commands.add_parser(action)
    args = parser.parse_args()
    result = {"ok": True, "reason": None, "message": ""}
    try:
        if args.action != "status" and os.geteuid() != 0:
            raise ControlError("permission_denied", "Administrator access is required")
        if args.action == "validate":
            # ExecStartPre must not wait on the caller's mutation lock.
            if not re.fullmatch(r"hci[0-9]+", args.adapter):
                raise ControlError("invalid_adapter", "Invalid controller name")
            if property_value(args.adapter, "Address").upper() != args.address.upper():
                raise ControlError(
                    "identity_mismatch", "The controller identity changed"
                )
            prepare(args.adapter)
        elif args.action != "status":
            with mutation_lock():
                control(args.action, args.adapter if args.action == "apply" else None)
    except (ControlError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        result.update(
            ok=False,
            reason=(
                error.reason if isinstance(error, ControlError) else "command_failed"
            ),
            message=str(error),
        )
    try:
        result.update(status())
        if args.action == "apply" and result["ok"] and result["state"] == "failed":
            result.update(
                ok=False,
                reason="start_failed",
                message="rpi-ble-tunnel service failed during startup",
            )
    except (ControlError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        result.update(
            installed=False, state=None, adapter=None, enabled=None, job=False
        )
        if result["ok"]:
            result.update(ok=False, reason="status_unavailable", message=str(error))
    print(json.dumps(result))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
