// swift-tools-version: 5.9
// SwiftPM 清单：让 Flutter 3.44+ 的 iOS 工程可以直接集成插件。

import PackageDescription

let package = Package(
    name: "clipshare_clipboard_listener",
    platforms: [
        .iOS("13.0")
    ],
    products: [
        .library(
            name: "clipshare-clipboard-listener",
            targets: ["clipshare_clipboard_listener"]
        )
    ],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework")
    ],
    targets: [
        .target(
            name: "clipshare_clipboard_listener",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework")
            ],
            resources: [
                .process("PrivacyInfo.xcprivacy")
            ]
        )
    ]
)
