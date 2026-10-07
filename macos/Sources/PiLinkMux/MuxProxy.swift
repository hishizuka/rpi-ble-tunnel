import Darwin
import Foundation
import PiLinkCore

public protocol MuxWire: AnyObject {
    func start()
    func read() throws -> [UInt8]?
    func write(_ bytes: UnsafePointer<UInt8>, count: Int) throws -> Int
    func close()
}

public func configureMuxSocket(_ fd: Int32) throws {
    let flags = fcntl(fd, F_GETFL)
    var enabled: Int32 = 1
    guard flags >= 0, fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0,
          fcntl(fd, F_SETFD, FD_CLOEXEC) == 0,
          setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &enabled, socklen_t(MemoryLayout<Int32>.size)) == 0 else {
        throw PiLinkError.invalid("socket の設定: \(String(cString: strerror(errno)))")
    }
}

public final class StreamMuxWire: NSObject, MuxWire, StreamDelegate {
    private let input: InputStream
    private let output: OutputStream
    private var failure: String?
    private var ended = false
    public init(input: InputStream, output: OutputStream) { self.input = input; self.output = output }
    public func start() {
        for stream: Stream in [input, output] {
            stream.delegate = self
            stream.schedule(in: .main, forMode: .default)
            stream.open()
        }
    }
    public func read() throws -> [UInt8]? {
        if let failure { throw PiLinkError.invalid(failure) }
        guard input.hasBytesAvailable else {
            if ended { throw PiLinkError.invalid("L2CAP が切断されました") }
            return nil
        }
        var bytes = [UInt8](repeating: 0, count: 4096)
        let count = input.read(&bytes, maxLength: bytes.count)
        guard count > 0 else { throw PiLinkError.invalid("L2CAP read が終了しました") }
        return Array(bytes.prefix(count))
    }
    public func write(_ bytes: UnsafePointer<UInt8>, count: Int) throws -> Int {
        if let failure { throw PiLinkError.invalid(failure) }
        guard !ended else { throw PiLinkError.invalid("L2CAP が切断されました") }
        guard output.hasSpaceAvailable else { return 0 }
        let written = output.write(bytes, maxLength: min(count, 512))
        guard written >= 0 else { throw PiLinkError.invalid("L2CAP write に失敗しました") }
        return written
    }
    public func close() {
        for stream: Stream in [input, output] {
            stream.delegate = nil
            stream.close()
            stream.remove(from: .main, forMode: .default)
        }
    }
    public func stream(_ stream: Stream, handle event: Stream.Event) {
        if event.contains(.errorOccurred) { failure = stream.streamError?.localizedDescription ?? "L2CAP I/O エラー" }
        if event.contains(.endEncountered) { ended = true }
    }
}

// Used only by the transport substitution test executable.
public final class SocketMuxWire: MuxWire {
    private var fd: Int32
    public init(port: UInt16) throws {
        let socketFD = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socketFD >= 0 else { throw PiLinkError.invalid("test transport socket に失敗しました") }
        fd = socketFD
        var installed = false
        defer { if !installed { Darwin.close(socketFD) } }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let result = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(socketFD, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard result == 0 else { throw PiLinkError.invalid("test transport connect: \(String(cString: strerror(errno)))") }
        try configureMuxSocket(fd)
        installed = true
    }
    public func start() {}
    public func read() throws -> [UInt8]? {
        var bytes = [UInt8](repeating: 0, count: 4096)
        let count = Darwin.recv(fd, &bytes, bytes.count, 0)
        if count < 0 && [EAGAIN, EWOULDBLOCK, EINTR].contains(errno) { return nil }
        guard count > 0 else { throw PiLinkError.invalid("test transport が切断されました") }
        return Array(bytes.prefix(count))
    }
    public func write(_ bytes: UnsafePointer<UInt8>, count: Int) throws -> Int {
        let written = Darwin.send(fd, bytes, min(count, 127), 0)
        if written < 0 && [EAGAIN, EWOULDBLOCK, EINTR].contains(errno) { return 0 }
        guard written > 0 else { throw PiLinkError.invalid("test transport write に失敗しました") }
        return written
    }
    public func close() { if fd >= 0 { Darwin.close(fd); fd = -1 } }
}

public final class MuxProxy {
    private final class SocketState {
        var fd: Int32
        let stream: MuxStream
        var shutdown = false
        var connecting = false
        var addresses = [ResolvedSocket]()
        var operation: Operation?
        var deadline = ProcessInfo.processInfo.systemUptime + 10
        var lastError: Int32 = 0
        init(fd: Int32, stream: MuxStream) { self.fd = fd; self.stream = stream }
    }
    private let wire: MuxWire
    private let onFailure: (String) -> Void
    private let mux: Multiplexer
    private var decoder: MuxDecoder
    private let resolver = OperationQueue()
    private var output = ByteQueue(capacity: MuxFrame.headerSize + MuxFrame.dataSize)
    private var sockets = [UInt32: SocketState]()
    private var listener: Int32 = -1
    private var timer: Timer?
    private var closed = false
    private var ticking = false

