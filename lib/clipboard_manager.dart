import 'dart:io';

import 'package:clipboard_listener/enums.dart';
import 'package:clipboard_listener/models/clipboard_source.dart';
import 'package:clipboard_listener/models/notification_content_config.dart';
import 'package:flutter/services.dart';

class SelectedFilesResult {
  final List<String> list;
  final bool succeed;

  SelectedFilesResult(this.succeed, this.list);

  SelectedFilesResult.empty({this.succeed = false, this.list = const []});
}

abstract mixin class ClipboardListener {
  /// Called when the clipboard content changes.
  ///
  /// [type] The type of clipboard content, such as text or image.
  /// If the clipboard contains both text and other types, it will return the text and ignore the other types.
  ///
  /// [content] The actual content in the clipboard, typically a string or path of an image.
  ///
  /// [source] Clipboard content source, usually the writer, but not guaranteed to be accurate. On Android, using shell to retrieve it may have high latency. Use the [getLatestWriteClipboardSource] method to asynchronously fetch it.
  void onClipboardChanged(ClipboardContentType type, String content, ClipboardSource? source);

  /// Called when the permission status changes.（Only Android and IOS）
  ///
  /// [environment] The environment in which the permission status changed.
  /// ### Android
  /// - shizuku
  /// - root
  /// - androidPre10
  ///
  /// ### IOS
  /// - none
  ///
  /// [isGranted] When the Android system version is before Android 10, always return true
  void onPermissionStatusChanged(EnvironmentType environment, bool isGranted) {}
}

//common
const kChannelName = 'top.coclyun.clipshare/clipboard_listener';
const kOnClipboardChanged = "onClipboardChanged";
const kStartListening = "startListening";
const kCheckIsRunning = "checkIsRunning";
const kStopListening = "stopListening";
const kCopy = "copy";

//Windows
const kGetSelectedFiles = "getSelectedFiles";
const kStoreCurrentWindowHwnd = "storeCurrentWindowHwnd";
const kPasteToPreviousWindow = "pasteToPreviousWindow";

//Desktop
const kSetTempFileDir = "setTempFileDir";

//Android
const kOnPermissionStatusChanged = "onPermissionStatusChanged";
const kCheckPermission = "checkPermission";
const kRequestPermission = "requestPermission";
const kGetShizukuversion = "getShizukuVersion";
const kGetLatestWriteClipboardSource = "getLatestWriteClipboardSource";
const kCheckAccessibility = "checkAccessibility";
const kRequestAccessibility = "requestAccessibility";

class ClipboardManager {
  final _channel = const MethodChannel(kChannelName);

  ClipboardManager._private() {
    _channel.setMethodCallHandler(_methodCallHandler);
  }

  final List<ClipboardListener> _listeners = [];
  static final ClipboardManager instance = ClipboardManager._private();

  void addListener(ClipboardListener listener) {
    _listeners.add(listener);
  }

  void removeListener(ClipboardListener listener) {
    _listeners.remove(listener);
  }

  ///start listening clipboard change event
  ///[title] notification title text
  ///[desc] notification description text
  ///[env] the listening mode you want to enable.The options are either a or b. If null, it will automatically select based on the current environment.
  Future<bool> startListening({
    NotificationContentConfig? notificationContentConfig,
    EnvironmentType? env,
    ClipboardListeningWay? way,
  }) {
    var args = <String, dynamic>{};
    assert(() {
      if (Platform.isAndroid) {
        return way != null;
      }
      return true;
    }());
    if (env != null) {
      args["env"] = env.name;
      if (env == EnvironmentType.none) {
        return Future(() => false);
      }
    }
    if (way != null) {
      args["way"] = way.name;
    }
    if (notificationContentConfig != null) {
      args.addAll(notificationContentConfig.toJson());
    }
    return _channel.invokeMethod<bool>(kStartListening, args.isEmpty ? null : args).then((value) => value ?? false);
  }

  /// stop listening clipboard
  Future<bool> stopListening() {
    return _channel.invokeMethod<bool>(kStopListening).then((value) => value ?? false);
  }

  ///get the version for Shizuku. (Only Android)
  Future<int?> getShizukuVersion() {
    if (!Platform.isAndroid) return Future(() => null);
    return _channel.invokeMethod<int>(kGetShizukuversion);
  }

  ///check listener running status
  Future<bool> checkIsRunning() {
    return _channel.invokeMethod<bool>(kCheckIsRunning).then((value) => value ?? false);
  }

  ///Check if there is permission (only Android/IOS)
  Future<bool> checkPermission(EnvironmentType env) {
    if (!Platform.isAndroid && !Platform.isIOS) return Future.value(false);
    final args = {"env": env.name};
    return _channel.invokeMethod<bool>(kCheckPermission, args).then((value) => value ?? false);
  }

