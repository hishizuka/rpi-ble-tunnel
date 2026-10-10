import Foundation
import RpiBleTunnelCore

func runMuxTests() throws {
    let payload = EchoVerifier.binaryPayload(size: MuxFrame.dataSize)
    let inputFrames = [MuxFrame(.open, id: 1, value: 16384), MuxFrame(.data, id: 3, payload: payload),
                       MuxFrame(.fin, id: 3), MuxFrame(.ping, id: 0, value: 0xdeadbeef)]
    let encoded = inputFrames.flatMap { $0.encoded }
    for size in [1, 3, 11, 12, 127, 4096] {
        var decoder = MuxDecoder()
        var output = [MuxFrame]()
        for offset in stride(from: 0, to: encoded.count, by: size) {
            output += try decoder.feed(Array(encoded[offset..<min(encoded.count, offset + size)]))
        }
        check(output.map { $0.encoded } == inputFrames.map { $0.encoded } && decoder.isEmpty,
              "Mux decoder corrupted split or coalesced frames")
    }
    print("PASS multiplex binary frames split at 1/3/11/12/127/4096 bytes")

    for frame in [MuxFrame(.open, id: 0, value: 16384), MuxFrame(.open, id: 2, value: 16384),
                  MuxFrame(.data, id: 1), MuxFrame(.fin, id: 1, payload: [0]),
                  MuxFrame(.window, id: 1, payload: [1]), MuxFrame(.ping, id: 1, value: 0)] {
        expectThrow { var decoder = MuxDecoder(); _ = try decoder.feed(frame.encoded) }
    }
    for offset in [0, 1, 2, 3] {
        var bytes = MuxFrame(.fin, id: 1).encoded
        bytes[offset] = 255
        expectThrow { var decoder = MuxDecoder(); _ = try decoder.feed(bytes) }
    }
    expectThrow {
        var decoder = MuxDecoder()
        _ = try decoder.feed([80, 76, 1, 3, 1, 0, 0, 0, 255, 255, 255, 255])
    }
    var truncated = MuxDecoder()
    _ = try truncated.feed(Array(encoded.dropLast()))
    check(!truncated.isEmpty, "Truncated frame disappeared")
    print("PASS malformed multiplex headers, sizes, IDs and truncation")

    let mux = Multiplexer()
    let first = try mux.open(), second = try mux.open()
    try check(try mux.nextFrame()!.id == 1, "First OPEN ID incorrect")
    try check(try mux.nextFrame()!.id == 3, "Second OPEN ID incorrect")
    try mux.receive(MuxFrame(.ok, id: first.id, value: 16384))
    try mux.receive(MuxFrame(.ok, id: second.id, value: 16384))
    expectThrow { try mux.receive(MuxFrame(.ok, id: 1, value: 16384)) }
    try mux.queue(EchoVerifier.binaryPayload(size: 16384), for: first)
    try mux.queue(payload, for: second)
    try check(try mux.nextFrame()!.id == 1, "Fair scheduler skipped first stream")
    try check(try mux.nextFrame()!.id == 3, "Bulk stream starved another stream")
    for _ in 0..<15 { try check(try mux.nextFrame()!.id == 1, "Bulk data lost") }
    try check(first.readAllowance == 0 && (try mux.nextFrame()) == nil, "Zero credit did not stop TCP reads")
    try mux.queue(payload, for: second)
    try check(try mux.nextFrame()!.id == 3, "Blocked stream blocked independent stream")
    expectThrow { try mux.receive(MuxFrame(.window, id: 1, value: 16385)) }
    try mux.receive(MuxFrame(.window, id: 1, value: 1024))
    check(first.readAllowance == 1024, "WINDOW did not restore exact credit")
    print("PASS per-stream credits, fairness and stalled-stream isolation")

    for _ in 0..<16 { try mux.receive(MuxFrame(.data, id: 1, payload: payload)) }
    expectThrow { try mux.receive(MuxFrame(.data, id: 1, payload: [1])) }
    check(first.received.count == 16384, "Receive buffer not bounded")
    try mux.consume(127, for: first)
    let window = try mux.nextFrame()!
    check(window.type == .window && window.value == 127, "Delivery failed to return exact credit")
    try mux.receive(MuxFrame(.data, id: 1, payload: Array(payload.prefix(127))))
    check(first.received.count == 16384, "Returned credit accounting failed")
    try mux.queue(payload, for: first)
    mux.eof(first)
    try check(try mux.nextFrame()!.type == .data, "FIN overtook queued data")
    try check(try mux.nextFrame()!.type == .fin, "EOF was not framed")
    try mux.receive(MuxFrame(.fin, id: 1))
    check(mux.streams.contains { $0.id == 1 }, "Unread tail was discarded at FIN")
    try mux.consume(16384, for: first)
    check(mux.takeRetired() == [1], "Drained half-closed stream did not retire")
    try mux.receive(MuxFrame(.window, id: 1, value: 1))
    check(mux.streams.count == 1, "Late frame interfered with surviving stream")
    mux.cancel(second)
    try check(try mux.nextFrame()!.type == .reset, "Cancellation not sent")
    check(mux.takeRetired() == [3], "RESET did not retire socket")
    let third = try mux.open()
    check(third.id == 5, "Stream ID reused")
    print("PASS receive bounds, delivered-byte credit, ordered FIN, tail drain and RESET")

    let limited = Multiplexer()
    for _ in 0..<8 { _ = try limited.open() }
    expectThrow { _ = try limited.open() }
    expectThrow { try limited.receive(MuxFrame(.data, id: 99, payload: [1])) }
    let muxDiscovery = try RpiBleTunnelProfile.decode(version: Data([1, 0]), psm: Data([128, 0]),
        capabilities: Data([4, 0, 0, 0]), requiredCapability: RpiBleTunnelProfile.muxCapability)
    check(muxDiscovery.capabilities == 4, "Mux discovery rejected")
    expectThrow {
        _ = try RpiBleTunnelProfile.decode(version: Data([1, 0]), psm: Data([128, 0]),
            capabilities: Data([2, 0, 0, 0]), requiredCapability: RpiBleTunnelProfile.muxCapability)
    }
    print("PASS eight-stream limit, unknown stream and legacy-mode compatibility")
}
