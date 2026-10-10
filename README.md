# rpi-ble-tunnel — Network tunnel over BLE

English (primary) | [日本語](README.ja.md)

rpi-ble-tunnel is a library that provides a TCP tunnel over BLE. You can SSH into a Raspberry Pi from an Android phone or Mac, and the Pi can access the Internet through that device's connection.

The Pi and client do not need to share a Wi-Fi network.

<img src="docs/assets/rpi-ble-tunnel-overview.svg" alt="rpi-ble-tunnel connects a Raspberry Pi to an Android phone or Mac over one BLE link, relaying SSH to the Pi and outbound TCP through the client's Internet connection. No shared Wi-Fi network required." width="1280">

- Connect to the Pi's OpenSSH server from an Android or Mac SSH client. SCP, SFTP, and rsync also work.
- TCP applications on the Pi can use the Android or Mac Internet connection without configuring a proxy for each application.

## Prerequisites

| Device | Requirements |
| --- | --- |
| Raspberry Pi | Raspberry Pi OS, Python 3.9 or later, Linux 5.3 or later, systemd, NetworkManager, and an adapter supporting BLE Peripheral mode and L2CAP CoC. Tested on Pi Zero W / ARMv6 |
| Android | Android 10 / API 29 or later, with BLE / L2CAP CoC support |
| Mac | macOS 12 or later, with Bluetooth |

To relay Internet traffic, the Android or Mac client needs an Internet connection, such as Wi-Fi or cellular. For SSH, provide a separate SSH client and the Pi's login credentials.

The instructions below build and install from source. The Pi installer installs its dependencies. Prepare the following tools to build and use the clients.

| Client | Tools required on the computer used for building and installation |
| --- | --- |
| Android app | Mac / Linux with a shell, JDK 17 or later, Android SDK 36, a C compiler, Python 3, and `adb` from SDK Platform-Tools. The Gradle Wrapper is included |
| Mac client | Swift 5.9 or later from Xcode / Command Line Tools, and Python 3 for the helper scripts |

## Installation

Clone or download this repository onto the Pi and the computer used to build the client. Run the commands below from the repository root on the indicated machine. Install the Pi component first, then the Android or Mac client you plan to use.

Replace the example Pi hostname `raspberrypi` and SSH username `pi` with your own values.

### Pi

Run on the Pi. The first installation requires the Pi's own Internet connection and administrator access. Administrative steps use `sudo -n` when running as a regular user; authenticate with `sudo -v` beforehand if needed.

```bash
bash scripts/install-pi-network.sh
```

If sudo authorization expires during a long first build, run `sudo -v` and repeat the command; completed builds are reused. You can also run `sudo bash scripts/install-pi-network.sh` to build and install as root.

