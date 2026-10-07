import Foundation

public struct MuxDestination {
    public let host: String
    public let port: UInt16
    public let addressType: UInt8

    public init(payload: [UInt8]) throws {
        guard payload.count >= 9, MuxFrame.integer(payload[0..<4]) == 16384 else {
            throw PiLinkError.invalid("OPEN_TCP の受信枠または長さが不正です")
        }
        addressType = payload[4]
        let end: Int
        switch addressType {
        case 1:
            end = 9
            guard payload.count == end + 2 else { throw PiLinkError.invalid("IPv4 の長さが不正です") }
            host = payload[5..<end].map(String.init).joined(separator: ".")
        case 4:
            end = 21
            guard payload.count == end + 2 else { throw PiLinkError.invalid("IPv6 の長さが不正です") }
            host = stride(from: 5, to: end, by: 2).map {
                String(UInt16(payload[$0]) << 8 | UInt16(payload[$0 + 1]), radix: 16)
            }.joined(separator: ":")
        case 3:
            end = 6 + Int(payload[5])
            guard payload[5] > 0, payload.count == end + 2,
                  payload[6..<end].allSatisfy({ (33...126).contains($0) }),
                  let name = String(bytes: payload[6..<end], encoding: .ascii) else {
                throw PiLinkError.invalid("接続先のホスト名が不正です")
            }
            host = name
        default: throw PiLinkError.invalid("OPEN_TCP の address type が不正です")
        }
        port = UInt16(payload[end]) << 8 | UInt16(payload[end + 1])
        guard port > 0 else { throw PiLinkError.invalid("接続先ポートが 0 です") }
    }
}
