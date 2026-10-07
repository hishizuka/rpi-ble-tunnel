import Foundation
import PiLinkCore

// A standalone runner also works with Command Line Tools, which do not ship XCTest.
func check(_ condition: @autoclosure () throws -> Bool, _ message: String) rethrows {
    guard try condition() else { fatalError(message) }
}

func expectThrow(_ operation: () throws -> Void) {
    do { try operation() } catch { return }
    fatalError("Expected operation to throw")
}

let discovery = try PiLinkProfile.decode(version: Data([1, 0]), psm: Data([128, 0]),
                                          capabilities: Data([1, 0, 0, 128]))
check(discovery.version == 1 && discovery.psm == 128 && discovery.capabilities == 0x80000001,
      "Little-endian discovery failed")
print("PASS little-endian discovery and future capability bits")

for (version, psm, capabilities) in [
    ([1], [128, 0], [1, 0, 0, 0]),
    ([2, 0], [128, 0], [1, 0, 0, 0]),
    ([1, 0], [0, 1], [1, 0, 0, 0]),
    ([1, 0], [127, 0], [1, 0, 0, 0]),
    ([1, 0], [128, 0], [0, 0, 0, 0])
] as [([UInt8], [UInt8], [UInt8])] {
    expectThrow {
        _ = try PiLinkProfile.decode(version: Data(version), psm: Data(psm), capabilities: Data(capabilities))
    }
}
print("PASS incompatible and malformed discovery rejected")

let payload = EchoVerifier.binaryPayload(size: 65536)
var echo = try EchoVerifier(payload: payload)
var offset = 0
while offset < payload.count {
    let count = min(511, payload.count - offset)
    try echo.recordSent(count)
    let midpoint = count / 2
    try echo.receive(Array(payload[offset..<(offset + midpoint)]))
    try echo.receive(Array(payload[(offset + midpoint)..<(offset + count)]))
    offset += count
}
check(echo.complete, "Echo failed across arbitrary boundaries")
print("PASS 64 KiB echo across arbitrary read and write boundaries")

var corrupted = try EchoVerifier(payload: [0, 1, 2, 255])
expectThrow { try corrupted.receive([0]) }
try corrupted.recordSent(2)
try corrupted.receive([0])
expectThrow { try corrupted.receive([9]) }
check(corrupted.received == 1, "Corruption advanced receive offset")
expectThrow { try corrupted.receive([1, 2]) }
check(!corrupted.complete, "Incomplete echo marked complete")
print("PASS corruption and unsolicited data rejected")

var truncated = try EchoVerifier(payload: [0, 255])
expectThrow { try truncated.recordSent(0) }
expectThrow { try truncated.recordSent(3) }
try truncated.recordSent(2)
try truncated.receive([0])
check(!truncated.complete, "Truncation marked complete")
expectThrow { _ = try EchoVerifier(payload: []) }
print("PASS truncation and invalid writes rejected")

let sshDiscovery = try PiLinkProfile.decode(version: Data([1, 0]), psm: Data([128, 0]),
                                             capabilities: Data([2, 0, 0, 0]),
                                             requiredCapability: PiLinkProfile.sshCapability)
check(sshDiscovery.capabilities == 2, "SSH capability rejected")
expectThrow {
    _ = try PiLinkProfile.decode(version: Data([1, 0]), psm: Data([128, 0]),
                                capabilities: Data([2, 0, 0, 0]))
}
expectThrow {
    _ = try PiLinkProfile.decode(version: Data([1, 0]), psm: Data([128, 0]),
                                capabilities: Data([1, 0, 0, 0]),
                                requiredCapability: PiLinkProfile.sshCapability)
}
print("PASS incompatible echo and SSH modes rejected before data transfer")

for capability in [PiLinkProfile.internetCapability, PiLinkProfile.muxCapability, PiLinkProfile.sshCapability] {
    let profile = try PiLinkProfile.decode(version: Data([1, 0]), psm: Data([128, 0]),
        capabilities: Data([UInt8(capability), 0, 0, 128]),
        requiredCapability: PiLinkProfile.automaticCapability)
    try check(try PiLinkProfile.selectCapability(profile.capabilities) == capability,
          "Automatic selection rejected an existing transport")
}
try check(try PiLinkProfile.selectCapability(14) == PiLinkProfile.internetCapability,
      "Automatic selection did not prefer Internet")
try check(try PiLinkProfile.selectCapability(6) == PiLinkProfile.muxCapability,
      "Automatic selection did not prefer multiplex over SSH")
print("PASS automatic Internet preference and legacy transport discovery")

for capability in [UInt8(0), 1] {
    expectThrow {
        _ = try PiLinkProfile.decode(version: Data([1, 0]), psm: Data([128, 0]),
            capabilities: Data([capability, 0, 0, 128]),
            requiredCapability: PiLinkProfile.automaticCapability)
    }
}
expectThrow {
    _ = try PiLinkProfile.decode(version: Data([2, 0]), psm: Data([128, 0]),
        capabilities: Data([8, 0, 0, 0]), requiredCapability: PiLinkProfile.automaticCapability)
}
expectThrow {
    _ = try PiLinkProfile.decode(version: Data([1, 0]), psm: Data([0, 1]),
        capabilities: Data([8, 0, 0, 0]), requiredCapability: PiLinkProfile.automaticCapability)
}
print("PASS automatic discovery rejects echo, unknown transports and invalid profiles")

var queue = ByteQueue(capacity: 7)
try queue.append([0, 1, 2, 3, 255])
try queue.consume(3)
try queue.append([4, 5, 6, 7, 8])
check(queue.remainingCapacity == 0, "Queue capacity accounting failed")
expectThrow { try queue.append([9]) }
let remaining = queue.withReadableBytes { Array(UnsafeBufferPointer(start: $0, count: $1)) }
check(remaining == [3, 255, 4, 5, 6, 7, 8], "Partial writes changed byte order")
expectThrow { try queue.consume(8) }
try queue.consume(7)
check(queue.isEmpty && queue.remainingCapacity == 7, "Queue did not reset after draining")
try queue.append([0, 255])
check(queue.count == 2, "Queue reuse failed")
print("PASS bounded queues, partial writes, byte ordering and reuse")

try runMuxTests()

try runInternetTests()
