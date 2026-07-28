import 'dart:io';

import 'package:clipshare_clipboard_listener/clipboard_manager.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

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

  /// Starts the listener used by non-Android platforms and surfaces OHOS float-window failures.
  Future<void> _startListening(BuildContext context) async {
    try {
      final started = await clipboardManager.startListening();
      if (!context.mounted) return;

      if (started) {
        showSnackBarSuc(context, 'Listening started successfully');
      } else {
        showSnackBarErr(context, 'Listening failed to start');
      }
    } on PlatformException catch (err) {
      if (!context.mounted) return;
      showSnackBarErr(context, _formatStartError(err));
    }
  }

  /// Stops the listener and lets OHOS native code release its bound float window.
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

  /// Converts OHOS floating-window MethodChannel errors into concise UI feedback.
  String _formatStartError(PlatformException err) {
    if (!Platform.isOhos) {
      return 'Listening failed to start';
    }
    switch (err.code) {
      case 'OHOS_FLOAT_WINDOW_PERMISSION_DENIED':
        return 'OHOS floating-window permission denied';
      case 'OHOS_FLOAT_WINDOW_UNAVAILABLE':
        return 'OHOS floating window unavailable';
      case 'OHOS_ABILITY_UNAVAILABLE':
        return 'OHOS ability unavailable';
      default:
        return err.message ?? 'Listening failed to start';
    }
  }
}
