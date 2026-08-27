// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "VialScanner",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [
        .library(name: "VialCore", targets: ["VialCore"]),
        .executable(name: "vialscan", targets: ["vialscan"]),
    ],
    targets: [
        .target(name: "VialCore"),
        .executableTarget(name: "vialscan", dependencies: ["VialCore"]),
    ]
)
