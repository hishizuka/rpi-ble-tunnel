# Protocol

English (primary) | [日本語](protocol.ja.md)

This document defines the GATT, L2CAP, framing, and connection behavior needed for interoperability between the Pi, Android, and Mac. See the [README](../README.md) for normal usage, [operations and network management](operations.md) for TUN / SOCKS setup, and [development and testing](development.md) for verification.

## Roles and modes

The Pi is the Peripheral, GATT server, and L2CAP listener. Android / Mac is the Central, GATT client, and L2CAP initiator. GATT provides connection metadata; one L2CAP CoC carries the data.

| Daemon mode | capabilities | Transport |
| --- | --- | --- |
| `echo` | 1 (bit 0) | Byte echo for testing |
| `ssh` | 2 (bit 1) | One SSH connection without framing |
| `mux` | 4 (bit 2) | Multiplexed SSH connections |
| `internet` (default) | 8 (bit 3) | Multiplexed SSH and outbound TCP from the Pi |

The mode is fixed for each daemon instance. Automatic client selection checks supported bits in the order Internet → mux → SSH and rejects peers advertising only echo or unknown bits. Explicit mode selection also checks the required bit before transferring data. All modes use `protocol_version` `1`; capabilities distinguish the transport formats.

## GATT and hostnames

Service UUID: `6f6d0001-8e6d-4c8a-a8bf-5b8a2a786a21`

Characteristics are read-only. Integers use little-endian byte order.

| Purpose | UUID | Type | Value |
| --- | --- | --- | --- |
| protocol_version | `6f6d0002-8e6d-4c8a-a8bf-5b8a2a786a21` | uint16 | `1` |
| l2cap_psm | `6f6d0003-8e6d-4c8a-a8bf-5b8a2a786a21` | uint16 | PSM allocated at startup |
| capabilities | `6f6d0004-8e6d-4c8a-a8bf-5b8a2a786a21` | uint32 | Bit corresponding to the mode |
| hostname | `6f6d0005-8e6d-4c8a-a8bf-5b8a2a786a21` | ASCII bytes | Lowercase DNS label, 1–63 characters, without `.local` |

The Pi's default BLE name is its system hostname. Even when the advertised name is shortened, the hostname Characteristic provides the complete name for Android registration and peer verification. A different Pi sharing the shortened name's prefix is excluded, and discovery continues. `--name` overrides the advertised name for legacy clients.

Clients read GATT values sequentially and validate lengths, version, PSM, supported capabilities, and hostname before starting the relay. Late callbacks from closed connections are ignored. On API 33 and later, Android copies the value passed to the read callback; on older APIs, it copies the Characteristic value at callback time.

Android maintains connections to older Pi versions using the legacy Bluetooth name stored in migrated registrations. New automatic registration requires an updated Pi providing the hostname Characteristic. The added Characteristic does not change the existing version, PSM, capabilities, or framing.

BlueZ's ObjectManager is at `/org/pilink`, the Service at `/org/pilink/service0`, its Characteristics at `char0` through `char3`, and advertising at `/org/pilink/advertisement0`. GATT and advertising are registered asynchronously after the L2CAP listener is created. If BlueZ or the system bus stops, the daemon exits and systemd restarts it.

## L2CAP CoC

- Linux uses `AF_BLUETOOTH / SOCK_SEQPACKET / BTPROTO_L2CAP`, an LE public address, and `BT_MODE_LE_FLOWCTL`.
- Binding with PSM `0` allocates a value in the dynamic range `0x80..0xff`; that value is published through GATT.
- The Pi's receive MTU is 16384 and its receive buffer is 65535 bytes. `MSG_TRUNC` is an error. Outgoing SDUs are split to fit the connection's `BT_SNDMTU`.
- Mac uses `CBPeripheral.openL2CAPChannel()` and Foundation InputStream / OutputStream.
- Android uses `BluetoothDevice.createInsecureL2capChannel(psm)`.

Linux SDU boundaries do not match client read / write boundaries. The frames below are reconstructed from a byte stream. Each daemon accepts one L2CAP connection at a time and accepts another after disconnection.

The security level is `BT_SECURITY_LOW`, so pairing is not required. OpenSSH handles SSH authentication, encryption, and host-key verification; applications handle HTTPS encryption. SSH connects to `127.0.0.1:22` on the Pi. The client's SSH listener binds to `127.0.0.1`.

## Multiplexed frames

mux and Internet use the same framing. Integers are little-endian, except that Internet destination ports use network byte order.

| Offset | Bytes | Contents |
| --- | --- | --- |
| 0 | 2 | Magic: ASCII `PL` (`50 4c`) |
| 2 | 1 | Framing version: `1` |
| 3 | 1 | Type |
| 4 | 4 | Stream ID |
| 8 | 4 | Payload length |
| 12 | length | Payload, at most 1024 bytes |

| Type | Value | Payload | Meaning |
| --- | --- | --- | --- |
| OPEN | 1 | uint32 = 16384 | Advertise the Central's receive window and request a Pi SSH connection |
| OPEN_OK | 2 | uint32 = 16384 | Report connection success and advertise the receive window |
| DATA | 3 | 1..1024 bytes | TCP bytes |
| WINDOW | 4 | uint32 > 0 | Return receive credit for bytes actually written to TCP |
| FIN | 5 | None | TCP EOF in this sending direction |
| RESET | 6 | uint32 reason | Abort the stream |
| PING | 7 | uint32 token | Link control |
| PONG | 8 | Same token | Reply to PING |
| OPEN_TCP | 9 | Receive window + SOCKS destination | Internet only: request outbound TCP from the Pi |

