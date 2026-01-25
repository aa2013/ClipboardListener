import Flutter
import UIKit
import AVKit

public class ClipshareClipboardListenerPlugin: NSObject, FlutterPlugin{
    private static let kChannelName = "top.coclyun.clipshare/clipboard_listener"
    private static let kOnClipboardChanged = "onClipboardChanged"
    private static let kStartListening = "startListening"
    private static let kStopListening = "stopListening"
    private static let kCheckIsRunning = "checkIsRunning"
    private static let kCopy = "copy"
    private static let kStartPip = "startPip"
    private static let kStopPIP = "stopPIP"

    private var ignoreNextCopy = false
    private var listening = false
    private var channel: FlutterMethodChannel!
    private var changeCount = 0
    var timer: Timer?


    // ===== PiP 相关 =====
    private var containerView: UIView?
    private var player: AVPlayer?
    private var playerLayer: AVPlayerLayer?
    private var pipController: AVPictureInPictureController?
    private var timeControlObserver: NSKeyValueObservation?



    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: kChannelName, binaryMessenger: registrar.messenger())
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
        case ClipshareClipboardListenerPlugin.kCopy:
            copy(args: args, result: result)
        case ClipshareClipboardListenerPlugin.kCheckIsRunning:
            checkIsRunning(result: result)
        case ClipshareClipboardListenerPlugin.kStartPip:
            startPip(args: args, result: result)
        case ClipshareClipboardListenerPlugin.kStopPIP:
            stopPip(result: result)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private func startListening(result: @escaping FlutterResult) {
        if listening {
            result(true)
            return
        }
        listening = true
        let notificationCenter = NotificationCenter.default
        notificationCenter.addObserver(
            self,
            selector: #selector (onClipboardChanged),
            name: UIPasteboard.changedNotification,
            object: nil
        )
        notificationCenter.addObserver(
            self,
            selector: #selector(onClipboardChanged),
            name: UIApplication.willEnterForegroundNotification,
            object: nil)
        setupAppBackgroundNotification()
        result(true)
    }

    @objc func onClipboardChanged() {
        var pasteboard = UIPasteboard.general
        if (pasteboard.changeCount == changeCount){
            return
        }
        changeCount = pasteboard.changeCount;
        if !listening {
            return
        }
        if ignoreNextCopy {
            ignoreNextCopy = false
            return
        }
        var type = "text";
        var content = "";
        if pasteboard.hasStrings, let text = pasteboard.string {
            type = "text"
            content = text
        } else if pasteboard.hasImages, let image = pasteboard.image {
            type = "image"
            if let data = image.pngData() {
                let filename = "clipboard_\(UUID().uuidString).png"
                let tempDir = FileManager.default.temporaryDirectory
                let fileURL = tempDir.appendingPathComponent(filename)
                do {
                    try data.write(to: fileURL)
                    content = fileURL.path
                } catch {
                    print("save image failed: \(error)")
                }
            }
        } else {
            return
        }
        var args: [String: Any] = [
            "type": type,
            "content": content
        ]

        channel.invokeMethod(ClipshareClipboardListenerPlugin.kOnClipboardChanged, arguments: args, result: nil)
    }

    func startClipboardBackgroundMonitoring() {
        stopClipboardBackgroundMonitoring()
        timer = Timer.scheduledTimer(
            timeInterval: 0.5,
            target: self,
            selector: #selector(onClipboardChanged),
            userInfo: nil,
            repeats: true
        )

    }

    func stopClipboardBackgroundMonitoring() {
        timer?.invalidate()
        timer = nil
    }

    private func stopListening(result: @escaping FlutterResult) {
        if !listening {
            result(true)
            return
        }
        listening = false
        startClipboardBackgroundMonitoring()
        let notificationCenter = NotificationCenter.default
        notificationCenter.removeObserver(
            self,
            name: UIPasteboard.changedNotification,
            object: nil
        )
        result(true)
    }

    private func copy(args: [String: Any], result: @escaping FlutterResult) {
        ignoreNextCopy = true
        let type = args["type"] as? String
        let content = args["content"] as? String
        if type == nil || content == nil {
            result(false)
        } else {
            copyData(type: type!, content: content!, result: result)
        }
    }

    private func checkIsRunning(result: @escaping FlutterResult) {
        result(listening)
    }

    private func copyData(type: String, content: String, result: @escaping FlutterResult) {
        ignoreNextCopy = true
        var success = false
        let lower = type.lowercased()
        let pasteboard = UIPasteboard.general

        if lower == "text" {
            // 处理文本复制
            pasteboard.string = content
            success = true
            result(success)
        } else if lower == "image" {
            let fileURL = URL(fileURLWithPath: content)
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    if FileManager.default.fileExists(atPath: fileURL.path) {
                        let imageData = try Data(contentsOf: fileURL)
                        if let image = UIImage(data: imageData) {
                            pasteboard.image = image
                            result(true)
                            return
                        }
                    } else {
                        result(false)
                        return
                    }
                } catch {
                    print("read image failed: \(error)")
                    result(false)
                    return
                }
            }

        } else {
            result(false)
            return
        }
    }

    private func startPip(args: [String: Any], result: @escaping FlutterResult) {
        guard let path = args["path"] as? String else {
            result(false)
            return
        }
        let url: URL
        if path.hasPrefix("http://") || path.hasPrefix("https://") {
            guard let remoteURL = URL(string: path) else {
                result(false)
                return
            }
            url = remoteURL
        } else {
            if FileManager.default.fileExists(atPath: path) {
                print("pip video file exist")
            } else {
                print("pip video file not exist")
                result(false)
                return
            }
            url = URL(fileURLWithPath: path)
        }

        startPipDirectly(url: url)
        result(true)
    }


    private func startPipDirectly(url: URL) {

        guard let rootVC = UIApplication.shared.windows.first?.rootViewController else {
            return
        }

        let container = UIView(frame: CGRect(x: -10, y: -10, width: 1, height: 1))
        container.isHidden = false

        rootVC.view.addSubview(container)
        containerView = container

        let player = AVPlayer(url: url)
        self.player = player

        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .moviePlayback)
        try? session.setActive(true)


        let layer = AVPlayerLayer(player: player)
        layer.frame = container.bounds
        container.layer.addSublayer(layer)
        playerLayer = layer

        if AVPictureInPictureController.isPictureInPictureSupported() {
            print("supported pip")
            pipController = AVPictureInPictureController(playerLayer: layer)
            timeControlObserver = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in

                guard let self = self else { return }

                if player.timeControlStatus == .playing {
                    print("Playing video in player, starting pip")

                    self.timeControlObserver?.invalidate()
                    self.timeControlObserver = nil

                    self.pipController?.startPictureInPicture()
                    if let pip = self.pipController {
                        if pip.isPictureInPictureActive {
                            print("PiP active")
                        } else {
                            print("PiP inactive, retry after 300ms")
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                                self.pipController?.startPictureInPicture()
                                if pip.isPictureInPictureActive {
                                    print("PiP active after retry")
                                }else{
                                    print("PiP inactive after retry")
                                }
                            }
                        }
                    }
                }
            }
            player.play()
        } else {
            print("not supported pip")
        }
    }

    private func stopPip(result: @escaping FlutterResult){

        if let pip = pipController, pip.isPictureInPictureActive {
            pip.stopPictureInPicture()
        }

        player?.pause()
        player?.replaceCurrentItem(with: nil)

        timeControlObserver?.invalidate()
        timeControlObserver = nil

        playerLayer?.removeFromSuperlayer()
        containerView?.removeFromSuperview()

        pipController = nil
        playerLayer = nil
        player = nil
        containerView = nil
        result(true)
    }

    @objc func onAppEnterBackground() {
        startClipboardBackgroundMonitoring()
    }

    @objc func onAppEnterForeground() {
        stopClipboardBackgroundMonitoring()
    }

    private func setupAppBackgroundNotification() {
        let notificationCenter = NotificationCenter.default
        notificationCenter.addObserver(self, selector: #selector(onAppEnterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        notificationCenter.addObserver(self, selector: #selector(onAppEnterForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
    }

}