The installer installs dependencies, builds and tests the software, installs the service, starts `rpi-ble-tunneld.service`, and enables it at boot. Use the same procedure for updates. Updates disconnect BLE, so run them through a separate LAN / USB SSH management connection. See [update and recovery details](docs/operations.md#updates-and-the-installer).

You can also transfer the source and install from a Mac. This requires SSH public-key authentication over the management connection, a trusted host key, and passwordless sudo on the Pi. Run on the Mac.

```bash
./scripts/deploy-pi.sh pi@raspberrypi.local
```

### Android

Run on the build computer. In [SDK Manager](https://developer.android.com/studio/intro/update#sdk-manager), install Android SDK Platform 36, Build-Tools 36.0.0, and Platform-Tools, and accept the SDK licenses.

Set `JAVA_HOME` for the JDK, specify the SDK with `ANDROID_HOME` or `android/local.properties`, and add `adb` to PATH. Android Studio's JDK and SDK are detected automatically at their standard Mac locations. Connect the Android device over USB, enable USB debugging, and authorize the computer.

```bash
./scripts/build-android.sh
./scripts/deploy-android.sh ADB_SERIAL
```

The APK is saved to `build/rpi-ble-tunnel-android-debug.apk`. You can omit `ADB_SERIAL` when only one device is connected.

### Mac

Run on the Mac you will use as the client. If Xcode / Command Line Tools is not installed, run `xcode-select --install` first. Check that `swift --version` reports 5.9 or later.

```bash
./scripts/build-macos.sh
```

The client is saved to `build/rpi-ble-tunnel.app`.

## Usage

### Connect from Android

1. Open **Add a Pi** in rpi-ble-tunnel settings, allow access to nearby devices, select a Pi from the search results, and save a display name. Choose **Enter hostname** to search by hostname alone; the Bluetooth address is read automatically from the selected device. Android 10 / 11 requires location permission and location services to be enabled.
2. Select the Pi on the connection screen, tap **Connect**, and allow notifications when prompted.
3. Use **Open in Termius** or **Copy command** to start SSH after connecting. Store SSH credentials in your chosen SSH client.

Up to three Bluetooth addresses can be registered. The internal and external adapters of the same Pi can have separate entries, with duplicate display names and hostnames allowed. Display names do not identify the connection or determine the SSH HostKeyAlias. For an older entry without an address, tap **Connect** to search by hostname, select its adapter, and save.

The interface and notifications follow the device language: Japanese for Japanese locales and English otherwise. Technical connection logs are recorded in English for troubleshooting.

**Power saving** in settings is off by default and can be changed while connected. When enabled, Android waits for TCP events instead of checking every 100 ms, retaining DNS and TCP connection deadlines. It requests BLE `LOW_POWER` while no SSH / Internet streams or wire writes are active and `BALANCED` when a connection starts. Turning it off restores `BALANCED` and periodic TCP checks. Open SSH sessions retain their wake lock even when no data is flowing. BLE priority changes are requests to the Bluetooth stack; device support and the resulting power savings vary. If the link becomes unstable, turn this setting off.

The Pi always waits for relay socket readiness and network / process notifications, independently of the Android setting. It wakes for connection deadlines or recovery retries when needed, with no fixed 10 ms relay or 500 ms network-supervision polling.

Changing the adapter on the Pi disconnects the existing BLE link. Stop the Android connection, select the entry for the new address, and connect again. Automatic reconnection continues to use the selected address.

Point the SSH client at `127.0.0.1` on the Android device. Each registered address has an assigned local port, shown on the diagnostics screen. SSH clients other than Termius also work.

Stop the connection from the app or notification. After an unexpected disconnect, the app retries about every two minutes, but existing SSH / TCP sessions are not restored. Power-saving modes may delay retries. The app does not connect automatically after a force-stop or device restart.

### Connect from Mac

Allow Bluetooth access when prompted on first use.

```bash
./scripts/macos-client.sh start raspberrypi
ssh -p 2222 -o HostKeyAlias=raspberrypi.local pi@127.0.0.1
```

`HostKeyAlias` selects the Pi's SSH host key for verification. Check the connection status or stop it with:

```bash
./scripts/macos-client.sh status
./scripts/macos-client.sh stop
```

An unexpected disconnect exits the Mac client. Run `start HOSTNAME` again to reconnect.

### Access the Internet from the Pi

After connecting with rpi-ble-tunnel on Android or Mac, run ordinary TCP applications on the Pi. No per-application SOCKS configuration is required.

```bash
curl --noproxy '*' https://example.com/
```

A `tun0` interface and temporary routes and DNS settings are created while connected and cleaned up on disconnect. See [operations and network management](docs/operations.md) for diagnostics and direct SOCKS5 use.

## Scope and limitations

- Each Pi accepts one Android or Mac BLE connection at a time.
- SSH and outbound TCP share a limit of eight connections, with at most seven outbound TCP connections. New requests wait when busy and fail if queue or timeout limits are exceeded.
- General UDP, QUIC, NTP, and ICMP traffic is unsupported. Mapped DNS returns synthetic addresses for A records; actual AAAA, MX, TXT, and similar record lookups are unsupported.
- rpi-ble-tunnel uses a different mechanism from Bluetooth PAN tethering. Large transfers and many parallel downloads are constrained.
- BLE pairing and link encryption are not required. OpenSSH provides SSH authentication and encryption; applications provide HTTPS encryption.

## Further documentation

| Document | Contents |
| --- | --- |
| [Operations and network management](docs/operations.md) | Updates, diagnostics, recovery, TUN, routes, DNS, and direct SOCKS5 use |
| [Protocol](docs/protocol.md) | GATT / L2CAP, SSH multiplexing, Internet extensions, flow control, and connection limits |
| [Development and testing](docs/development.md) | Source layout, unit and integration tests, and hardware verification |
| [Third-party code and assets](docs/third-party-notices.md) | Sources and licenses for external code and icons |

## License

rpi-ble-tunnel is licensed under the [MIT License](LICENSE). Third-party code and assets retain their own licenses; see [third-party notices](docs/third-party-notices.md).
