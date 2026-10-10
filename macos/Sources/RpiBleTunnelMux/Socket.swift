import Darwin
import RpiBleTunnelCore

public func socketFailure(_ operation: String) -> RpiBleTunnelError {
    .invalid("\(operation): \(String(cString: strerror(errno)))")
}

public func configureMuxSocket(_ fd: Int32) throws {
    let flags = fcntl(fd, F_GETFL)
    var enabled: Int32 = 1
    guard flags >= 0, fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0,
          fcntl(fd, F_SETFD, FD_CLOEXEC) == 0,
          setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &enabled, socklen_t(MemoryLayout<Int32>.size)) == 0 else {
        throw socketFailure("socket の設定")
    }
}

public func configureTCPSocket(_ fd: Int32) throws {
    try configureMuxSocket(fd)
    var enabled: Int32 = 1
    guard setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &enabled, socklen_t(MemoryLayout<Int32>.size)) == 0 else {
        throw socketFailure("TCP_NODELAY")
    }
}

public func openLoopbackListener(port: UInt16, backlog: Int32) throws -> Int32 {
    let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
    guard fd >= 0 else { throw socketFailure("listener socket") }
    do {
        try configureMuxSocket(fd)
        var enabled: Int32 = 1
        guard setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &enabled, socklen_t(MemoryLayout<Int32>.size)) == 0 else {
            throw socketFailure("SO_REUSEADDR")
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
        guard bound == 0, Darwin.listen(fd, backlog) == 0 else {
            throw socketFailure("127.0.0.1:\(port) の listen")
        }
        return fd
    } catch {
        Darwin.close(fd)
        throw error
    }
}
