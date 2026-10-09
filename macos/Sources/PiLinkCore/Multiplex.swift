import Foundation

public enum MuxType: UInt8 {
    case open = 1, ok, data, window, fin, reset, ping, pong, openTCP
}

public struct MuxFrame {
    public static let headerSize = 12
    public static let dataSize = 1024
    public let type: MuxType
    public let id: UInt32
    public let payload: [UInt8]

    public init(_ type: MuxType, id: UInt32, payload: [UInt8] = []) {
        self.type = type; self.id = id; self.payload = payload
    }

    public init(_ type: MuxType, id: UInt32, value: UInt32) {
        self.init(type, id: id, payload: Self.bytes(value))
    }

    public static func bytes(_ value: UInt32) -> [UInt8] {
        (0..<4).map { UInt8(truncatingIfNeeded: value >> ($0 * 8)) }
    }

    public static func integer(_ bytes: ArraySlice<UInt8>) -> UInt32 {
        bytes.enumerated().reduce(0) { $0 | UInt32($1.element) << ($1.offset * 8) }
    }

    public var value: UInt32 { Self.integer(payload[...]) }
    public var encoded: [UInt8] {
        [80, 76, 1, type.rawValue] + Self.bytes(id) + Self.bytes(UInt32(payload.count)) + payload
    }
}

public struct MuxDecoder {
    private var bytes = [UInt8]()
    private var target = MuxFrame.headerSize
    private let allowInternet: Bool
    public var isEmpty: Bool { bytes.isEmpty }
    public init(allowInternet: Bool = false) { self.allowInternet = allowInternet }

    public mutating func feed(_ input: [UInt8]) throws -> [MuxFrame] {
        var frames = [MuxFrame]()
        var offset = 0
        while offset < input.count {
            let count = min(target - bytes.count, input.count - offset)
            bytes.append(contentsOf: input[offset..<(offset + count)])
            offset += count
            guard bytes.count == target else { continue }
            guard bytes[0...2] == [80, 76, 1], let type = MuxType(rawValue: bytes[3]) else {
                throw PiLinkError.invalid("多重化フレームの magic / version / type が不正です")
            }
            let length = Int(MuxFrame.integer(bytes[8..<12]))
            guard length <= MuxFrame.dataSize else { throw PiLinkError.invalid("多重化フレームが大きすぎます") }
            if target == MuxFrame.headerSize && length > 0 {
                target += length
                continue
            }
            let id = MuxFrame.integer(bytes[4..<8])
            let control = type == .ping || type == .pong
            guard control ? id == 0 : id > 0 && (allowInternet || id & 1 == 1),
                  type != .openTCP || allowInternet && id & 1 == 0 else {
                throw PiLinkError.invalid("多重化 stream ID が不正です")
            }
            let validLength = type == .openTCP ? length >= 9 : type == .data ? length > 0 : type == .fin ? length == 0 : length == 4
            guard validLength else { throw PiLinkError.invalid("多重化フレームの長さが不正です") }
            frames.append(MuxFrame(type, id: id, payload: Array(bytes.dropFirst(MuxFrame.headerSize))))
            bytes.removeAll(keepingCapacity: true)
            target = MuxFrame.headerSize
        }
        return frames
    }
}

public final class MuxStream {
    public let id: UInt32
    public fileprivate(set) var acknowledged = false
    public fileprivate(set) var remoteEOF = false
    public fileprivate(set) var localEOF = false
    public fileprivate(set) var received = ByteQueue()
    public fileprivate(set) var destination: MuxDestination?
    fileprivate var outgoing = ByteQueue()
    fileprivate var credit = 0
    fileprivate var receiveCredit = 16384
    fileprivate var window = 0
    fileprivate var openSent = false
    fileprivate var finSent = false
    fileprivate var reset: UInt32?
    fileprivate var connected = false
    public var readAllowance: Int {
        acknowledged && !localEOF && reset == nil ? credit - outgoing.count : 0
    }
    fileprivate init(id: UInt32) { self.id = id }
}

// All methods are confined to the caller's event loop.
public final class Multiplexer {
    public static let streamLimit = 8
    public static let windowSize = 16384
    private var nextID: UInt32 = 1
    private var cursor: UInt32 = 0
    private var table = [UInt32: MuxStream]()
    private var retired = [UInt32]()
    private var pong: UInt32?
    private let allowInternet: Bool
    private var highestEven: UInt32 = 0
    private var rejected = [UInt32]()
    public var streams: [MuxStream] { table.values.sorted { $0.id < $1.id } }
    public init(allowInternet: Bool = false) { self.allowInternet = allowInternet }

    public func open() throws -> MuxStream {
        guard table.count < Self.streamLimit, nextID < UInt32.max else {
            throw PiLinkError.invalid("多重化の接続数または stream ID の上限に達しました")
        }
        let stream = MuxStream(id: nextID)
        nextID += 2
        table[stream.id] = stream
        return stream
    }

    public func queue(_ bytes: [UInt8], for stream: MuxStream) throws {
        guard table[stream.id] === stream, !bytes.isEmpty, bytes.count <= stream.readAllowance else {
            throw PiLinkError.invalid("多重化の送信枠を超えました")
        }
        try stream.outgoing.append(bytes)
    }

    public func consume(_ count: Int, for stream: MuxStream) throws {
        try stream.received.consume(count)
        stream.window += count
        reap(stream)
    }

