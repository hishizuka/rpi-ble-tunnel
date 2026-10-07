import CoreBluetooth
import Foundation
import PiLinkCore

// Select the transport before opening L2CAP and retain the discovered GATT link.
final class AutoClient: NSObject, CommandRunner, BLELinkDelegate {
    private let options: Options
    private var link: BLELink!
    private var client: CommandRunner?
    private var timer: Timer?
    private var stopped = false
    private var ownResult: Int32?
    var result: Int32? { client?.result ?? ownResult }

    init(options: Options) {
        self.options = options
        super.init()
        link = BLELink(options: options, capability: PiLinkProfile.automaticCapability, delegate: self)
    }

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: options.timeout, repeats: false) { [weak self] _ in
            guard let self else { return }
            self.fail("タイムアウト: \(self.link.stage)")
        }
        link.start()
    }

    func stop(code: Int32) {
        guard !stopped else { return }
        stopped = true
        timer?.invalidate()
        if let client { client.stop(code: code) }
        else {
            link.stop()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { self.ownResult = code }
        }
    }

    private func fail(_ message: String) {
        guard !stopped else { return }
        FileHandle.standardError.write(Data("PiLink: \(message)\n".utf8))
        stop(code: 1)
    }

    func linkReady(_ link: BLELink) {
        guard !stopped, let capability = link.selectedCapability else {
            fail("Pi の接続方式を選択できません"); return
        }
        timer?.invalidate()
        var selected = options
        switch capability {
        case PiLinkProfile.internetCapability, PiLinkProfile.muxCapability:
            selected.command = capability == PiLinkProfile.internetCapability ? "internet" : "multiplex"
            client = MultiplexClient(options: selected, connectedLink: link)
        default:
            selected.command = "ssh"
            client = SSHClient(options: selected, connectedLink: link)
        }
        print("接続方式を自動選択: \(selected.command)")
        client?.start()
    }

    func link(_ link: BLELink, failed message: String) { fail(message) }
    func link(_ link: BLELink, opened channel: CBL2CAPChannel) {
        closeL2CAP(channel)
        fail("接続方式の選択前に L2CAP が開かれました")
    }
}
