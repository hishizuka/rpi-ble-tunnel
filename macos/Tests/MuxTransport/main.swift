import Darwin
import Foundation
import PiLinkMux

// This executable substitutes loopback TCP for BLE without changing MuxProxy.
guard [3, 4].contains(CommandLine.arguments.count),
      let wirePort = UInt16(CommandLine.arguments[1]), wirePort > 0,
      let localPort = UInt16(CommandLine.arguments[2]), localPort > 0 else {
    fatalError("Usage: pilink-mux-test WIRE_PORT LOCAL_PORT [internet]")
}
setbuf(stdout, nil)
var result: Int32?
let proxy = try MuxProxy(wire: SocketMuxWire(port: wirePort), port: localPort,
                         allowInternet: CommandLine.arguments.dropFirst(3).first == "internet") {
    FileHandle.standardError.write(Data("\($0)\n".utf8))
    result = 1
}
let sources = [SIGINT, SIGTERM].map { value -> DispatchSourceSignal in
    signal(value, SIG_IGN)
    let source = DispatchSource.makeSignalSource(signal: value, queue: .main)
    source.setEventHandler { proxy.close(); result = 0 }
    source.resume()
    return source
}
proxy.start()
print("READY mux test 127.0.0.1:\(localPort)")
withExtendedLifetime(sources) {
    while result == nil { RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.1)) }
}
exit(result!)
