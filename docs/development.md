# Development and testing

English (primary) | [日本語](development.ja.md)

See the [README](../README.md) for normal installation, usage, and client build requirements; the [protocol](protocol.md) for communication behavior; and [operations and network management](operations.md) for service control and recovery.

## Source and test layout

| Directory | Contents |
| --- | --- |
| `pi/` | BLE daemon, relay code, network management, and systemd unit |
| `android/` | Android app, JVM tests in `app/src/test/`, and Gradle Wrapper |
| `macos/` | Swift client and tests in `Tests/` |
| `scripts/` | Build, deployment, installation, and Mac client control |
| `tests/` | C tests and integration / hardware verification tools in `integration/` |
| `docs/` | Operations, protocol, development and testing, and third-party sources |

## Tests without hardware

Run from the repository root. C tests require CMake 3.16 or later and a C compiler; integration checks require Python 3. Building the daemon on Linux also requires GLib / BlueZ development packages.

```bash
./scripts/test.sh
./scripts/build-android.sh
./scripts/build-macos.sh
```

- `test.sh` runs the C tests. On Mac, it also runs Swift unit tests and C / Swift multiplexing and SOCKS5 integration tests.
- The Android build script produces the APK and runs JVM tests, Lint, and C / Kotlin SOCKS5 integration tests.
- The Mac build script produces the client. `test.sh` handles Swift verification.

### Integration coverage and limits

The Pi's `pi/mux.c`, the Mac's `PiLinkCore/Multiplex.swift` / `PiLinkMux/MuxProxy.swift`, and Android's `Multiplex.kt` / `MuxBridge.kt` are shared between the real relay and test executables.

The test transport substitutes loopback TCP for the wire and splits transfers into 127-byte chunks. C tests use Unix `SOCK_SEQPACKET` on Linux and Unix `SOCK_DGRAM` on Mac to check SDU fragmentation and coalescing. These tests cover framing, TCP relay, and flow control, but do not exercise the BLE stack. Verification using real Pi / Android hardware runs separately.

Android holds a wake lock only while a stream or outgoing frame is active, releasing it after the final FIN is written or on stop. Hardware checks also verify transfers with the screen off and wake-lock release.

## Hardware verification tools

These are not required for normal use. Specify the target hostname, management SSH connection, adb serial, and host-key alias explicitly. SSH checks require public-key authentication and trusted host keys.

| Tool | Checks |
| --- | --- |
| `verify-echo.py` | Binary echo over BLE, with the Pi running `--mode echo` |
| `verify-ssh.py` | SSH / SCP / SFTP / rsync in single-SSH mode |
| `verify-multiplex.py` | Loopback multiplexing or parallel SSH through an existing BLE proxy |
| `verify-internet.py` | C / Swift or C / Kotlin SOCKS5 relay without hardware |
| `verify-pi-internet.py` | Outbound TCP from the Pi alongside SSH, using management SSH and BLE |
| `verify-ble-loss.py` | BLE disconnection and SSH shutdown using a specified stop command |
| `verify-android-screen-off.py` | Screen-off operation, parallel SSH, and wake-lock release on a specified Android device |
| `verify-pi-network.py` | NetworkManager / hev behavior in an isolated namespace |

Examples:

```bash
python3 tests/integration/verify-multiplex.py --ssh --proxy-port 2222 \
  --host-key-alias raspberrypi.local
python3 tests/integration/verify-pi-internet.py --target pi@raspberrypi.local \
  --host-key-alias raspberrypi.local
```

Isolated network-management verification uses separate network / mount / UTS namespaces and a dedicated D-Bus instance. The tool checks namespace separation before proceeding.

```bash
sudo unshare --net --mount --uts --propagation private --fork \
  python3 tests/integration/verify-pi-network.py \
  --work /tmp/pilink-network-check \
  --helper "$PWD/pi/pilink-network.py" \
  --binary /usr/local/libexec/hev-socks5-tunnel
```

## Documentation languages

`README.md` and Markdown files under `docs/` without a language suffix are the authoritative English versions. Japanese translations use the corresponding `.ja.md` filenames. Update the English version first, then reflect the changes in Japanese. Internal links should stay within the same language; the language switch at the top links to the corresponding version.

## Build outputs and local notes

APKs, the Mac app, C build outputs, test-result JSON, and logs are stored in `build/` and excluded from Git. Gradle / Swift / Python caches, SDK path settings, and signing keys are also excluded. The Gradle Wrapper JAR is included because builds require it.

Keep device-specific notes and investigation code in `.local/`; place reusable tests in `tests/integration/`.
