# 開発・検証

[English (primary)](development.md) | 日本語

通常の導入・利用とクライアントのビルド環境は [README](../README.ja.md)、通信方式は[通信仕様](protocol.ja.md)、サービスの操作と復旧は[運用・ネットワーク管理](operations.ja.md)を参照してください。

## ソースとテストの構成

| ディレクトリ | 内容 |
| --- | --- |
| `pi/` | BLE daemon、中継処理、ネットワーク管理、systemd unit |
| `android/` | Android アプリ、`app/src/test/` の JVM テスト、Gradle Wrapper |
| `macos/` | Swift クライアントと `Tests/` のテスト |
| `scripts/` | ビルド・配置・インストール・Mac クライアント操作 |
| `tests/` | C のテストと `integration/` の結合・実機検証ツール |
| `docs/` | 運用、通信仕様、開発・検証、外部コード・素材の出典 |

## ハードウェアを使わないテスト

リポジトリルートから実行します。C の検証には CMake 3.16 以上と C コンパイラ、結合検証には Python 3 が必要です。Linux で daemon をビルドする場合は GLib / BlueZ の開発パッケージも用意します。

```bash
./scripts/test.sh
./scripts/build-android.sh
./scripts/build-macos.sh
```

- `test.sh` は C のテストを実行し、Mac では Swift の単体テストと C / Swift の多重化・SOCKS5 結合テストも実行します。
- Android のビルドスクリプトは APK、JVM テスト、Lint、C / Kotlin の SOCKS5 結合テストを実行します。
- Mac のビルドスクリプトはクライアントの生成に使います。Swift の検証は `test.sh` が担当します。

### 結合検証の対象と限界

Pi の `pi/mux.c`、Mac の `PiLinkCore/Multiplex.swift` / `PiLinkMux/MuxProxy.swift`、Android の `Multiplex.kt` / `MuxBridge.kt` を実際の中継と検証用実行ファイルで共用します。

検証用 transport は loopback TCP を wire として使い、127バイトに分割して転送します。C のテストは Linux で Unix `SOCK_SEQPACKET`、Mac で Unix `SOCK_DGRAM` を使い、SDU の分割・連結も検証します。フレーム処理・TCP 中継・流量制御を検証できますが、BLE stack は通りません。Pi / Android の実機を操作する検証は別途実行します。

Android は stream または送信中フレームがある間だけ wake lock を保持し、最後の FIN の書き込み後や停止時に解放します。画面 OFF 中の転送と wake lock の解放は実機でも検証します。

## 実機の検証ツール

通常利用には不要です。対象のホスト名・管理用 SSH・adb serial・ホスト鍵照合名を明示して使います。SSH 系の検証は公開鍵認証と登録済みホスト鍵を前提にしています。

| ツール | 検証内容 |
| --- | --- |
| `verify-echo.py` | BLE のバイナリ echo。Pi を `--mode echo` で起動して使う |
| `verify-ssh.py` | 単一 SSH モードの SSH / SCP / SFTP / rsync |
| `verify-multiplex.py` | loopback の多重化、または既存 BLE proxy の並行 SSH |
| `verify-internet.py` | ハードウェアを使わない C / Swift・C / Kotlin の SOCKS5 中継 |
| `verify-pi-internet.py` | 管理用 SSH と BLE 接続を使う、Pi 発の外部 TCP と並行 SSH |
| `verify-ble-loss.py` | 指定した停止コマンドによる BLE 切断と SSH 終了 |
| `verify-android-screen-off.py` | 指定 Android の画面 OFF、並行 SSH、wake lock の解放 |
| `verify-pi-network.py` | 独立した namespace 内での NetworkManager / hev の動作 |

例:

```bash
python3 tests/integration/verify-multiplex.py --ssh --proxy-port 2222 \
  --host-key-alias raspberrypi.local
python3 tests/integration/verify-pi-internet.py --target pi@raspberrypi.local \
  --host-key-alias raspberrypi.local
```

ネットワーク管理の隔離検証は、別の network / mount / UTS namespace と専用 D-Bus を使用します。namespace が別であることを検証ツールが確認してから実行します。

```bash
sudo unshare --net --mount --uts --propagation private --fork \
  python3 tests/integration/verify-pi-network.py \
  --work /tmp/pilink-network-check \
  --helper "$PWD/pi/pilink-network.py" \
  --binary /usr/local/libexec/hev-socks5-tunnel
```

## ドキュメントの言語

`README.md` と `docs/` の言語サフィックスなしの Markdown は英語の正本です。日本語版は対応する `.ja.md` に置きます。内容を変更するときは英語版を先に更新し、日本語版にも反映してください。各版の内部リンクは同じ言語の文書を参照し、冒頭の言語切り替えリンクで対応する版へ移動できるようにします。

## 生成物とローカル記録

APK、Mac アプリ、C のビルド結果、テスト結果 JSON・ログは `build/` に保存し、Git に含めません。Gradle / Swift / Python のキャッシュ、SDK パス設定、署名鍵も除外します。Gradle Wrapper の JAR はビルドに必要なため含めます。

個別端末の作業記録や調査用コードは `.local/` に保管し、再利用できるテストを `tests/integration/` に置きます。
