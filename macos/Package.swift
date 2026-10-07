// swift-tools-version: 5.9
import Foundation
import PackageDescription

let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let package = Package(
    name: "PiLink",
    platforms: [.macOS(.v12)],
    products: [.executable(name: "pilink", targets: ["pilink"]),
               .executable(name: "pilink-core-tests", targets: ["pilink-core-tests"]),
               .executable(name: "pilink-mux-test", targets: ["pilink-mux-test"])],
    targets: [
        .target(name: "PiLinkCore"),
        .target(name: "PiLinkMux", dependencies: ["PiLinkCore"]),
        .executableTarget(
            name: "pilink",
            dependencies: ["PiLinkCore", "PiLinkMux"],
            linkerSettings: [
                .linkedFramework("CoreBluetooth"),
                .unsafeFlags(["-Xlinker", "-sectcreate", "-Xlinker", "__TEXT",
                              "-Xlinker", "__info_plist", "-Xlinker", "\(root)/Info.plist"])
            ]
        ),
        .executableTarget(name: "pilink-core-tests", dependencies: ["PiLinkCore"],
                          path: "Tests/PiLinkCoreTests"),
        .executableTarget(name: "pilink-mux-test", dependencies: ["PiLinkMux"],
                          path: "Tests/MuxTransport")
    ]
)
