# 通信仕様

[English (primary)](protocol.md) | 日本語

Pi・Android・Mac 間の互換性に関わる GATT、L2CAP、フレームと接続制御をまとめます。通常の利用手順は [README](../README.ja.md)、TUN / SOCKS の運用は[運用・ネットワーク管理](operations.ja.md)、検証方法は[開発・検証](development.ja.md)を参照してください。

## 役割とモード

Pi は Peripheral / GATT server / L2CAP listener、Android / Mac は Central / GATT client / L2CAP initiator です。GATT で接続先の情報を読み、データは1本の L2CAP CoC で転送します。

| daemon のモード | capabilities | 転送内容 |
| --- | --- | --- |
| `echo` | 1（bit 0） | 検証用のバイト列 echo |
| `ssh` | 2（bit 1） | 単一 SSH 接続。フレームなし |
| `mux` | 4（bit 2） | SSH 接続の多重化 |
| `internet`（既定） | 8（bit 3） | SSH と Pi の外向き TCP の多重化 |

モードは daemon ごとに固定です。クライアントの自動接続は Internet → mux → SSH の順で対応 bit を選び、echo だけまたは未知 bit だけの接続先は拒否します。明示指定でも必要な bit をデータ転送前に確認します。`protocol_version` はすべて `1` ですが、capabilities によって転送方式を区別します。

## GATT とホスト名

Service UUID: `6f6d0001-8e6d-4c8a-a8bf-5b8a2a786a21`

Characteristic は read-only、整数は little-endian です。

| 用途 | UUID | 型 | 値 |
| --- | --- | --- | --- |
| protocol_version | `6f6d0002-8e6d-4c8a-a8bf-5b8a2a786a21` | uint16 | `1` |
| l2cap_psm | `6f6d0003-8e6d-4c8a-a8bf-5b8a2a786a21` | uint16 | 起動時に割り当てた PSM |
| capabilities | `6f6d0004-8e6d-4c8a-a8bf-5b8a2a786a21` | uint32 | モードに対応する bit |
| hostname | `6f6d0005-8e6d-4c8a-a8bf-5b8a2a786a21` | ASCII bytes | 小文字の DNS label、1〜63文字、`.local` なし |

Pi の BLE 名の既定はシステムのホスト名です。広告名が省略されても hostname Characteristic から完全な名前を取得し、Android の登録と接続先確認に使います。省略名の接頭辞が一致する別の Pi は除外して検索を継続します。`--name` は旧クライアント用の広告名上書きです。

クライアントは GATT の値を順に読み、長さ・version・PSM・対応 capability・ホスト名を検証してから転送を開始します。閉じた接続から遅れて届く callback は無視します。Android は API 33 以上の read callback では引数の値を、旧 API では callback 時点の値をコピーします。

旧 Pi への Android 接続は、移行済み登録に保存した従来の Bluetooth 名を使って維持します。新規の自動登録には hostname Characteristic を提供する Pi 側の更新が必要です。追加 Characteristic により既存の version・PSM・capabilities やフレームは変更していません。

BlueZ の ObjectManager は `/org/pilink`、Service は `/org/pilink/service0`、Characteristic は `char0`〜`char3`、広告は `/org/pilink/advertisement0` です。L2CAP listener の作成後に GATT / 広告を非同期登録します。BlueZ または system bus の停止時は daemon も終了し、systemd が再起動します。

## L2CAP CoC

- Linux は `AF_BLUETOOTH / SOCK_SEQPACKET / BTPROTO_L2CAP`、LE public address、`BT_MODE_LE_FLOWCTL` を使います。
- PSM `0` で bind し、動的範囲 `0x80..0xff` の割り当て結果を GATT に公開します。
- Pi の受信 MTU は16384、受信 buffer は65535バイトです。`MSG_TRUNC` はエラーにし、送信 SDU は接続ごとの `BT_SNDMTU` 以下に分割します。
- Mac は `CBPeripheral.openL2CAPChannel()` と Foundation の InputStream / OutputStream を使います。
- Android は `BluetoothDevice.createInsecureL2capChannel(psm)` を使います。

