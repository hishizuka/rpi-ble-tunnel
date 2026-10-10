# 運用・ネットワーク管理

[English (primary)](operations.md) | 日本語

標準の導入・接続・停止手順は [README](../README.ja.md) を参照してください。この文書は更新、診断、障害復旧と、通常の TUN 構成を変更する場合の手順を説明します。

## サービスの操作と診断

Pi 側の BLE とネットワーク管理は `rpi-ble-tunneld.service` 一つで起動・停止します。Pi 上で実行します。

```bash
sudo systemctl start rpi-ble-tunneld.service
sudo systemctl stop rpi-ble-tunneld.service
sudo systemctl restart rpi-ble-tunneld.service
systemctl status rpi-ble-tunneld.service
/usr/local/libexec/rpi-ble-tunnel-network status
sudo journalctl -u rpi-ble-tunneld.service -f
```

BLE 未接続時は TUN を作らず待機します。接続後の経路確認には `ip route get 8.8.8.8` を使います。自動起動も解除する場合は `sudo systemctl disable --now rpi-ble-tunneld.service` を実行します。

Mac の接続ログは `build/rpi-ble-tunnel-connect.log`、Android の SSH 待受ポートと接続情報は診断画面で確認できます。接続数・待機時間の詳細は[通信仕様](protocol.ja.md#接続上限とタイムアウト)を参照してください。

## 更新とインストーラー

更新は [README の Pi インストール手順](../README.ja.md#pi)と同じです。サービスの置き換えで BLE SSH が切れるため、管理用 LAN / USB SSH から実行します。

- スクリプトはソース一式の `CMakeLists.txt`・`LICENSE`・`pi/`・`tests/` を使います。実行時の作業ディレクトリには依存しません。
- rpi-ble-tunnel と hev のビルド・テスト後、既存のバイナリ・ライセンス文・unit・rpi-ble-tunnel の起動状態を `/var/backups/rpi-ble-tunnel-0.1.0-*` に退避します。配置または起動確認に失敗すると復元します。
- APT のパッケージ、Bluetooth / OpenSSH の準備は巻き戻しません。旧 `rpi-ble-tunnel-network.service` があれば停止・無効化・削除します。既存の systemd drop-in は書き換えないため、起動コマンドを上書きしている設定は事前に整理してください。
- 取得済みソースとビルド結果は `build/pi-install/` に保存します。再実行時もソースの検証とテストを行います。依存をすべて準備済みの場合だけ `--skip-deps` で APT の工程を省略できます。

一般ユーザーではビルドをそのユーザーで行い、管理操作だけに `sudo -n` を使います。root からの実行も可能です。コンパイルは Pi Zero のメモリに合わせ1ジョブです。呼び出し元の Python 仮想環境は systemd サービスに引き継ぎません。外部コードの取得元とライセンスは[出典情報](third-party-notices.ja.md)にまとめています。

別プロジェクトのインストーラーからは、配置先に合わせた絶対パスで子プロセスとして呼び出せます。失敗時は終了コードが非0になります。

```bash
bash "$HOME/code/rpi-ble-tunnel/scripts/install-pi-network.sh"
```

Mac からの転送先は `~/rpi-ble-tunnel` です。管理用 SSH を IP アドレスで指定する場合は、登録済みホスト鍵の照合名を第2引数に渡します。

```bash
./scripts/deploy-pi.sh pi@PI_IP raspberrypi.local
```

## 呼び出し元からアダプターを指定する連携モード

`bash scripts/install-pi-network.sh --caller-managed` で汎用のサービス操作コマンドと systemd drop-in を導入します。このモードでは Bluetooth の rfkill 解除、hci0 の固定電源操作、rpi-ble-tunnel の有効化・起動を行いません。呼び出し元アプリの設定ファイルやリポジトリをサービスから参照せず、同じコマンドを手動でも利用できます。

```bash
sudo -n /usr/local/libexec/rpi-ble-tunnel-service-control apply --adapter hci1
sudo -n /usr/local/libexec/rpi-ble-tunnel-service-control status
sudo -n /usr/local/libexec/rpi-ble-tunnel-service-control stop
sudo -n /usr/local/libexec/rpi-ble-tunnel-service-control disable
```

`apply` は指定対象を準備してから有効化・起動し、同じ対象で稼働中なら再起動しません。`stop` は一時停止、`disable` は停止と自動起動の無効化です。結果は JSON で返し、起動前の対象不在・電源準備失敗などは `adapter_unavailable`、それ以外の失敗は別の理由として返します。rfkill はこれらの操作で解除しません。

サービスは `Type=exec` で起動し、プロセス実行後に状態を取得します。これにより、切り替え直後の実行準備中に `adapter=null` を返す競合を防ぎます。BLE 広告の開始はサービスログの `READY` で確認します。

アダプター名とアドレスは `/run/rpi-ble-tunnel-control/adapter.env` に保持し、起動前に現在のコントローラーと照合します。OS 再起動後は呼び出し元が再指定するまで待機します。連携モードの更新は再びこのオプションで導入し、保存設定の反映は呼び出し元が行います。従来の単独運用へ戻す場合は `/etc/systemd/system/rpi-ble-tunneld.service.d/20-caller-adapter.conf` を削除して `systemctl daemon-reload` し、通常の手順で有効化・起動します。

## TUN・経路・DNS の構成

```text
Pi の TCP アプリ → tun0 → hev-socks5-tunnel → rpi-ble-tunnel SOCKS5 :1080
                → BLE → Android / Mac の TCP・名前解決 → Internet
SSH クライアント → Android / Mac 127.0.0.1:<port> → BLE → Pi sshd :22
```

| 担当 | 内容 |
| --- | --- |
| `rpi-ble-tunneld.service` | BLE とネットワーク管理をまとめて起動・停止・監視 |
| `rpi-ble-tunneld` | BLE 待受、SSH / SOCKS5、接続状態の通知 |
| `rpi-ble-tunnel-network` | BLE daemon の監視、接続中だけ TUN と一時 NetworkManager プロファイルを管理 |
| `hev-socks5-tunnel` | TUN の TCP を SOCKS5 CONNECT へ変換し、mapped DNS を提供 |

接続状態は `/run/rpi-ble-tunnel/link.json` です。ネットワーク管理は PID・プロセス開始時刻・セッション UUID を照合します。サービスの capability は `CAP_NET_ADMIN` に限定しています。

| 項目 | 値 |
| --- | --- |
| TUN | `tun0`、IPv4 `198.18.0.1/30`、IPv6 `fd00:198:18::1/128` |
| IPv4 / IPv6 の既定経路 | metric 50 |
| DNS / mapped DNS の仮アドレス | `198.18.0.2` / `198.19.0.0/16` |

`tun0` やこれらの範囲を別用途で使う環境では調整が必要です。他の所有者の TUN は操作しません。

NetworkManager の UUID `c7cf1339-9037-4d4a-aa55-6af99efa034b` に保存しないプロファイルを作り、hev の準備後に経路・DNS を適用します。`/etc/resolv.conf` は直接編集しません。切断時は自分の一時プロファイルと TUN を削除し、NetworkManager が元の接続設定に戻します。

mapped DNS は A に仮アドレスを返します。対応範囲は [README](../README.ja.md#対応範囲)を参照してください。IPv6 アドレスを直接指定する TCP と、クライアントが宛先を IPv6 へ解決する TCP は利用できます。Android / Mac の既定ネットワークを使い、クライアント側の Wi-Fi・モバイル回線・VPN 設定は変更しません。

## 障害時の復旧

hev だけの異常終了では TUN を再生成し、BLE 接続を維持します。BLE daemon や管理プログラムの異常終了では systemd が全体を再起動します。通常停止は `KillMode=mixed` で管理プログラムに SIGTERM を送り、経路・DNS と子プロセスを順に片付けます。強制終了後も残った子プロセスを終了し、`ExecStopPost` で後処理します。

クライアント側の再接続方法は [README の使い方](../README.ja.md#使い方)を参照してください。復旧後は新しい BLE セッションと TUN を作ります。

## 手動で実行する場合

### TUN を含めて起動

Pi の管理用 LAN / USB SSH から実行します。サービスとの二重起動を避けるため、先に常駐サービスを停止します。

```bash
sudo systemctl stop rpi-ble-tunneld.service
sudo /usr/local/libexec/rpi-ble-tunnel-network service --adapter hci0 --mode internet
```

Ctrl+C で BLE daemon・hev を終了し、TUN・経路・DNS を片付けます。管理プログラムを SIGKILL した場合は systemd の後処理が働かないため、残った自分の BLE daemon・hev を終了してから `sudo /usr/local/libexec/rpi-ble-tunnel-network cleanup` を実行してください。

### SOCKS5 を直接利用

TUN の管理を使わず、BLE daemon だけを Pi 上で起動します。

```bash
sudo systemctl stop rpi-ble-tunneld.service
sudo /usr/local/bin/rpi-ble-tunneld --adapter hci0 --mode internet
```

Android / Mac の rpi-ble-tunnel から接続すると、Pi に `SOCKS5 READY 127.0.0.1:1080` が表示されます。SOCKS listener は BLE 接続中だけ存在し、`--socks-port` で変更できます。SSH も同じ BLE 接続で利用できます。

Pi のアプリごとに `socks5h` を設定すると、名前解決もクライアントに任せます。

```bash
ALL_PROXY=socks5h://127.0.0.1:1080 curl --noproxy '' https://example.com/
git -c http.proxy=socks5h://127.0.0.1:1080 clone --depth 1 https://github.com/octocat/Hello-World.git
apt-get -o Acquire::http::Proxy=socks5h://127.0.0.1:1080 \
        -o Acquire::https::Proxy=socks5h://127.0.0.1:1080 download hello
```

個別の proxy / `NO_PROXY` 設定はアプリ側で確認してください。設定方法は [curl](https://curl.se/docs/manpage.html)、[APT HTTP transport](https://manpages.debian.org/trixie/apt/apt-transport-http.1.en.html)を参照してください。

### 常駐サービスへ戻す

手動で起動したプログラムを Ctrl+C で終了し、Pi 上で実行します。

```bash
sudo systemctl start rpi-ble-tunneld.service
```

## Mac クライアントを直接起動

フォアグラウンドで使う場合は、Mac のリポジトリルートから直接起動できます。バックグラウンドで動作中のクライアントは先に `./scripts/macos-client.sh stop` で停止します。

```bash
./build/rpi-ble-tunnel.app/Contents/MacOS/rpi-ble-tunnel connect --name raspberrypi --timeout 60
```
