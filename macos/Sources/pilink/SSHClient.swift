import CoreBluetooth
import Darwin
import Foundation
import PiLinkCore
import PiLinkMux

final class SSHClient: NSObject, CommandRunner, BLELinkDelegate {
    private let options: Options
    private var link: BLELink!
    private var timer: Timer?
    private var listener: DispatchSourceRead?
    private var listenerFD: Int32 = -1
    private var pendingFD: Int32 = -1
    private var session: SSHSession?
    private var finished = false
    private(set) var result: Int32?

    init(options: Options, connectedLink: BLELink? = nil) {
        self.options = options
        super.init()
        link = connectedLink ?? BLELink(options: options, capability: PiLinkProfile.sshCapability, delegate: self)
        link.setDelegate(self)
    }

    func start() {
        armTimeout()
        if link.isReady { linkReady(link) } else { link.start() }
    }

    private func armTimeout() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: options.timeout, repeats: false) { [weak self] _ in
            guard let self else { return }
            self.fail("タイムアウト: \(self.link.stage)")
        }
    }

    func stop(code: Int32) {
        guard !finished else { return }
        finished = true
        timer?.invalidate()
        listener?.cancel()
        listener = nil
        listenerFD = -1
        if pendingFD >= 0 { Darwin.close(pendingFD); pendingFD = -1 }
        session?.stop(reason: "クライアント終了")
        session = nil
        link.stop()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { self.result = code }
    }

    private func fail(_ message: String) {
        guard !finished else { return }
        FileHandle.standardError.write(Data("PiLink: \(message)\n".utf8))
        stop(code: 1)
    }

    func link(_ link: BLELink, failed message: String) { fail(message) }

    func linkReady(_ link: BLELink) {
        timer?.invalidate()
        if listener != nil {
            if pendingFD >= 0 {
                armTimeout()
                link.openChannel()
            }
            return
        }
        do {
            let fd = try openLoopbackListener(port: options.port, backlog: 4)
            let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: .main)
            source.setEventHandler { [weak self] in self?.acceptClients() }
            source.setCancelHandler { Darwin.close(fd) }
            listenerFD = fd
            listener = source
            source.resume()
            print("READY 127.0.0.1:\(options.port) → BLE → Pi 127.0.0.1:22")
        } catch { fail(String(describing: error)) }
    }

    private func acceptClients() {
        guard !finished, listenerFD >= 0 else { return }
        for _ in 0..<16 {
            let fd = Darwin.accept(listenerFD, nil, nil)
            if fd < 0 {
                if errno == EINTR { continue }
                if errno != EAGAIN && errno != EWOULDBLOCK { fail(String(describing: socketFailure("accept"))) }
                return
            }
            guard pendingFD < 0, session == nil else {
                print("同時接続は 1 本までです。追加の TCP 接続を閉じます。")
                Darwin.close(fd)
                continue
            }
            do { try configureTCPSocket(fd) }
            catch { Darwin.close(fd); continue }
            pendingFD = fd
            armTimeout()
            if link.isReady { link.openChannel() }
            else { print("次の SSH セッション: BLE の再接続を待っています") }
        }
    }

    func link(_ link: BLELink, opened channel: CBL2CAPChannel) {
        guard !finished, pendingFD >= 0 else { closeL2CAP(channel); return }
        timer?.invalidate()
        let connected = SSHSession(fd: pendingFD, channel: channel) { [weak self] in
            guard let self, !self.finished else { return }
            let identifier = self.link.identifier
            self.session = nil
            self.armTimeout()
            // Cancellation is asynchronous; wait for its acknowledgement before reconnecting.
            self.link.stop(afterDisconnect: { [weak self] in
                DispatchQueue.main.async { [weak self] in
                    guard let self, !self.finished else { return }
                    var nextOptions = self.options
                    if let identifier { nextOptions.identifier = identifier; nextOptions.name = nil }
                    self.link = BLELink(options: nextOptions, capability: PiLinkProfile.sshCapability, delegate: self)
                    self.link.start()
                }
            })
        }
        pendingFD = -1
        session = connected
        print("SSH セッション: L2CAP 接続成功")
        connected.start()
    }
}

final class SSHSession: NSObject, StreamDelegate {
    private let fd: Int32
    private let channel: CBL2CAPChannel
    private let onFinish: () -> Void
    private var toBLE = ByteQueue()
    private var toTCP = ByteQueue()
    private var tcpRead: DispatchSourceRead!
    private var tcpWrite: DispatchSourceWrite!
    private var readEnabled = false
    private var writeEnabled = false
    private var inputScheduled = false
    private var tcpEOF = false
    private var bleEOF = false
    private var finished = false
    private var sent = 0
    private var received = 0

    init(fd: Int32, channel: CBL2CAPChannel, onFinish: @escaping () -> Void) {
        self.fd = fd
        self.channel = channel
        self.onFinish = onFinish
    }

