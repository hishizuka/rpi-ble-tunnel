# 外部コード・素材

[English (primary)](third-party-notices.md) | 日本語

## hev-socks5-tunnel

Pi の透過 TCP 通信には [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) 2.18.0 を使います。インストーラーがサブモジュール込みソースを取得し、固定 SHA-256 を検証してビルドします。ソースや生成バイナリをこのリポジトリには同梱しません。

MIT ライセンスの本文は [pi/hev-socks5-tunnel-LICENSE.txt](../pi/hev-socks5-tunnel-LICENSE.txt) に保存し、Pi の `/usr/local/share/doc/pilink/` に配置します。同梱依存の条件は取得する upstream ソースを参照してください。

## Android のアイコン

Google Material Design Icons の SVG を Android VectorDrawable に変換し、テーマの色を適用しています。

- [元の素材と変換方法](third-party/material-icons-sources.json)
- [Apache 2.0 ライセンス全文](../android/app/src/main/assets/material-icons-LICENSE.txt)

## README の図

[概要図](assets/pilink-overview.svg)の Raspberry Pi と Android のロゴは、[gadgetbridge-rpi-link の図](https://github.com/hishizuka/gadgetbridge-rpi-link/blob/main/docs/assets/gadgetbridge-rpi-link-overview.svg)から使用しています。

- Android ロボットは Google が作成・公開した素材で、[Creative Commons Attribution 3.0](https://creativecommons.org/licenses/by/3.0/) に基づいて使用しています。
- Raspberry Pi は Raspberry Pi Ltd. の商標です。
- macOS を示す Apple アイコンは [Simple Icons](https://github.com/simple-icons/simple-icons/blob/develop/icons/apple.svg) の素材で、[CC0 1.0](https://github.com/simple-icons/simple-icons/blob/develop/LICENSE.md) で公開されています。Apple と macOS は Apple Inc. の商標です。

## ビルド依存

Android の依存は `android/app/build.gradle` と `android/build.gradle`、Gradle Wrapper の取得先は `android/gradle/wrapper/gradle-wrapper.properties` で管理しています。Pi は GLib / BlueZ と OS の NetworkManager、Mac は Apple の CoreBluetooth を使用します。

PiLink 本体は [MIT ライセンス](../LICENSE)で公開しています。ライセンス文は Android の APK と Mac アプリに同梱し、Pi では `/usr/local/share/doc/pilink/LICENSE` に配置します。上記の外部コード・素材には、それぞれのライセンスが別途適用されます。
