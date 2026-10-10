import Foundation

public enum RpiBleTunnelProfile {
    public static let service = "6f6d0001-8e6d-4c8a-a8bf-5b8a2a786a21"
    public static let version = "6f6d0002-8e6d-4c8a-a8bf-5b8a2a786a21"
    public static let psm = "6f6d0003-8e6d-4c8a-a8bf-5b8a2a786a21"
    public static let capabilities = "6f6d0004-8e6d-4c8a-a8bf-5b8a2a786a21"
    public static let automaticCapability: UInt32 = 0
    public static let echoCapability: UInt32 = 1
    public static let sshCapability: UInt32 = 2
    public static let muxCapability: UInt32 = 4
    public static let internetCapability: UInt32 = 8

    public struct Discovery: Equatable {
        public let version: UInt16
        public let psm: UInt16
        public let capabilities: UInt32
    }

    public static func selectCapability(_ capabilities: UInt32) throws -> UInt32 {
        for capability in [internetCapability, muxCapability, sshCapability] {
            if capabilities & capability != 0 { return capability }
        }
        throw RpiBleTunnelError.invalid("Pi が対応する接続方式を提供していません")
    }

    public static func decode(version: Data, psm: Data, capabilities: Data,
                              requiredCapability: UInt32 = echoCapability) throws -> Discovery {
        guard version.count == 2, psm.count == 2, capabilities.count == 4 else {
            throw RpiBleTunnelError.invalid("GATT 値の長さが不正です")
        }
        let v = Array(version), p = Array(psm), c = Array(capabilities)
        let decodedVersion = UInt16(v[0]) | UInt16(v[1]) << 8
        let decodedPSM = UInt16(p[0]) | UInt16(p[1]) << 8
        let decodedCaps = UInt32(c[0]) | UInt32(c[1]) << 8 | UInt32(c[2]) << 16 | UInt32(c[3]) << 24
        guard decodedVersion == 1 else {
            throw RpiBleTunnelError.invalid("未対応の protocol version: \(decodedVersion)")
        }
        guard (0x80...0xff).contains(decodedPSM) else {
            throw RpiBleTunnelError.invalid("LE L2CAP PSM が範囲外です: \(decodedPSM)")
        }
        if requiredCapability == automaticCapability { _ = try selectCapability(decodedCaps) }
        guard decodedCaps & requiredCapability == requiredCapability else {
            let mode = requiredCapability == internetCapability ? "Internet" : requiredCapability == muxCapability ? "多重化" : requiredCapability == sshCapability ? "SSH" : "echo"
            throw RpiBleTunnelError.invalid("接続先は \(mode) モードを提供していません")
        }
        return Discovery(version: decodedVersion, psm: decodedPSM, capabilities: decodedCaps)
    }
}

public enum RpiBleTunnelError: Error, CustomStringConvertible {
    case invalid(String)
    public var description: String {
        switch self { case .invalid(let message): return message }
    }
}