The Central assigns SSH stream IDs `1, 3, 5, ...`. In Internet mode, the Pi assigns outbound TCP IDs `2, 4, 6, ...`. IDs increase independently in each direction and are never reused within a transport session. PING / PONG use ID `0`; other frames use nonzero IDs. mux accepts only SSH on odd IDs and does not accept arbitrary destinations or OPEN_TCP from the Pi.

OPEN / OPEN_OK always advertises an initial receive window of 16384. DATA is sent only after OPEN_OK. For example, OPEN for stream 1 is `50 4c 01 01 01 00 00 00 04 00 00 00 00 40 00 00`.

### Internet destinations and SOCKS5

The OPEN_TCP payload consists of a uint32 little-endian receive window of 16384 followed by SOCKS ATYP / DST.ADDR / DST.PORT. Destinations may be IPv4, IPv6, or domain names; the destination port uses network byte order. After successful DNS resolution and TCP connection, the Central sends OPEN_OK. DATA / WINDOW / FIN and half-close then follow the shared rules.

The Pi's SOCKS5 listener binds only to IPv4 loopback and supports NO AUTH and TCP CONNECT. BIND and UDP ASSOCIATE receive reply 7; unsupported address types receive reply 8; unsupported authentication receives `05 ff`. CONNECT replies use `0.0.0.0:0` for BND.ADDR / BND.PORT.

### Flow control and shutdown

Receive windows are independent for each direction and stream. Sending DATA subtracts from send credit; receiving WINDOW adds credit. At zero credit, TCP reads stop. WINDOW is returned only after received data is written to local TCP, rather than on receipt alone.

Each stream's receive queue is 16 KiB; Mac / Android send queues are also 16 KiB. The Pi's pending send data is at most 1024 bytes. Pending wire output is limited to one frame (1036 bytes); another frame cannot be inserted while a frame is being sent in fragments.

Streams are scheduled one frame at a time in round-robin order, and new OPEN frames are sent in ID order. Within a stream, RESET / WINDOW precede DATA, and FIN follows buffered DATA. An unsent OPEN is canceled locally without sending RESET for an ID the peer has never seen. The Pi queues at most 16 RESET replies to excess OPEN requests and one PONG; exceeding these limits is a protocol error.

After receiving FIN, buffered data is delivered to TCP before `shutdown(SHUT_WR)`; the opposite direction continues. The stream slot is released after FIN in both directions and delivery of queued data. Streams still shutting down count toward the connection limit. L2CAP / GATT remains connected after all streams finish.

DATA / WINDOW / FIN / RESET frames arriving for old IDs after RESET are ignored. Unissued IDs, invalid headers, receive-window overruns, excessive WINDOW credit, DATA after FIN, and duplicate OPEN / OPEN_OK / FIN are protocol errors. DNS / TCP failures close only the affected connection; wire disconnection or a protocol error closes every stream and the SOCKS listener.

| RESET reason | Meaning |
| --- | --- |
| 1 | Pi SSH connection failure, I/O error, or connection timeout |
| 2 | Connection limit exceeded |
| 3 | Mac / Android TCP I/O error |
| 4 | DNS failure (Internet) |
| 5 | Connection refused (Internet) |
| 6 | Timeout (Internet) |
| 7 | Unreachable destination (Internet) |

Receiving RESET closes the stream regardless of the reason. PING / PONG encoding, decoding, and replies are implemented, but periodic keepalive and reply timeouts are not configured. SSH sessions have no duration limit. See the [README](../README.md#usage) for client behavior after disconnection.

## Connection limits and timeouts

| Item | Limit or behavior |
| --- | --- |
| mux / Internet TCP streams | Eight total. Internet permits at most seven outbound TCP streams, reserving one slot for SSH |
| SOCKS negotiation / waiting slots | At most 128. Excess new sockets are closed; existing streams remain active |
| SOCKS negotiation | 10 seconds |
| Waiting for a stream slot after CONNECT | FIFO, at most 30 seconds. Expiration returns SOCKS reply 6 |
| Pi waiting for OPEN_OK | 30 seconds |
| Pi connecting to local sshd | 10 seconds |
| Mac / Android DNS + outbound TCP connection | 10 seconds total. Two DNS workers; at most eight running or queued jobs combined |

A delayed OS DNS cancellation does not create more workers, and no socket is created after the deadline. Android shares DNS workers across reconnections.

## Compatibility and test modes

- `echo` returns received bytes unchanged. Verification compares concatenated byte sequences regardless of SDU / read / write boundaries.
- `ssh` relays one TCP connection without framing. Bounded queues in each direction stop reads only in the blocked direction. It delivers buffered data before closing the whole channel at EOF and does not support independent TCP half-close.
- Between single-SSH sessions, GATT reconnects and PSM is read again. Mac isolates old callbacks from the new session. Android waits for workers to exit, closes GATT, and reconnects after 0.5 seconds. The next L2CAP connection opens when local TCP connects. One TCP connection may wait; Android initial connection and L2CAP connection each time out after 30 seconds.

## References

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
