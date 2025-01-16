
import 'clipboard_listener_platform_interface.dart';

class ClipboardListener {
  Future<String?> getPlatformVersion() {
    return ClipboardListenerPlatform.instance.getPlatformVersion();
  }
}
