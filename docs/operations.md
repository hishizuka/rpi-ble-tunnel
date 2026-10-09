# Operations and network management

English (primary) | [日本語](operations.ja.md)

See the [README](../README.md) for standard installation, connection, and stop procedures. This document covers updates, diagnostics, recovery, and alternatives to the default TUN setup.

## Service control and diagnostics

A single `pilinkd.service` controls BLE and network management on the Pi. Run on the Pi.

```bash
sudo systemctl start pilinkd.service
sudo systemctl stop pilinkd.service
sudo systemctl restart pilinkd.service
systemctl status pilinkd.service
/usr/local/libexec/pilink-network status
sudo journalctl -u pilinkd.service -f
```

Without a BLE connection, the service waits without creating a TUN interface. After connecting, check routing with `ip route get 8.8.8.8`. To stop the service and disable startup at boot, run `sudo systemctl disable --now pilinkd.service`.

The Mac connection log is `build/pilink-connect.log`. Android's diagnostics screen shows the SSH listening port and connection details. See the [protocol](protocol.md#connection-limits-and-timeouts) for connection and timeout limits.

## Updates and the installer

Follow the same [Pi installation procedure in the README](../README.md#pi) for updates. Replacing the service disconnects BLE SSH, so use a separate LAN / USB SSH management connection.

- The script needs the source tree's `CMakeLists.txt`, `LICENSE`, `pi/`, and `tests/`. It does not depend on the working directory from which it is invoked.
- After building and testing PiLink and hev, it backs up the existing binaries, license texts, unit files, and PiLink service state to `/var/backups/pilink-0.1.0-*`. It restores them if installation or startup verification fails.
- APT packages and Bluetooth / OpenSSH preparation are not rolled back. Any old `pilink-network.service` is stopped, disabled, and removed. Existing systemd drop-ins are left in place; review overrides of the startup command before updating.
- Downloaded sources and build outputs are stored in `build/pi-install/`. Sources are verified and tests run again on each invocation. Use `--skip-deps` to skip APT only when all dependencies are already installed.

For regular users, compilation runs as that user and only administrative steps use `sudo -n`. Running as root is also supported. Compilation uses one job to fit Pi Zero's memory limits. The caller's Python virtual environment is not passed to the systemd service. See [third-party notices](third-party-notices.md) for external source and license information.

Other project installers can invoke the script as a child process using an absolute path adjusted to the checkout location. Failures return a nonzero exit status.

```bash
bash "$HOME/code/rpi-ble-tunnel/scripts/install-pi-network.sh"
```

Deployment from a Mac copies the source to `~/rpi-ble-tunnel`. When specifying the management SSH connection by IP address, pass the trusted host-key alias as the second argument.

```bash
./scripts/deploy-pi.sh pi@PI_IP raspberrypi.local
```

## Caller-managed adapter selection

Install with `bash scripts/install-pi-network.sh --caller-managed`. This adds the generic `/usr/local/libexec/pilink-service-control` command and a systemd drop-in. It does not unblock Bluetooth, power a fixed hci0, or enable/start PiLink. The service does not read the caller's repository, Python environment, or settings file.

Use `sudo -n /usr/local/libexec/pilink-service-control apply --adapter hciN` to prepare the selected controller and enable/start the service. Reapplying the same running controller does not restart it. The other commands are `status`, `stop` (temporary), and `disable` (stop and disable automatic startup). Results are JSON; `adapter_unavailable` identifies controller preparation failures that a caller may use for fallback. Other errors must not trigger adapter fallback. No command unblocks rfkill.

The service uses `Type=exec` so status is collected after its process has executed. This prevents an immediate adapter switch from returning `adapter=null` while the process is still being prepared. Check `READY` in the service log to confirm BLE advertising has started.

The controller name and address are held in `/run/pilink-control/adapter.env` and checked before service startup. After OS reboot a caller must select the adapter again. Reinstall in caller-managed mode for updates, then let the caller reapply its settings. To restore standalone operation, remove `/etc/systemd/system/pilinkd.service.d/20-caller-adapter.conf`, run `systemctl daemon-reload`, and enable/start the original service.

## TUN, routes, and DNS

```text
Pi TCP apps → tun0 → hev-socks5-tunnel → PiLink SOCKS5 :1080
           → BLE → Android / Mac TCP connections and DNS resolution → Internet
SSH client → Android / Mac 127.0.0.1:<port> → BLE → Pi sshd :22
```

| Component | Role |
| --- | --- |
| `pilinkd.service` | Starts, stops, and supervises BLE and network management together |
| `pilinkd` | Accepts BLE connections, relays SSH / SOCKS5, and reports connection state |
| `pilink-network` | Supervises the BLE daemon and manages the TUN interface and temporary NetworkManager profile while connected |
| `hev-socks5-tunnel` | Converts TUN TCP traffic into SOCKS5 CONNECT requests and provides mapped DNS |

Connection state is stored in `/run/pilink/link.json`. Network management checks the PID, process start time, and session UUID. The service's capability bounding set is limited to `CAP_NET_ADMIN`.

| Setting | Value |
| --- | --- |
| TUN | `tun0`, IPv4 `198.18.0.1/30`, IPv6 `fd00:7069:6c69:6e6b::1/128` |
| IPv4 / IPv6 default routes | Metric 50 |
| DNS / synthetic mapped-DNS address range | `198.18.0.2` / `198.19.0.0/16` |

Adjust the configuration if `tun0` or these address ranges are already used for another purpose. The helper leaves TUN interfaces owned by others untouched.

NetworkManager creates a nonpersistent profile with UUID `c7cf1339-9037-4d4a-aa55-6af99efa034b`. Routes and DNS are applied after hev is ready. The helper does not edit `/etc/resolv.conf` directly. On disconnect, it removes its temporary profile and TUN interface, and NetworkManager restores the previous connection settings.

Mapped DNS returns synthetic addresses for A records. See the [README](../README.md#scope-and-limitations) for supported traffic. TCP to literal IPv6 addresses and destinations resolved to IPv6 by the client is supported. Android / Mac uses its default network; the client's Wi-Fi, cellular, and VPN settings are left unchanged.

## Recovery after failures

If only hev exits unexpectedly, the helper recreates the TUN interface while keeping BLE connected. If the BLE daemon or management process fails, systemd restarts the whole service. Normal shutdown uses `KillMode=mixed` to send SIGTERM to the management process, which cleans up routes, DNS, and child processes in order. After forced termination, remaining child processes are also terminated and `ExecStopPost` performs cleanup.

See [README usage](../README.md#usage) for client reconnection behavior. Recovery creates a new BLE session and TUN interface.

## Manual operation

### Start with TUN management

Run through the Pi's LAN / USB SSH management connection. Stop the background service first to avoid running two instances.

```bash
sudo systemctl stop pilinkd.service
sudo /usr/local/libexec/pilink-network service --adapter hci0 --mode internet
```

Ctrl+C stops the BLE daemon and hev and cleans up TUN, routes, and DNS. If the management process is killed with SIGKILL, systemd's cleanup hook does not run. Terminate your remaining BLE daemon and hev processes, then run `sudo /usr/local/libexec/pilink-network cleanup`.

### Use SOCKS5 directly

Run only the BLE daemon on the Pi, without TUN management.

```bash
sudo systemctl stop pilinkd.service
sudo /usr/local/bin/pilinkd --adapter hci0 --mode internet
```

After connecting from PiLink on Android / Mac, the Pi prints `SOCKS5 READY 127.0.0.1:1080`. The SOCKS listener exists only while BLE is connected; change its port with `--socks-port`. SSH remains available over the same BLE connection.

Configure each Pi application to use `socks5h` to delegate DNS resolution to the client as well.

```bash
ALL_PROXY=socks5h://127.0.0.1:1080 curl --noproxy '' https://example.com/
git -c http.proxy=socks5h://127.0.0.1:1080 clone --depth 1 https://github.com/octocat/Hello-World.git
apt-get -o Acquire::http::Proxy=socks5h://127.0.0.1:1080 \
        -o Acquire::https::Proxy=socks5h://127.0.0.1:1080 download hello
```

Check each application's proxy / `NO_PROXY` settings. See the [curl manual](https://curl.se/docs/manpage.html) and [APT HTTP transport documentation](https://manpages.debian.org/trixie/apt/apt-transport-http.1.en.html) for configuration details.

### Return to the background service

Stop the manually started program with Ctrl+C, then run on the Pi:

```bash
sudo systemctl start pilinkd.service
```

## Run the Mac client directly

To run in the foreground, launch the client from the Mac repository root. Stop any background client first with `./scripts/macos-client.sh stop`.

```bash
./build/PiLink.app/Contents/MacOS/pilink connect --name raspberrypi --timeout 60
```