    public init(wire: MuxWire, port: UInt16, allowInternet: Bool = false, onFailure: @escaping (String) -> Void) throws {
        self.wire = wire
        self.onFailure = onFailure
        mux = Multiplexer(allowInternet: allowInternet)
        decoder = MuxDecoder(allowInternet: allowInternet)
        resolver.maxConcurrentOperationCount = 2
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw PiLinkError.invalid("多重化 listener socket に失敗しました") }
        var installed = false
        defer { if !installed { Darwin.close(fd) } }
        try configureMuxSocket(fd)
        var enabled: Int32 = 1
        guard setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &enabled, socklen_t(MemoryLayout<Int32>.size)) == 0 else {
            throw PiLinkError.invalid("多重化 SO_REUSEADDR に失敗しました")
        }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, Darwin.listen(fd, Int32(Multiplexer.streamLimit)) == 0 else {
            throw PiLinkError.invalid("127.0.0.1:\(port) の listen: \(String(cString: strerror(errno)))")
        }
        listener = fd
        installed = true
    }

    public func start() {
        wire.start()
        timer = Timer.scheduledTimer(withTimeInterval: 0.01, repeats: true) { [weak self] _ in self?.tick() }
    }

    public func close() {
        guard !closed else { return }
        closed = true
        timer?.invalidate()
        timer = nil
        if listener >= 0 { Darwin.close(listener); listener = -1 }
        resolver.cancelAllOperations()
        for state in sockets.values {
            state.operation?.cancel()
            if state.fd >= 0 { Darwin.close(state.fd) }
        }
        sockets.removeAll()
        wire.close()
    }

    private func openInternet(_ stream: MuxStream) {
        guard let destination = stream.destination else { return }
        let state = SocketState(fd: -1, stream: stream)
        sockets[stream.id] = state
        print("Internet 接続: stream=\(stream.id) \(destination.host):\(destination.port)")
        guard resolver.operationCount < Multiplexer.streamLimit else {
            mux.cancel(stream, reason: 2)
            return
        }
        let operation = BlockOperation()
        state.operation = operation
        operation.addExecutionBlock { [weak self, weak state, weak operation] in
            guard operation?.isCancelled == false else { return }
            let addresses = resolveSocket(destination)
            DispatchQueue.main.async { [weak self, weak state, weak operation] in
                guard let self, let state, !self.closed, operation?.isCancelled == false,
                      self.sockets[stream.id] === state else { return }
                state.operation = nil
                state.addresses = addresses
                if addresses.isEmpty { self.mux.cancel(stream, reason: 4) }
                else { self.connectNext(state) }
            }
        }
        resolver.addOperation(operation)
    }

    private func connectNext(_ state: SocketState) {
        if state.fd >= 0 { Darwin.close(state.fd); state.fd = -1 }
        state.connecting = false
        while !state.addresses.isEmpty {
            let address = state.addresses.removeFirst()
            let fd = Darwin.socket(address.family, SOCK_STREAM, 0)
            if fd < 0 { state.lastError = errno; continue }
            do { try configureMuxSocket(fd) }
            catch { state.lastError = errno; Darwin.close(fd); continue }
            var enabled: Int32 = 1
            if setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &enabled, socklen_t(MemoryLayout<Int32>.size)) < 0 {
                state.lastError = errno; Darwin.close(fd); continue
            }
            let result = address.address.withUnsafeBytes {
                Darwin.connect(fd, $0.baseAddress!.assumingMemoryBound(to: sockaddr.self), socklen_t($0.count))
            }
            if result == 0 || errno == EINPROGRESS {
                state.fd = fd
                state.connecting = result != 0
                if result == 0 { mux.connected(state.stream) }
                return
            }
            state.lastError = errno
            Darwin.close(fd)
        }
        let reason: UInt32 = state.lastError == ECONNREFUSED ? 5 :
            [ENETUNREACH, EHOSTUNREACH].contains(state.lastError) ? 7 : 1
        mux.cancel(state.stream, reason: reason)
    }

    private func accept() throws {
        for _ in 0..<16 {
            let fd = Darwin.accept(listener, nil, nil)
            if fd < 0 {
                if errno == EINTR { continue }
                if errno == EAGAIN || errno == EWOULDBLOCK { return }
                throw PiLinkError.invalid("多重化 accept に失敗しました")
            }
            guard mux.streams.count < Multiplexer.streamLimit else {
                print("同時接続は 8 本までです。追加の TCP 接続を閉じます。")
                Darwin.close(fd)
                continue
            }
            do {
                try configureMuxSocket(fd)
                var enabled: Int32 = 1
                guard setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &enabled, socklen_t(MemoryLayout<Int32>.size)) == 0 else {
                    throw PiLinkError.invalid("TCP_NODELAY に失敗しました")
                }
                let stream = try mux.open()
                sockets[stream.id] = SocketState(fd: fd, stream: stream)
                print("多重化 SSH 接続: stream=\(stream.id)")
            } catch { Darwin.close(fd); throw error }
        }
    }

    private func pumpTCP(_ state: SocketState) throws {
        let stream = state.stream
        if stream.destination != nil && !stream.acknowledged {
            if ProcessInfo.processInfo.systemUptime > state.deadline {
                state.operation?.cancel()
                mux.cancel(stream, reason: 6)
                return
            }
            if state.connecting {
                var descriptor = pollfd(fd: state.fd, events: Int16(POLLOUT), revents: 0)
                let status = Darwin.poll(&descriptor, 1, 0)
                if status < 0 && errno != EINTR { mux.cancel(stream, reason: 1); return }
                if status > 0 {
                    var error: Int32 = 0
                    var size = socklen_t(MemoryLayout<Int32>.size)
                    if getsockopt(state.fd, SOL_SOCKET, SO_ERROR, &error, &size) < 0 { error = errno }
                    if error == 0 { state.connecting = false; mux.connected(stream) }
                    else { state.lastError = error; connectNext(state) }
                }
            }
            return
        }
        guard state.fd >= 0 else { return }
        if !stream.received.isEmpty {
            let count = stream.received.withReadableBytes { Darwin.send(state.fd, $0, $1, 0) }
            if count > 0 { try mux.consume(count, for: stream) }
            else if count == 0 || ![EAGAIN, EWOULDBLOCK, EINTR].contains(errno) { mux.cancel(stream); return }
        }
        if stream.remoteEOF && stream.received.isEmpty && !state.shutdown {
            if Darwin.shutdown(state.fd, SHUT_WR) < 0 && errno != ENOTCONN { mux.cancel(stream); return }
            state.shutdown = true
        }
        if stream.readAllowance > 0 {
            var bytes = [UInt8](repeating: 0, count: min(MuxFrame.dataSize, stream.readAllowance))
            let count = Darwin.recv(state.fd, &bytes, bytes.count, 0)
            if count > 0 { try mux.queue(Array(bytes.prefix(count)), for: stream) }
            else if count == 0 { mux.eof(stream) }
            else if ![EAGAIN, EWOULDBLOCK, EINTR].contains(errno) { mux.cancel(stream) }
        }
    }

    private func tick() {
        guard !closed, !ticking else { return }
        ticking = true
        defer { ticking = false }
        do {
            for _ in 0..<16 {
                guard let bytes = try wire.read() else { break }
                for frame in try decoder.feed(bytes) {
                    try mux.receive(frame)
                    if frame.type == .openTCP, let stream = mux.streams.first(where: { $0.id == frame.id }) {
                        openInternet(stream)
                    }
                }
            }
            // Handle retirements before touching sockets closed by a peer RESET.
            retireSockets()
            for stream in mux.streams {
                if let state = sockets[stream.id] { try pumpTCP(state) }
            }
            for _ in 0..<64 {
                if output.isEmpty {
                    guard let frame = try mux.nextFrame() else { break }
                    try output.append(frame.encoded)
                }
                let count = try output.withReadableBytes { try wire.write($0, count: $1) }
                if count == 0 { break }
                try output.consume(count)
            }
            retireSockets()
            // Reclaim completed streams before applying the limit to new TCP clients.
            try accept()
        } catch {
            close()
            onFailure(String(describing: error))
        }
    }

    private func retireSockets() {
        for id in mux.takeRetired() {
            if let state = sockets.removeValue(forKey: id) {
                state.operation?.cancel()
                if state.fd >= 0 { Darwin.close(state.fd) }
            }
            print("多重化 SSH 終了: stream=\(id)")
        }
    }
}
