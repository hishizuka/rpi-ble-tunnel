import Darwin
import Foundation
import PiLinkCore

struct ResolvedSocket {
    let family: Int32
    let address: Data
}

// Blocking DNS runs on a queue with two workers; sockets remain on the event loop.
func resolveSocket(_ destination: MuxDestination) -> [ResolvedSocket] {
    var hints = addrinfo()
    hints.ai_family = destination.addressType == 1 ? AF_INET : destination.addressType == 4 ? AF_INET6 : AF_UNSPEC
    hints.ai_socktype = SOCK_STREAM
    hints.ai_protocol = IPPROTO_TCP
    if destination.addressType != 3 { hints.ai_flags = AI_NUMERICHOST }
    var result: UnsafeMutablePointer<addrinfo>?
    guard getaddrinfo(destination.host, String(destination.port), &hints, &result) == 0 else { return [] }
    defer { if let result { freeaddrinfo(result) } }
    var addresses = [ResolvedSocket]()
    var current = result
    while let info = current, addresses.count < 8 {
        if [AF_INET, AF_INET6].contains(info.pointee.ai_family), let address = info.pointee.ai_addr {
            addresses.append(ResolvedSocket(family: info.pointee.ai_family,
                                            address: Data(bytes: address, count: Int(info.pointee.ai_addrlen))))
        }
        current = info.pointee.ai_next
    }
    return addresses
}