    func start() {
        tcpRead = DispatchSource.makeReadSource(fileDescriptor: fd, queue: .main)
        tcpWrite = DispatchSource.makeWriteSource(fileDescriptor: fd, queue: .main)
        tcpRead.setEventHandler { [weak self] in self?.readTCP() }
        tcpWrite.setEventHandler { [weak self] in self?.writeTCP() }
        let socketFD = fd
        tcpRead.setCancelHandler { Darwin.close(socketFD) }
        for stream: Stream in [channel.inputStream, channel.outputStream] {
            stream.delegate = self
            stream.schedule(in: .main, forMode: .default)
            stream.open()
        }
        inputScheduled = true
        adjustInterests()
    }

    func stop(reason: String) {
        guard !finished else { return }
        finished = true
        // Suspended dispatch sources must be resumed before cancellation and release.
        if !readEnabled { tcpRead.resume() }
        if !writeEnabled { tcpWrite.resume() }
        tcpRead.cancel()
        tcpWrite.cancel()
        tcpRead = nil
        tcpWrite = nil
        closeL2CAP(channel)
        print("SSH セッション終了: \(reason)、TCP→BLE=\(sent)、BLE→TCP=\(received) バイト")
        onFinish()
    }

    private func adjustInterests() {
        guard !finished else { return }
        let read = !tcpEOF && !bleEOF && toBLE.remainingCapacity > 0
        if read != readEnabled {
            readEnabled = read
            if read { tcpRead.resume() } else { tcpRead.suspend() }
        }
        let write = !toTCP.isEmpty
        if write != writeEnabled {
            writeEnabled = write
            if write { tcpWrite.resume() } else { tcpWrite.suspend() }
        }
        let scheduleInput = !bleEOF && toTCP.remainingCapacity > 0
        if scheduleInput != inputScheduled {
            inputScheduled = scheduleInput
            if scheduleInput {
                channel.inputStream.schedule(in: .main, forMode: .default)
                DispatchQueue.main.async { [weak self] in self?.readBLE() }
            } else {
                channel.inputStream.remove(from: .main, forMode: .default)
            }
        }
        if (tcpEOF || bleEOF) && toBLE.isEmpty && toTCP.isEmpty {
            stop(reason: "接続終了")
        }
    }

    private func readTCP() {
        guard !finished, !tcpEOF, !bleEOF else { return }
        var bytes = [UInt8](repeating: 0, count: 16384)
        while !finished, toBLE.remainingCapacity > 0 {
            let count = Darwin.recv(fd, &bytes, min(bytes.count, toBLE.remainingCapacity), 0)
            if count < 0 {
                if errno == EINTR { continue }
                if errno != EAGAIN && errno != EWOULDBLOCK { stop(reason: String(describing: socketFailure("TCP read"))) }
                break
            }
            if count == 0 { tcpEOF = true; break }
            do { try toBLE.append(Array(bytes.prefix(count))) }
            catch { stop(reason: String(describing: error)); return }
            writeBLE()
        }
        adjustInterests()
    }

    private func writeBLE() {
        guard !finished, !bleEOF else { return }
        let output = channel.outputStream!
        while !finished, !toBLE.isEmpty, output.hasSpaceAvailable {
            let count = toBLE.withReadableBytes { output.write($0, maxLength: min($1, 512)) }
            if count < 0 { stop(reason: "L2CAP write: \(output.streamError?.localizedDescription ?? "失敗")"); return }
            if count == 0 { break }
            do { try toBLE.consume(count) } catch { stop(reason: String(describing: error)); return }
            sent += count
        }
        adjustInterests()
    }

    private func readBLE() {
        guard !finished, !bleEOF else { return }
        let input = channel.inputStream!
        var bytes = [UInt8](repeating: 0, count: 16384)
        while !finished, input.hasBytesAvailable, toTCP.remainingCapacity > 0 {
            let count = input.read(&bytes, maxLength: min(bytes.count, toTCP.remainingCapacity))
            if count < 0 { stop(reason: "L2CAP read: \(input.streamError?.localizedDescription ?? "失敗")"); return }
            if count == 0 { bleEOF = true; toBLE = ByteQueue(); break }
            do { try toTCP.append(Array(bytes.prefix(count))) }
            catch { stop(reason: String(describing: error)); return }
        }
        adjustInterests()
        writeTCP()
    }

    private func writeTCP() {
        guard !finished else { return }
        while !toTCP.isEmpty {
            let count = toTCP.withReadableBytes { Darwin.send(fd, $0, $1, 0) }
            if count < 0 {
                if errno == EINTR { continue }
                if errno != EAGAIN && errno != EWOULDBLOCK { stop(reason: String(describing: socketFailure("TCP write"))) }
                break
            }
            if count == 0 { stop(reason: "TCP write が終了しました"); return }
            do { try toTCP.consume(count) } catch { stop(reason: String(describing: error)); return }
            received += count
        }
        adjustInterests()
    }

    func stream(_ stream: Stream, handle eventCode: Stream.Event) {
        guard !finished else { return }
        switch eventCode {
        case .hasBytesAvailable: readBLE()
        case .hasSpaceAvailable: writeBLE()
        case .endEncountered:
            readBLE()
            bleEOF = true
            // The peer is gone; only already-received bytes can still be delivered to TCP.
            toBLE = ByteQueue()
            writeTCP()
            adjustInterests()
        case .errorOccurred: stop(reason: "L2CAP I/O: \(stream.streamError?.localizedDescription ?? "失敗")")
        default: break
        }
    }
}
