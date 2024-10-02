import 'package:flutter_test/flutter_test.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockClipboardListenerPlatform
    with MockPlatformInterfaceMixin{

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  // final ClipboardManagerPlatform initialPlatform = ClipboardManagerPlatform.instance;
  //
  // test('$MethodChannelClipboardManager is the default instance', () {
  //   expect(initialPlatform, isInstanceOf<MethodChannelClipboardManager>());
  // });
  //
  // test('getPlatformVersion', () async {
  //   ClipboardManager clipboardListenerPlugin = ClipboardManager();
  //   MockClipboardListenerPlatform fakePlatform = MockClipboardListenerPlatform();
  //   ClipboardManagerPlatform.instance = fakePlatform;
  //
  //   expect(await clipboardListenerPlugin.getPlatformVersion(), '42');
  // });
}