  ///request permission (only Android/IOS)
  Future<void> requestPermission(EnvironmentType env) {
    if (!Platform.isAndroid && !Platform.isIOS) return Future.value();
    final args = {"env": env.name};
    return _channel.invokeMethod(kRequestPermission, args);
  }

  ///get files selected by user in the explorer (only Windows and not desktop folder)
  Future<SelectedFilesResult> getSelectedFiles() {
    if (!Platform.isWindows) {
      return Future.value(SelectedFilesResult.empty());
    }
    return _channel.invokeMethod<dynamic>(kGetSelectedFiles).then((res) {
      if (res == null) {
        return SelectedFilesResult.empty();
      }
      return SelectedFilesResult(res["succeed"] ?? false, (res["list"] as String?)?.split(";") ?? []);
    });
  }

  /// copy self content to clipboard，only support text and image
  ///
  ///[type] only support text and image
  ///[content] The content that needs to be copied, if it is an image, is the image path
  Future<bool> copy(ClipboardContentType type, String content) {
    final args = {"type": type.name, "content": content};
    return _channel.invokeMethod<bool>(kCopy, args).then((value) => value ?? false);
  }

  ///Save the hwnd of the current window and use it in conjunction with [pasteToPreviousWindow] method
  Future<void> storeCurrentWindowHwnd() {
    if (!Platform.isWindows) return Future.value();
    return _channel.invokeMethod(kStoreCurrentWindowHwnd);
  }

  ///send Ctrl + V to previous Window
  ///Ensure [storeCurrentWindowHwnd] is called before pasting clipboard data into the previous window.
  ///[keyDelayMs] The interval duration of each key, if the duration is too short, the combination key may not work properly
  Future<void> pasteToPreviousWindow([int keyDelayMs = 100]) {
    if (!Platform.isWindows) return Future.value();
    if (keyDelayMs < 0) {
      throw Exception("KeyDelayMs cannot be less than 0");
    }
    final args = {"keyDelayMs": keyDelayMs};
    return _channel.invokeMethod(kPasteToPreviousWindow, args);
  }

  ///getCurrentEnvironment (only Android)
  Future<EnvironmentType> getCurrentEnvironment() async {
    if (!Platform.isAndroid) {
      return EnvironmentType.none;
    }
    for (var env in EnvironmentType.values) {
      bool hasPermission = await clipboardManager.checkPermission(env);
      if (hasPermission) return env;
    }
    return EnvironmentType.none;
  }

  ///end with '/' or '\'
  Future<void> setTempFileDir(String dirPath) async {
    if (!Platform.isWindows) return;
    _channel.invokeMethod(kSetTempFileDir, {"tempFileDir": dirPath});
  }

  /// Get the latest source written to the clipboard, but accuracy is not guaranteed
  /// On Android, it is based on shell scripts and may be time-consuming
  Future<ClipboardSource?> getLatestWriteClipboardSource() async {
    if (!Platform.isAndroid) return null;
    return _channel.invokeMethod<dynamic>(
      kGetLatestWriteClipboardSource,
      {},
    ).then((data) {
      if (data == null) return null;
      return convert2Source(data);
    });
  }

  /// Check for accessibility permission.
  /// Accessibility permission is used to monitor changes in the top activity to determine which app the content was copied from.
  Future<bool> checkAccessibility() async {
    if (!Platform.isAndroid) return false;
    return _channel.invokeMethod<bool?>(kCheckAccessibility, {}).then((res) => res ?? false);
  }

  /// Request accessibility permission.
  /// Accessibility permission is used to monitor changes in the top activity to determine which app the content was copied from.
  Future<void> requestAccessibility() async {
    if (!Platform.isAndroid) return;
    return _channel.invokeMethod<void>(kRequestAccessibility, {});
  }

  Future<void> _methodCallHandler(MethodCall call) async {
    var arguments = call.arguments as Map;
    for (var listener in _listeners) {
      switch (call.method) {
        case kOnClipboardChanged:
          String content = arguments['content'];
          var type = ClipboardContentType.parse(arguments['type']);
          ClipboardSource? source;
          try {
            dynamic data = arguments.containsKey("source") ? arguments["source"] : null;
            source = convert2Source(data);
          } catch (_) {}
          listener.onClipboardChanged(type, content, source);
          break;
        case kOnPermissionStatusChanged:
          var env = EnvironmentType.parse(arguments['env']);
          var isGranted = arguments['isGranted'];
          listener.onPermissionStatusChanged(env, isGranted);
          break;
      }
    }
  }
}

final clipboardManager = ClipboardManager.instance;
