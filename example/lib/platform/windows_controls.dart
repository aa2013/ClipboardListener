import 'package:flutter/material.dart';

class WindowsControls extends StatelessWidget {
  const WindowsControls({
    super.key,
    required this.excludeFormatEnabled,
    required this.onExcludeFormatChanged,
  });

  final bool excludeFormatEnabled;
  final Future<void> Function(bool checked) onExcludeFormatChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        const Text('ExcludeClipboardContentFromMonitorProcessing:'),
        Switch(
          value: excludeFormatEnabled,
          onChanged: onExcludeFormatChanged,
        ),
      ],
    );
  }
}
