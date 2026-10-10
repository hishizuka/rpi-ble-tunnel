import CoreBluetooth
import Foundation
import RpiBleTunnelCore
import RpiBleTunnelMux

final class MultiplexClient: NSObject, CommandRunner, BLELinkDelegate {
    private let options: Options
    private var link: BLELink!
    private var proxy: MuxProxy?
    private var channel: CBL2CAPChannel?
    private var timer: Timer?
    private var stopped = false
    private(set) var result: Int32?

    init(options: Options, connectedLink: BLELink? = nil) {
        self.options = options
        super.init()
        link = connectedLink ?? BLELink(options: options, capability: options.command == "internet" ? RpiBleTunnelProfile.internetCapability : RpiBleTunnelProfile.muxCapability, delegate: self)
        link.setDelegate(self)
    }
    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: options.timeout, repeats: false) { [weak self] _ in
            guard let self else { return }
            self.fail("タイムアウト: \(self.link.stage)")
        }
        if link.isReady { linkReady(link) } else { link.start() }
    }
    func stop(code: Int32) {
        guard !stopped else { return }
        stopped = true
        timer?.invalidate()
        proxy?.close()
        proxy = nil
        channel = nil
        link.stop()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { self.result = code }
    }
    private func fail(_ message: String) {
        guard !stopped else { return }
        FileHandle.standardError.write(Data("rpi-ble-tunnel: \(message)\n".utf8))
        stop(code: 1)
    }
    func linkReady(_ link: BLELink) { link.openChannel() }
    func link(_ link: BLELink, failed message: String) { fail(message) }
    func link(_ link: BLELink, opened channel: CBL2CAPChannel) {
        guard !stopped else { closeL2CAP(channel); return }
        self.channel = channel
        do {
            let wire = StreamMuxWire(input: channel.inputStream, output: channel.outputStream)
            proxy = try MuxProxy(wire: wire, port: options.port, allowInternet: options.command == "internet") { [weak self] in self?.fail($0) }
            timer?.invalidate()
            proxy?.start()
            print("READY 127.0.0.1:\(options.port) → BLE mux → Pi 127.0.0.1:22（最大 8 接続）")
            if options.command == "internet" { print("Internet 中継中: Pi の SOCKS5 → BLE → Mac TCP / DNS") }
        } catch { closeL2CAP(channel); fail(String(describing: error)) }
    }
}
