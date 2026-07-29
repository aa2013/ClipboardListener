import 'dart:io';

import 'package:clipshare_clipboard_listener/clipboard_manager.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

const _pipVideoAssetPath = 'assets/pip_video.mp4';

class IosControls extends StatelessWidget {
  const IosControls({
    super.key,
    required this.startedPip,
    required this.onStartedPipChanged,
  });

  final bool startedPip;
  final ValueChanged<bool> onStartedPipChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        TextButton(
          onPressed: startedPip ? null : _startPip,
          child: const Text('Start PIP'),
        ),
        TextButton(
          onPressed: startedPip ? _stopPip : null,
          child: const Text('Stop PIP'),
        ),
      ],
    );
  }

  /// Starts iOS Picture in Picture with a local file copied from bundled assets.
  Future<void> _startPip() async {
    final path = await _copyAssetToTemp(_pipVideoAssetPath);
    debugPrint(path);
    await clipboardManager.startPIP(path);
    onStartedPipChanged(true);
  }

  /// Stops iOS Picture in Picture and updates the visible control state.
  Future<void> _stopPip() async {
    await clipboardManager.stopPIP();
    onStartedPipChanged(false);
  }

  /// Copies an asset to the temporary directory because native PIP needs a file path.
  Future<String> _copyAssetToTemp(String assetPath) async {
    final byteData = await rootBundle.load(assetPath);
    final tempDir = await getTemporaryDirectory();
    final tempFile = File('${tempDir.path}/${assetPath.split('/').last}');

    await tempFile.writeAsBytes(
      byteData.buffer.asUint8List(byteData.offsetInBytes, byteData.lengthInBytes),
    );

    return tempFile.absolute.path;
  }
}
