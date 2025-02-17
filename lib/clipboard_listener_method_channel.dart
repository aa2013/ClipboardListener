import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'clipboard_listener_platform_interface.dart';

/// An implementation of [ClipboardListenerPlatform] that uses method channels.
class MethodChannelClipboardListener extends ClipboardListenerPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('clipboard_listener');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }
}
