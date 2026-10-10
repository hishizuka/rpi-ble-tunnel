// swift-tools-version: 5.9
import Foundation
import PackageDescription

let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let package = Package(
    name: "rpi-ble-tunnel",
    platforms: [.macOS(.v12)],
    products: [.executable(name: "rpi-ble-tunnel", targets: ["rpi-ble-tunnel"]),
               .executable(name: "rpi-ble-tunnel-core-tests", targets: ["rpi-ble-tunnel-core-tests"]),
               .executable(name: "rpi-ble-tunnel-mux-test", targets: ["rpi-ble-tunnel-mux-test"])],
    targets: [
        .target(name: "RpiBleTunnelCore"),
        .target(name: "RpiBleTunnelMux", dependencies: ["RpiBleTunnelCore"]),
        .executableTarget(
            name: "rpi-ble-tunnel",
            dependencies: ["RpiBleTunnelCore", "RpiBleTunnelMux"],
            linkerSettings: [
                .linkedFramework("CoreBluetooth"),
                .unsafeFlags(["-Xlinker", "-sectcreate", "-Xlinker", "__TEXT",
                              "-Xlinker", "__info_plist", "-Xlinker", "\(root)/Info.plist"])
            ]
        ),
        .executableTarget(name: "rpi-ble-tunnel-core-tests", dependencies: ["RpiBleTunnelCore"],
                          path: "Tests/RpiBleTunnelCoreTests"),
        .executableTarget(name: "rpi-ble-tunnel-mux-test", dependencies: ["RpiBleTunnelMux"],
                          path: "Tests/MuxTransport")
    ]
)