    public func eof(_ stream: MuxStream) { stream.localEOF = true }
    public func connected(_ stream: MuxStream) { stream.connected = true }
    public func cancel(_ stream: MuxStream, reason: UInt32 = 3) {
        if !stream.openSent { retire(stream); return }
        stream.reset = reason
        stream.outgoing = ByteQueue()
        stream.received = ByteQueue()
    }

    private func retire(_ stream: MuxStream) {
        table.removeValue(forKey: stream.id)
        retired.append(stream.id)
    }

    private func reap(_ stream: MuxStream) {
        if stream.finSent && stream.remoteEOF && stream.received.isEmpty { retire(stream) }
    }

    public func takeRetired() -> [UInt32] {
        let result = retired
        retired.removeAll(keepingCapacity: true)
        return result
    }

    public func receive(_ frame: MuxFrame) throws {
        if frame.type == .ping || frame.type == .pong {
            if frame.type == .ping {
                guard pong == nil else { throw PiLinkError.invalid("PING の待機枠を超えました") }
                pong = frame.value
            }
            return
        }
        guard frame.type != .open else { throw PiLinkError.invalid("Pi からの OPEN は未対応です") }
        if frame.type == .openTCP {
            guard allowInternet, frame.id > highestEven, frame.id & 1 == 0 else {
                throw PiLinkError.invalid("Pi からの OPEN_TCP の ID が不正です")
            }
            let destination = try MuxDestination(payload: frame.payload)
            highestEven = frame.id
            guard table.count < Self.streamLimit else {
                guard rejected.count < 16 else { throw PiLinkError.invalid("OPEN_TCP の拒否待機枠を超えました") }
                rejected.append(frame.id)
                return
            }
            let stream = MuxStream(id: frame.id)
            stream.destination = destination
            stream.openSent = true
            stream.receiveCredit = 0
            stream.credit = Self.windowSize
            table[frame.id] = stream
            return
        }
        guard frame.id & 1 == 1 || allowInternet else { throw PiLinkError.invalid("stream ID が不正です") }
        guard let stream = table[frame.id] else {
            guard frame.id & 1 == 1 ? frame.id < nextID : frame.id <= highestEven else {
                throw PiLinkError.invalid("未発行の stream ID を受信しました")
            }
            return
        }
        if frame.type == .reset { retire(stream); return }
        if stream.reset != nil { return }
        switch frame.type {
        case .ok:
            guard stream.destination == nil, stream.openSent, !stream.acknowledged, frame.value == UInt32(Self.windowSize) else {
                throw PiLinkError.invalid("OPEN_OK が不正です")
            }
            stream.acknowledged = true
            stream.credit = Self.windowSize
        case .data:
            guard stream.acknowledged, !stream.remoteEOF, frame.payload.count <= stream.receiveCredit else {
                throw PiLinkError.invalid("受信枠を超える DATA または FIN 後の DATA です")
            }
            try stream.received.append(frame.payload)
            stream.receiveCredit -= frame.payload.count
        case .window:
            let value = frame.value
            guard stream.acknowledged, value > 0, value <= UInt32(Self.windowSize - stream.credit) else {
                throw PiLinkError.invalid("WINDOW が不正です")
            }
            stream.credit += Int(value)
        case .fin:
            guard stream.acknowledged, !stream.remoteEOF else { throw PiLinkError.invalid("FIN が不正です") }
            stream.remoteEOF = true
            reap(stream)
        default: throw PiLinkError.invalid("予期しない多重化フレームです")
        }
    }

    public func nextFrame() throws -> MuxFrame? {
        if !rejected.isEmpty { return MuxFrame(.reset, id: rejected.removeFirst(), value: 2) }
        if let value = pong {
            pong = nil
            return MuxFrame(.pong, id: 0, value: value)
        }
        let ordered = streams
        // New OPEN IDs remain ordered independently of round-robin DATA traffic.
        if let opening = ordered.first(where: { !$0.openSent }) {
            opening.openSent = true
            cursor = opening.id
            return MuxFrame(.open, id: opening.id, value: UInt32(Self.windowSize))
        }
        let candidates = ordered.filter { $0.id > cursor } + ordered.filter { $0.id <= cursor }
        for stream in candidates {
            var frame: MuxFrame?
            if let reason = stream.reset {
                frame = MuxFrame(.reset, id: stream.id, value: reason)
                retire(stream)
            } else if stream.destination != nil && stream.connected && !stream.acknowledged {
                stream.acknowledged = true
                stream.receiveCredit = Self.windowSize
                frame = MuxFrame(.ok, id: stream.id, value: UInt32(Self.windowSize))
            } else if stream.acknowledged && stream.window > 0 {
                let credit = stream.window
                stream.window = 0
                stream.receiveCredit += credit
                frame = MuxFrame(.window, id: stream.id, value: UInt32(credit))
            } else if stream.acknowledged && !stream.outgoing.isEmpty {
                let bytes = stream.outgoing.withReadableBytes {
                    Array(UnsafeBufferPointer(start: $0, count: min($1, MuxFrame.dataSize)))
                }
                try stream.outgoing.consume(bytes.count)
                stream.credit -= bytes.count
                frame = MuxFrame(.data, id: stream.id, payload: bytes)
            } else if stream.acknowledged && stream.localEOF && !stream.finSent {
                stream.finSent = true
                frame = MuxFrame(.fin, id: stream.id)
                reap(stream)
            }
            if let frame { cursor = stream.id; return frame }
        }
        return nil
    }
}
