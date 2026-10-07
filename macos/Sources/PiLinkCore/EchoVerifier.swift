import Foundation

public struct EchoVerifier {
    public let payload: [UInt8]
    public private(set) var sent = 0
    public private(set) var received = 0
    public var complete: Bool { received == payload.count && sent == payload.count }

    public init(payload: [UInt8]) throws {
        guard !payload.isEmpty else { throw PiLinkError.invalid("送信データが空です") }
        self.payload = payload
    }

    public static func binaryPayload(size: Int) -> [UInt8] {
        // A deterministic PRNG detects reordered data better than a repeating byte ramp.
        var state: UInt32 = 0x70694c6b
        return (0..<size).map { _ in
            state ^= state << 13
            state ^= state >> 17
            state ^= state << 5
            return UInt8(truncatingIfNeeded: state)
        }
    }

    public mutating func recordSent(_ count: Int) throws {
        guard count > 0, count <= payload.count - sent else {
            throw PiLinkError.invalid("送信バイト数が不正です")
        }
        sent += count
    }

    public mutating func receive(_ data: [UInt8]) throws {
        guard data.count <= sent - received else {
            throw PiLinkError.invalid("echo が送信済みのバイト数を超えました")
        }
        for (index, byte) in data.enumerated() {
            guard byte == payload[received + index] else {
                throw PiLinkError.invalid("echo のデータ不一致: offset=\(received + index)")
            }
        }
        received += data.count
    }
}
