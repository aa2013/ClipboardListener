import 'dart:io';

import 'package:clipboard_listener/enums.dart';
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
  void onClipboardChanged(ClipboardContentType type, String content);

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

const kChannelName = 'top.coclyun.clipshare/clipboard_listener';
const kOnClipboardChanged = "onClipboardChanged";
const kOnPermissionStatusChanged = "onPermissionStatusChanged";
const kStartListening = "startListening";
const kCheckIsRunning = "checkIsRunning";
const kCheckPermission = "checkPermission";
const kRequestPermission = "requestPermission";
const kGetSelectedFiles = "getSelectedFiles";
const kCopy = "copy";

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

  ///start listening clipboard change (only Android)
  Future<bool> startListening([String? title, String? desc]) {
    var args = <String, dynamic>{};
    if (title != null) {
      args["title"] = title;
    }
    if (desc != null) {
      args["desc"] = desc;
    }
    return _channel
        .invokeMethod<bool>(kStartListening, args.isEmpty ? null : args)
        .then((value) => value ?? false);
  }

  ///check listener running status (only Android)
  Future<bool> checkIsRunning() {
    if (!Platform.isAndroid) return Future.value(false);
    return _channel
        .invokeMethod<bool>(kCheckIsRunning)
        .then((value) => value ?? false);
  }

  ///Check if there is permission (only Android/IOS)
  Future<bool> checkPermission(EnvironmentType env) {
    if (!Platform.isAndroid && !Platform.isIOS) return Future.value(false);
    final args = {"env": env.name};
    return _channel
        .invokeMethod<bool>(kCheckPermission, args)
        .then((value) => value ?? false);
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
      return SelectedFilesResult(
        res["succeed"] ?? false,
        (res["list"] as String?)?.split(";") ?? [],
      );
    });
  }

  /// copy self content to clipboard，only support text and image
  ///
  ///[type] only support text and image
  ///[content] The content that needs to be copied, if it is an image, is the image path
  Future<bool> copy(ClipboardContentType type, String content) {
    final args = {"type": type.name, "content": content};
    return _channel
        .invokeMethod<bool>(kCopy, args)
        .then((value) => value ?? false);
  }

  ///getCurrentEnvironment (only Android)
  Future<EnvironmentType> getCurrentEnvironment() async {
    if (Platform.isAndroid) {
      return EnvironmentType.none;
    }
    for (var env in EnvironmentType.values) {
      bool hasPermission = await clipboardManager.checkPermission(env);
      if (hasPermission) return env;
    }
    return EnvironmentType.none;
  }

  Future<void> _methodCallHandler(MethodCall call) async {
    var arguments = call.arguments;
    for (var listener in _listeners) {
      switch (call.method) {
        case kOnClipboardChanged:
          String content = arguments['content'];
          var type = ClipboardContentType.parse(arguments['type']);
          listener.onClipboardChanged(type, content);
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