Linux の SDU 境界とクライアントの read / write 境界は一致しません。下記のフレームは byte stream として復元します。1 daemon の同時 L2CAP 接続は1本で、切断後は次の接続を受け付けます。

security level は `BT_SECURITY_LOW` でペアリングを要求しません。SSH の認証・暗号化・ホスト鍵確認は OpenSSH、HTTPS の暗号化は利用アプリが担当します。SSH の宛先は Pi の `127.0.0.1:22`、クライアントの SSH 待受は `127.0.0.1` です。

## 多重化フレーム

mux / Internet 共通の形式です。整数は little-endian です。Internet の宛先ポートだけは network byte order を使います。

| Offset | Bytes | 内容 |
| --- | --- | --- |
| 0 | 2 | magic: ASCII `PL`（`50 4c`） |
| 2 | 1 | framing version: `1` |
| 3 | 1 | type |
| 4 | 4 | stream ID |
| 8 | 4 | payload length |
| 12 | length | payload（最大1024バイト） |

| Type | 値 | Payload | 意味 |
| --- | --- | --- | --- |
| OPEN | 1 | uint32 = 16384 | Central の受信枠を提示し、Pi の SSH 接続を要求 |
| OPEN_OK | 2 | uint32 = 16384 | 接続成功と受信枠 |
| DATA | 3 | 1..1024 bytes | TCP バイト列 |
| WINDOW | 4 | uint32 > 0 | 実際に TCP に書き込んだ分の受信枠を返す |
| FIN | 5 | なし | この送信方向の TCP EOF |
| RESET | 6 | uint32 reason | 該当 stream を中断 |
| PING | 7 | uint32 token | link 制御 |
| PONG | 8 | 同じ token | PING への応答 |
| OPEN_TCP | 9 | 受信枠 + SOCKS 宛先 | Internet 専用。Pi の外向き TCP を要求 |

SSH の stream ID は Central が `1, 3, 5, ...` と発行します。Internet の外向き TCP は Pi が `2, 4, 6, ...` と発行します。それぞれ増加順で同じ transport 内では再利用しません。PING / PONG の ID は `0`、他のフレームは非ゼロです。mux は奇数 ID の SSH だけを扱い、任意の宛先や Pi 発行の OPEN_TCP は受け付けません。

OPEN / OPEN_OK の初期受信枠は16384固定で、DATA は OPEN_OK の後に送ります。例: stream 1 の OPEN は `50 4c 01 01 01 00 00 00 04 00 00 00 00 40 00 00` です。

### Internet の宛先と SOCKS5

OPEN_TCP の payload は uint32 LE の受信枠16384に SOCKS の ATYP / DST.ADDR / DST.PORT を続けます。宛先は IPv4 / IPv6 / ドメイン名で、宛先ポートは network byte order です。Central が DNS / TCP 接続に成功すると OPEN_OK を返し、以後は共通の DATA / WINDOW / FIN と half-close を使います。

Pi の SOCKS5 は IPv4 loopback のみに bind し、NO AUTH と TCP CONNECT を受け付けます。BIND と UDP ASSOCIATE は応答7、未対応アドレス形式は8、未対応認証は `05 ff` で拒否します。CONNECT 応答の BND.ADDR / BND.PORT は `0.0.0.0:0` です。

### 流量制御と終了

各方向・各 stream の受信枠は独立です。DATA 送信時に送信枠を引き、WINDOW 受信時に加算します。枠が0なら TCP の読み取りを止めます。受信しただけでは WINDOW を返さず、ローカル TCP に書き込めた分だけ返します。

受信キューは stream ごとに16 KiB、Mac / Android の送信キューも16 KiB、Pi の送信待機は最大1024バイトです。wire の送信待機は1フレーム（1036バイト）に制限し、分割送信中に別フレームを挿入しません。

stream 間は1フレームずつ round-robin で選び、新しい OPEN は ID 順に送ります。同じ stream の RESET / WINDOW は DATA より先、FIN は保持済み DATA の後です。未送信 OPEN のキャンセルはローカルで処理し、peer が知らない ID に RESET を送りません。Pi の過剰 OPEN に対する RESET 待機は16件、PONG 待機は1件で、超過は protocol error です。

