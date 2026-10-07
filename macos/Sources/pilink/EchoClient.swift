import CoreBluetooth
import Foundation
import PiLinkCore

final class EchoClient: NSObject, CommandRunner, BLELinkDelegate, StreamDelegate {
    private let options: Options
    private var link: BLELink!
    private var channel: CBL2CAPChannel?
    private var verifier: EchoVerifier
    private var timer: Timer?
    private var started: TimeInterval = 0
    private var finished = false
    private let window = 16384
    private(set) var result: Int32?

    init(options: Options) throws {
        self.options = options
        verifier = try EchoVerifier(payload: options.payload)
        super.init()
        link = BLELink(options: options, capability: PiLinkProfile.echoCapability, delegate: self)
    }

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: options.timeout, repeats: false) { [weak self] _ in
            guard let self else { return }
            self.fail("タイムアウト: \(self.link.stage) (送信 \(self.verifier.sent), 受信 \(self.verifier.received) バイト)")
        }
        link.start()
    }

    func stop(code: Int32) {
        guard !finished else { return }
        finished = true
        timer?.invalidate()
        if let channel { closeL2CAP(channel) }
        channel = nil
        link.stop()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { self.result = code }
    }

    private func fail(_ message: String) {
        FileHandle.standardError.write(Data("PiLink: \(message)\n".utf8))
        stop(code: 1)
    }

    func linkReady(_ link: BLELink) { link.openChannel() }
    func link(_ link: BLELink, failed message: String) { fail(message) }
    func link(_ link: BLELink, opened channel: CBL2CAPChannel) {
        self.channel = channel
        started = ProcessInfo.processInfo.systemUptime
        print("L2CAP 接続成功。\(verifier.payload.count) バイトを送信します。")
        for stream: Stream in [channel.inputStream, channel.outputStream] {
            stream.delegate = self
            stream.schedule(in: .main, forMode: .default)
            stream.open()
        }
    }

    func stream(_ stream: Stream, handle eventCode: Stream.Event) {
        guard !finished else { return }
        switch eventCode {
        case .hasSpaceAvailable: writeAvailable()
        case .hasBytesAvailable: readAvailable()
        case .errorOccurred: fail("L2CAP I/O: \(stream.streamError?.localizedDescription ?? "失敗")")
        case .endEncountered: fail("echo 完了前に L2CAP stream が閉じられました")
        default: break
        }
    }

    private func writeAvailable() {
        guard let output = channel?.outputStream else { return }
        while !finished, output.hasSpaceAvailable, verifier.sent < verifier.payload.count {
            let available = window - (verifier.sent - verifier.received)
            guard available > 0 else { return }
            let count = min(options.chunkSize, available, verifier.payload.count - verifier.sent)
            let offset = verifier.sent
            let written = verifier.payload.withUnsafeBufferPointer {
                output.write($0.baseAddress!.advanced(by: offset), maxLength: count)
            }
            if written < 0 { fail("L2CAP write: \(output.streamError?.localizedDescription ?? "失敗")"); return }
            if written == 0 { return }
            do { try verifier.recordSent(written) } catch { fail(String(describing: error)); return }
        }
    }

    private func readAvailable() {
        guard let input = channel?.inputStream else { return }
        var bytes = [UInt8](repeating: 0, count: 16384)
        while !finished, input.hasBytesAvailable {
            let count = input.read(&bytes, maxLength: bytes.count)
            if count < 0 { fail("L2CAP read: \(input.streamError?.localizedDescription ?? "失敗")"); return }
            if count == 0 { break }
            do { try verifier.receive(Array(bytes.prefix(count))) }
            catch { fail(String(describing: error)); return }
            if verifier.complete {
                let elapsed = max(ProcessInfo.processInfo.systemUptime - started, 0.000001)
                print(String(format: "ECHO OK: %d バイト一致、%.3f 秒、%.1f KiB/s (往復)",
                             verifier.received, elapsed, Double(verifier.received) / elapsed / 1024))
                stop(code: 0)
                return
            }
        }
        writeAvailable()
    }
}
