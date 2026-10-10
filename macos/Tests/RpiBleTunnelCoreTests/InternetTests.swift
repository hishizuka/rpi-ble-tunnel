import Foundation
import RpiBleTunnelCore

func runInternetTests() throws {
    let host = Array("localhost".utf8)
    let request = MuxFrame.bytes(16384) + [3, UInt8(host.count)] + host + [1, 187]
    let frame = MuxFrame(.openTCP, id: 2, payload: request)
    for chunk in [1, 3, 11, 12, 127] {
        var decoder = MuxDecoder(allowInternet: true)
        var decoded = [MuxFrame]()
        for offset in stride(from: 0, to: frame.encoded.count, by: chunk) {
            decoded += try decoder.feed(Array(frame.encoded[offset..<min(offset + chunk, frame.encoded.count)]))
        }
        check(decoded.count == 1 && decoded[0].payload == request, "OPEN_TCP split decoding failed")
    }
    var legacy = MuxDecoder()
    expectThrow { _ = try legacy.feed(frame.encoded) }
    expectThrow { _ = try MuxDestination(payload: MuxFrame.bytes(16384) + [3, 0, 0, 22]) }
    expectThrow { _ = try MuxDestination(payload: MuxFrame.bytes(16384) + [3, 1, 0, 0, 22]) }
    expectThrow { _ = try MuxDestination(payload: MuxFrame.bytes(16384) + [1, 127, 0, 0, 1, 0, 0]) }
    let ipv4 = try MuxDestination(payload: MuxFrame.bytes(16384) + [1, 127, 0, 0, 1, 0, 22])
    check(ipv4.host == "127.0.0.1" && ipv4.port == 22, "IPv4 target decoding failed")
    let ipv6 = try MuxDestination(payload: MuxFrame.bytes(16384) + [4] + Array(repeating: 0, count: 15) + [1, 0, 22])
    check(ipv6.host == "0:0:0:0:0:0:0:1", "IPv6 target decoding failed")
    print("PASS reverse OPEN_TCP targets, byte boundaries, invalid addresses and legacy isolation")

    let mux = Multiplexer(allowInternet: true)
    try mux.receive(frame)
    let stream = mux.streams[0]
    check(stream.destination?.host == "localhost" && stream.destination?.port == 443, "DNS target changed")
    check(stream.readAllowance == 0, "Internet stream read before TCP connected")
    expectThrow { try mux.receive(MuxFrame(.data, id: 2, payload: [1])) }
    mux.connected(stream)
    try check(try mux.nextFrame()?.type == .ok, "TCP connection did not acknowledge OPEN_TCP")
    try mux.queue([0, 255], for: stream)
    try check(try mux.nextFrame()?.type == .data, "Internet TCP data missing")
    mux.eof(stream)
    try check(try mux.nextFrame()?.type == .fin, "Internet FIN missing")
    try mux.receive(MuxFrame(.data, id: 2, payload: [7, 8]))
    try mux.receive(MuxFrame(.fin, id: 2))
    check(mux.streams.count == 1, "Half-close discarded reverse response")
    try mux.consume(2, for: stream)
    check(mux.takeRetired() == [2], "Reverse EOF did not release slot")
    try mux.receive(MuxFrame(.window, id: 2, value: 1))
    expectThrow { try mux.receive(frame) }
    print("PASS reverse connect acknowledgment, binary data, half-close, late frames and ID reuse rejection")

    let mixed = Multiplexer(allowInternet: true)
    for i in 1...4 { try mixed.receive(MuxFrame(.openTCP, id: UInt32(i * 2), payload: request)) }
    for _ in 0..<4 { _ = try mixed.open() }
    expectThrow { _ = try mixed.open() }
    try mixed.receive(MuxFrame(.openTCP, id: 10, payload: request))
    let reset = try mixed.nextFrame()!
    check(reset.type == .reset && reset.id == 10 && reset.value == 2, "Shared eight-stream limit failed")
    expectThrow {
        _ = try RpiBleTunnelProfile.decode(version: Data([1, 0]), psm: Data([128, 0]),
            capabilities: Data([8, 0, 0, 0]), requiredCapability: RpiBleTunnelProfile.muxCapability)
    }
    print("PASS shared forward/reverse limit and distinct Internet capability")

    let ordered = Multiplexer(allowInternet: true)
    try ordered.receive(frame)
    ordered.connected(ordered.streams[0])
    _ = try ordered.nextFrame()
    _ = try ordered.open()
    _ = try ordered.open()
    try check(try ordered.nextFrame()?.id == 1, "Mixed-direction scheduling reordered SSH OPEN IDs")
    try check(try ordered.nextFrame()?.id == 3, "Second SSH OPEN ID was skipped")
    let cancelled = try ordered.open()
    ordered.cancel(cancelled)
    check(ordered.takeRetired() == [5], "Unsent local OPEN did not retire locally")
    try check(try ordered.nextFrame() == nil, "Cancellation emitted a frame for an unissued OPEN")
    print("PASS monotonic SSH OPEN IDs alongside reverse traffic")
}