FIN 受信後は保持済みデータを TCP に届けてから `shutdown(SHUT_WR)` し、逆方向は転送を続けます。両方向の FIN とキューの配送完了後に stream 枠を解放します。終了処理中の stream も接続上限に含みます。全 stream が終わっても L2CAP / GATT は維持します。

RESET 後に届いた過去 ID の DATA / WINDOW / FIN / RESET は無視します。未発行 ID、不正ヘッダー、受信枠超過、WINDOW の増幅、FIN 後の DATA、重複 OPEN / OPEN_OK / FIN は protocol error です。DNS / TCP の失敗は該当接続だけを閉じ、wire 切断や protocol error は全 stream と SOCKS listener を閉じます。

| RESET reason | 意味 |
| --- | --- |
| 1 | Pi の SSH 接続失敗・I/O エラー・接続タイムアウト |
| 2 | 接続数超過 |
| 3 | Mac / Android の TCP I/O エラー |
| 4 | DNS 失敗（Internet） |
| 5 | 接続拒否（Internet） |
| 6 | タイムアウト（Internet） |
| 7 | 到達不可（Internet） |

受信した RESET は reason を問わず該当 stream を閉じます。PING / PONG の encode / decode / 応答は実装済みですが、定期 keepalive と応答タイムアウトは未設定です。SSH セッションの時間制限はありません。切断後のクライアントの動作は [README](../README.ja.md#使い方)を参照してください。

## 接続上限とタイムアウト

| 項目 | 上限・動作 |
| --- | --- |
| mux / Internet の TCP stream | 合計8本。Internet の外向き TCP は最大7本で SSH に1枠を残す |
| SOCKS の交渉・待機用スロット | 最大128件。超過時は新規ソケットを閉じ、既存の stream を維持 |
| SOCKS 交渉 | 10秒 |
| CONNECT 受信後の stream 枠待ち | FIFO で30秒。期限超過は SOCKS 応答6 |
| Pi の OPEN_OK 待ち | 30秒 |
| Pi からローカル sshd への接続 | 10秒 |
| Mac / Android の DNS + 外向き TCP 接続 | 合計10秒。DNS worker は2本、実行中・待機中は合計8件以下 |

OS の DNS キャンセルが遅れても worker を増やさず、期限後に socket を作りません。Android は再接続をまたいで DNS worker を共有します。

## 互換性・検証用モード

- `echo` は受信バイト列をそのまま返します。SDU / read / write の境界を問わず、連結したバイト列の一致で検証します。
- `ssh` は1本の TCP をフレームなしで中継します。各方向のキューに上限を設け、詰まった方向の読み取りだけを止めます。EOF までのデータを配送してチャネル全体を閉じるため、独立した TCP half-close は扱いません。
- 単一 SSH のセッション間では GATT を接続し直し、PSM を読み直します。Mac は古い callback を新しいセッションから分離し、Android は worker 終了後に GATT を閉じて0.5秒後に再接続します。次の L2CAP はローカル TCP 接続時に開き、待機 TCP は1本、Android の初期接続・L2CAP 接続は各30秒までです。

## 参照資料

- [BlueZ L2CAP socket API](https://github.com/bluez/bluez/wiki/L2CAP)
- [BlueZ GATT Manager API](https://github.com/bluez/bluez/blob/master/doc/org.bluez.GattManager.rst)
- [BlueZ GATT Characteristic API](https://github.com/bluez/bluez/blob/master/doc/org.bluez.GattCharacteristic.rst)
- [BlueZ Advertising API](https://github.com/bluez/bluez/blob/master/doc/org.bluez.LEAdvertisement.rst)
- [Linux L2CAP socket implementation](https://github.com/torvalds/linux/blob/master/net/bluetooth/l2cap_sock.c)
- [Apple CoreBluetooth openL2CAPChannel](https://developer.apple.com/documentation/corebluetooth/cbperipheral/openl2capchannel(_:))
- [Android BluetoothDevice / L2CAP CoC API](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createInsecureL2capChannel(int))
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)
- [RFC 1928: SOCKS5](https://www.rfc-editor.org/rfc/rfc1928)
