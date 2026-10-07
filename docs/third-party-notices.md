# Third-party code and assets

English (primary) | [日本語](third-party-notices.ja.md)

## hev-socks5-tunnel

The Pi's transparent TCP relay uses [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) 2.18.0. The installer downloads its source, including submodules, verifies a pinned SHA-256 checksum, and builds it. Neither the source nor the generated binary is bundled in this repository.

The MIT license text is stored in [pi/hev-socks5-tunnel-LICENSE.txt](../pi/hev-socks5-tunnel-LICENSE.txt) and installed under `/usr/local/share/doc/pilink/` on the Pi. Consult the downloaded upstream source for the terms of its bundled dependencies.

## Android icons

SVGs from Google Material Design Icons are converted to Android VectorDrawables and tinted using the app theme.

- [Original assets and conversion details](third-party/material-icons-sources.json)
- [Full Apache 2.0 license](../android/app/src/main/assets/material-icons-LICENSE.txt)

## README diagram

The Raspberry Pi and Android logos in [the overview diagram](assets/pilink-overview.svg) come from [gadgetbridge-rpi-link's diagram](https://github.com/hishizuka/gadgetbridge-rpi-link/blob/main/docs/assets/gadgetbridge-rpi-link-overview.svg).

- The Android robot is reproduced from work created and shared by Google and used under the [Creative Commons Attribution 3.0 license](https://creativecommons.org/licenses/by/3.0/).
- Raspberry Pi is a trademark of Raspberry Pi Ltd.
- The Apple icon used to identify macOS comes from [Simple Icons](https://github.com/simple-icons/simple-icons/blob/develop/icons/apple.svg), released under [CC0 1.0](https://github.com/simple-icons/simple-icons/blob/develop/LICENSE.md). Apple and macOS are trademarks of Apple Inc.

## Build dependencies

Android dependencies are defined in `android/app/build.gradle` and `android/build.gradle`; the Gradle Wrapper download is configured in `android/gradle/wrapper/gradle-wrapper.properties`. The Pi uses GLib / BlueZ and the operating system's NetworkManager. The Mac client uses Apple's CoreBluetooth.

PiLink itself is licensed under the [MIT License](../LICENSE). Its license is bundled in the Android APK and Mac app, and installed under `/usr/local/share/doc/pilink/LICENSE` on the Pi. The license texts referenced above apply separately to external code and assets.
