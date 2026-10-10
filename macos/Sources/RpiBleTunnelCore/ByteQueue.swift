import Foundation

public struct ByteQueue {
    public let capacity: Int
    private var storage = [UInt8]()
    private var offset = 0
    public var count: Int { storage.count - offset }
    public var remainingCapacity: Int { capacity - count }
    public var isEmpty: Bool { count == 0 }

    public init(capacity: Int = 16384) {
        precondition(capacity > 0)
        self.capacity = capacity
    }

    public mutating func append(_ bytes: [UInt8]) throws {
        guard bytes.count <= remainingCapacity else {
            throw RpiBleTunnelError.invalid("通信バッファの上限を超えました")
        }
        if offset > 0 {
            storage.removeFirst(offset)
            offset = 0
        }
        storage.append(contentsOf: bytes)
    }

    public mutating func consume(_ count: Int) throws {
        guard count >= 0, count <= self.count else {
            throw RpiBleTunnelError.invalid("通信バッファの消費量が不正です")
        }
        offset += count
        if offset == storage.count {
            storage.removeAll(keepingCapacity: true)
            offset = 0
        }
    }

    public func withReadableBytes<T>(_ body: (UnsafePointer<UInt8>, Int) throws -> T) rethrows -> T {
        precondition(!isEmpty)
        return try storage.withUnsafeBufferPointer { bytes in
            try body(bytes.baseAddress!.advanced(by: offset), count)
        }
    }
}
