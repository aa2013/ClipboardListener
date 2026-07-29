import 'package:clipshare_clipboard_listener/clipboard_manager.dart';
import 'package:desktop_multi_window/desktop_multi_window.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:hotkey_manager/hotkey_manager.dart';

const _desktopWindowPayload = '{}';
final _desktopWindowFrame = const Offset(500, 500) & const Size(355.0, 630.0);
const _desktopWindowTitle = 'Window';
const _multiWindowMethodName = 'methodName';

/// Registers the desktop hotkey that opens the helper multi-window UI.
Future<void> initDesktopHotKey() async {
  await hotKeyManager.unregisterAll();
  final key = HotKey(
    key: PhysicalKeyboardKey.keyG,
    modifiers: [HotKeyModifier.alt],
    scope: HotKeyScope.system,
  );
  await hotKeyManager.register(
    key,
    keyDownHandler: (hotKey) async {
      // createWindow requires a payload even when the example does not use it.
      final window = await DesktopMultiWindow.createWindow(_desktopWindowPayload);
      window
        ..setFrame(_desktopWindowFrame)
        ..setTitle(_desktopWindowTitle)
        ..show();
    },
  );
}

/// Handles paste requests from the helper desktop window.
void initDesktopMultiWindowEvent() {
  DesktopMultiWindow.setMethodHandler((
    MethodCall call,
    int fromWindowId,
  ) {
    Clipboard.setData(ClipboardData(text: DateTime.now().toString()));
    clipboardManager.pasteToPreviousWindow();
    return Future.value();
  });
}

class MultiWindow extends StatelessWidget {
  const MultiWindow({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('MultiWindow'),
        ),
        body: Column(
          children: [
            TextButton(
              onPressed: () {
                DesktopMultiWindow.invokeMethod(
                  0,
                  _multiWindowMethodName,
                  _desktopWindowPayload,
                );
              },
              child: const Text('click me'),
            ),
          ],
        ),
      ),
    );
  }
}
