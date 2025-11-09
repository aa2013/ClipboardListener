import Cocoa
import FlutterMacOS

public class ClipshareClipboardListenerPlugin: NSObject, FlutterPlugin {

    private static let kChannelName = "top.coclyun.clipshare/clipboard_listener"
    private static let kOnClipboardChanged = "onClipboardChanged"
    private static let kStartListening = "startListening"
    private static let kStopListening = "stopListening"
    private static let kCheckIsRunning = "checkIsRunning"
    private static let kCopy = "copy"


    private var channel: FlutterMethodChannel!
    private let pasteboard = NSPasteboard.general
    private var changeCount: Int = -1
    private var timer: Timer?
    private var ignoreNextCopy = false

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
            if let image = NSImage(contentsOfFile: content) {
                pasteboard.clearContents()
                success = pasteboard.writeObjects([image])
            } else {
                print("Failed to load image from path: \(content)")
                success = false
            }
        }
        result(success)
    }

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
        var source:[String: Any]=[:]
        if let app = getFrontmostApplication() {
            // 应用ID (bundle identifier)
            if let bundleIdentifier = app!.bundleIdentifier {
                source["id"] = bundleIdentifier
            }

            // 应用名称
            if let localizedName = app!.localizedName {
                source["name"] = localizedName
            } else if let bundleIdentifier = app!.bundleIdentifier {
                source["name"] = bundleIdentifier
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

        guard let tiffData = image.tiffRepresentation,
        let bitmapImage = NSBitmapImageRep(data: tiffData),
        let pngData = bitmapImage.representation(using: .png, properties: [:]) else {
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

        guard let tiffData = resizedImage.tiffRepresentation,
        let bitmapImage = NSBitmapImageRep(data: tiffData),
        let pngData = bitmapImage.representation(using: .png, properties: [:]) else {
            return nil
        }

        // 转换为 Base64 字符串
        return pngData.base64EncodedString()
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
}
