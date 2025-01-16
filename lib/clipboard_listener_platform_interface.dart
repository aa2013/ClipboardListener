import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'clipboard_listener_method_channel.dart';

abstract class ClipboardListenerPlatform extends PlatformInterface {
  /// Constructs a ClipboardListenerPlatform.
  ClipboardListenerPlatform() : super(token: _token);

  static final Object _token = Object();

  static ClipboardListenerPlatform _instance = MethodChannelClipboardListener();

  /// The default instance of [ClipboardListenerPlatform] to use.
  ///
  /// Defaults to [MethodChannelClipboardListener].
  static ClipboardListenerPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [ClipboardListenerPlatform] when
  /// they register themselves.
  static set instance(ClipboardListenerPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
