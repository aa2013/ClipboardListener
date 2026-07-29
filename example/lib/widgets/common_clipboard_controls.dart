import 'dart:math';

import 'package:clipshare_clipboard_listener/clipboard_manager.dart';
import 'package:clipshare_clipboard_listener/enums.dart';
import 'package:clipshare_clipboard_listener/models/clipboard_source.dart';
import 'package:flutter/material.dart';

const _randomClipboardMax = 99999;
const _testImagePath = '/storage/emulated/0/DCIM/Camera/20231104_210215.jpg';

class CommonClipboardControls extends StatelessWidget {
  const CommonClipboardControls({
    super.key,
    required this.type,
    required this.content,
    required this.source,
    required this.controller,
  });

  final String? type;
  final String? content;
  final ClipboardSource? source;
  final TextEditingController controller;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Text('type: $type\n\ncontent:\n$content\n\nsource:${source?.name}\n\n'),
        const SizedBox(height: 10),
        if (source?.iconBytes != null)
          Image.memory(
            source!.iconBytes!,
            height: 30,
            width: 30,
          ),
        const SizedBox(height: 10),
        TextField(controller: controller),
        const SizedBox(height: 10),
        GestureDetector(
          onTap: () {
            clipboardManager.copy(ClipboardContentType.text, controller.text);
          },
          child: const Chip(label: Text('Copy Input Data')),
        ),
        GestureDetector(
          onTap: () {
            clipboardManager.copy(ClipboardContentType.text, Random().nextInt(_randomClipboardMax).toString());
          },
          child: const Chip(label: Text('Copy Random Data')),
        ),
        GestureDetector(
          onTap: () {
            clipboardManager.copy(ClipboardContentType.image, _testImagePath);
          },
          child: const Chip(label: Text('Copy Test Image(mannal set on code)')),
        ),
      ],
    );
  }
}
