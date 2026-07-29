import 'package:clipshare_clipboard_listener/clipboard_manager.dart';
import 'package:flutter/material.dart';

import '../widgets/snack_bar_helper.dart';

class NonAndroidControls extends StatelessWidget {
  const NonAndroidControls({super.key});

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        GestureDetector(
          onTap: () => _startListening(context),
          child: const Chip(label: Text('Start listening')),
        ),
        GestureDetector(
          onTap: () => _stopListening(context),
          child: const Chip(label: Text('Stop listening')),
        ),
      ],
    );
  }

  /// Starts the default listener used by desktop and iOS platforms.
  Future<void> _startListening(BuildContext context) async {
    final started = await clipboardManager.startListening();
    if (!context.mounted) return;

    if (started) {
      showSnackBarSuc(context, 'Listening started successfully');
    } else {
      showSnackBarErr(context, 'Listening failed to start');
    }
  }

  /// Stops the default listener used by desktop and iOS platforms.
  Future<void> _stopListening(BuildContext context) async {
    try {
      await clipboardManager.stopListening();
      if (!context.mounted) return;
      showSnackBarSuc(context, 'Listening stopped successfully');
    } catch (err) {
      if (!context.mounted) return;
      showSnackBarErr(context, 'Listening failed to stop');
    }
  }
}
