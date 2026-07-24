import Cocoa
import FlutterMacOS

public class ClipshareClipboardListenerPlugin: NSObject, FlutterPlugin {

    private static let kChannelName = "top.coclyun.clipshare/clipboard_listener"
    private static let kOnClipboardChanged = "onClipboardChanged"
    private static let kStartListening = "startListening"
    private static let kStopListening = "stopListening"
    private static let kCheckIsRunning = "checkIsRunning"
    private static let kPasteToPreviousWindow = "pasteToPreviousWindow"
    private static let kCopy = "copy"
    // Explicitly write PNG data so third-party clipboard managers keep the image type stable.
    private static let kPngPasteboardType = NSPasteboard.PasteboardType("public.png")


    private var channel: FlutterMethodChannel!
    private let pasteboard = NSPasteboard.general
    private var changeCount: Int = -1
    private var timer: Timer?
    private var ignoreNextCopy = false
    private var currentApp: NSRunningApplication?
    private var previousApp: NSRunningApplication?

    // 图片保存目录
    private let imageSavePath: String = {
        let tempDir = NSTemporaryDirectory()
        return tempDir
    }()

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: ClipshareClipboardListenerPlugin.kChannelName, binaryMessenger: registrar.messenger)
        let instance = ClipshareClipboardListenerPlugin()
        instance.channel = channel
        registrar.addMethodCallDelegate(instance, channel: channel)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let args = call.arguments as? [String: Any] ?? [:]
        switch call.method {
        case ClipshareClipboardListenerPlugin.kStartListening:
            startListening(result: result)
        case ClipshareClipboardListenerPlugin.kStopListening:
            stopListening(result: result)
        case ClipshareClipboardListenerPlugin.kCheckIsRunning:
            checkIsRunning(result: result)
        case ClipshareClipboardListenerPlugin.kPasteToPreviousWindow:
            pasteToPreviousWindow(result: result)
        case ClipshareClipboardListenerPlugin.kCopy:
            let type = args["type"] as? String
            let content = args["content"] as? String
            if type == nil || content == nil {
                result(false)
            } else {
                copyData(type: type!, content: content!, result: result)
            }
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private func startListening(result: @escaping FlutterResult) {
        observeForegroundAppChanges()
        timer = Timer.scheduledTimer(
            timeInterval: 0.1,
            target: self,
            selector: #selector(self.onClipboardChanged),
            userInfo: nil,
            repeats: true
        )
        result(true)
    }

    private func stopListening(result: @escaping FlutterResult) {
        timer?.invalidate()
        timer = nil
        NSWorkspace.shared.notificationCenter.removeObserver(
            self,
            name: NSWorkspace.didActivateApplicationNotification,
            object: nil
        )
        result(true)
    }

    private func checkIsRunning(result: @escaping FlutterResult) {
        result(timer != nil)
    }

    private func copyData(type: String, content: String, result: @escaping FlutterResult) {
        ignoreNextCopy = true
        var success = false
        let lower = type.lowercased()
        if lower == "text" {
            pasteboard.clearContents()
            success = pasteboard.setString(content, forType: .string)
        } else if lower == "image" {
            // 处理图片类型
            if let image = NSImage(contentsOfFile: content),
               let pngData = createPngData(from: image) {
                // Write a concrete PNG payload instead of NSImage's default TIFF representation.
                pasteboard.clearContents()
                let item = NSPasteboardItem()
                item.setData(pngData, forType: Self.kPngPasteboardType)
                success = pasteboard.writeObjects([item])
            } else {
                print("Failed to load image from path: \(content)")
                success = false
            }
        }
        result(success)
    }

    private func observeForegroundAppChanges() {
        if currentApp == nil {
            currentApp = NSWorkspace.shared.frontmostApplication
        }
        NSWorkspace.shared.notificationCenter.removeObserver(
            self,
            name: NSWorkspace.didActivateApplicationNotification,
            object: nil
        )
        NSWorkspace.shared.notificationCenter.addObserver(
            self,
            selector: #selector(self.onForegroundAppChanged(_:)),
            name: NSWorkspace.didActivateApplicationNotification,
            object: nil
        )
    }

    @objc private func onForegroundAppChanged(_ notification: Notification) {
        guard let app = notification.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication else {
            return
        }
        if app.processIdentifier == ProcessInfo.processInfo.processIdentifier {
            if let activeApp = currentApp {
                previousApp = activeApp
            }
            currentApp = nil
            return
        }
        if app.processIdentifier == currentApp?.processIdentifier {
            return
        }
        if let activeApp = currentApp, activeApp.processIdentifier != ProcessInfo.processInfo.processIdentifier {
            previousApp = activeApp
        }
        currentApp = app
    }

    private func pasteToPreviousWindow(result: @escaping FlutterResult){
        guard let app = previousApp else {
            result(false)
            return
        }
        // 激活前台应用
        app.activate(options: [.activateAllWindows])
        sendPasteShortcut()
        result(true)
    }

    //模拟 粘贴事件
    func sendPasteShortcut() {
        // Command + V
        let src = CGEventSource(stateID: .hidSystemState)

        // ⌘ 键按下
        let cmdDown = CGEvent(keyboardEventSource: src, virtualKey: 0x37, keyDown: true)
        cmdDown?.flags = .maskCommand
        cmdDown?.post(tap: .cgSessionEventTap)

        // V 键按下
        let vDown = CGEvent(keyboardEventSource: src, virtualKey: 0x09, keyDown: true)
        vDown?.flags = .maskCommand
        vDown?.post(tap: .cgSessionEventTap)

        // V 键松开
        let vUp = CGEvent(keyboardEventSource: src, virtualKey: 0x09, keyDown: false)
        vUp?.flags = .maskCommand
        vUp?.post(tap: .cgSessionEventTap)

        // ⌘ 键松开
        let cmdUp = CGEvent(keyboardEventSource: src, virtualKey: 0x37, keyDown: false)
        cmdUp?.post(tap: .cgSessionEventTap)
    }

    //region OnClipboardChanged

    @objc private func onClipboardChanged() {
        if (pasteboard.changeCount == changeCount){
            return
        }
        changeCount = pasteboard.changeCount;
        if ignoreNextCopy {
            ignoreNextCopy = false
            return
        }

        var args: [String: Any] = [:]

        // 检查文本内容
        if let text = pasteboard.string(forType: .string) {
            args["type"] = "text"
            args["content"] = text
        }
        // 检查图片
        else if let image = pasteboard.readObjects(forClasses: [NSImage.self])?.first as? NSImage {
            // 保存图片到临时文件
            if let imagePath = saveImageToTempFile(image: image) {
                args["type"] = "image"
                args["content"] = imagePath
            } else {
                return
            }
        }else{
            return
        }
        if let app = getFrontmostApplication() {
            var source:[String: Any] = [:]

            // 应用ID (bundle identifier)
            if let bundleIdentifier = app!.bundleIdentifier {
                source["id"] = bundleIdentifier
            }

            // 应用名称
            if let localizedName = app!.localizedName {
                source["name"] = localizedName
            }

            // 应用图标 Base64
            if let appIcon = app!.icon {
                source["iconB64"] = convertImageToBase64(image: appIcon)
            }
            args["source"] = source
        }

        channel.invokeMethod(ClipshareClipboardListenerPlugin.kOnClipboardChanged, arguments: args, result: nil)
    }

    private func saveImageToTempFile(image: NSImage) -> String? {
        let fileName = "clipboard_\(getCurrentTimeWithMilliseconds()).png"
        let filePath = (imageSavePath as NSString).appendingPathComponent(fileName)

        guard let pngData = createPngData(from: image) else {
            return nil
        }

        do {
            try pngData.write(to: URL(fileURLWithPath: filePath))
            return filePath
        } catch {
            return nil
        }
    }

    private func getCurrentTimeWithMilliseconds() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd_HHmmss_SSS"
        return formatter.string(from: Date())
    }

    private func getFrontmostApplication() -> NSRunningApplication?? {
        let workspace = NSWorkspace.shared
        guard let frontApp = workspace.frontmostApplication else {
            return nil
        }
        return frontApp
    }

    private func convertImageToBase64(image: NSImage) -> String? {
        // 调整图片大小（可选，避免Base64数据过大）
        let resizedImage = resizeImage(image: image, maxSize: NSSize(width: 64, height: 64))

        guard let pngData = createPngData(from: resizedImage) else {
            return nil
        }

        // 转换为 Base64 字符串
        return pngData.base64EncodedString()
    }

    // Reuse one PNG encoder so clipboard writes and exported temp files stay consistent.
    private func createPngData(from image: NSImage) -> Data? {
        guard let tiffData = image.tiffRepresentation,
              let bitmapImage = NSBitmapImageRep(data: tiffData),
              let pngData = bitmapImage.representation(using: .png, properties: [:]) else {
            return nil
        }

        return pngData
    }

    private func resizeImage(image: NSImage, maxSize: NSSize) -> NSImage {
        let imageSize = image.size
        let widthRatio = maxSize.width / imageSize.width
        let heightRatio = maxSize.height / imageSize.height
        let ratio = min(widthRatio, heightRatio, 1.0) // 不超过原始大小

        let newSize = NSSize(width: imageSize.width * ratio, height: imageSize.height * ratio)
        let resizedImage = NSImage(size: newSize)

        resizedImage.lockFocus()
        image.draw(in: NSRect(origin: .zero, size: newSize),
            from: NSRect(origin: .zero, size: imageSize),
            operation: .copy,
            fraction: 1.0)
        resizedImage.unlockFocus()

        return resizedImage
    }

    //endregion
}
