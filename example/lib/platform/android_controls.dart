import 'package:clipshare_clipboard_listener/clipboard_manager.dart';
import 'package:clipshare_clipboard_listener/enums.dart';
import 'package:clipshare_clipboard_listener/models/notification_content_config.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import '../widgets/snack_bar_helper.dart';

typedef AndroidListeningStarter = Future<void> Function(
  BuildContext context, {
  NotificationContentConfig? notificationContentConfig,
  EnvironmentType? env,
  ClipboardListeningWay? way,
});

const _restartListeningDelay = Duration(seconds: 1);

class AndroidControls extends StatelessWidget {
  const AndroidControls({
    super.key,
    required this.env,
    required this.isGranted,
    required this.shizukuBinderStatusText,
    required this.hasAlertWindowPermission,
    required this.hasNotificationPermission,
    required this.hasAccessibilityPermission,
    required this.hasClipboardPermission,
    required this.way,
    required this.onAlertWindowPermissionChanged,
    required this.onNotificationPermissionChanged,
    required this.onClipboardPermissionChanged,
    required this.onListeningWayChanged,
    required this.onStartListening,
  });

  final EnvironmentType env;
  final bool isGranted;
  final String shizukuBinderStatusText;
  final bool hasAlertWindowPermission;
  final bool hasNotificationPermission;
  final bool hasAccessibilityPermission;
  final bool hasClipboardPermission;
  final ClipboardListeningWay way;
  final ValueChanged<bool> onAlertWindowPermissionChanged;
  final ValueChanged<bool> onNotificationPermissionChanged;
  final ValueChanged<bool> onClipboardPermissionChanged;
  final ValueChanged<ClipboardListeningWay> onListeningWayChanged;
  final AndroidListeningStarter onStartListening;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Current environment: ${env.name}, Status: $isGranted'),
        Text('Shizuku binder: $shizukuBinderStatusText (connectivity only, not permission)'),
        _EnvironmentPermissionControls(
          hasAlertWindowPermission: hasAlertWindowPermission,
          hasNotificationPermission: hasNotificationPermission,
          hasAccessibilityPermission: hasAccessibilityPermission,
          hasClipboardPermission: hasClipboardPermission,
          onAlertWindowPermissionChanged: onAlertWindowPermissionChanged,
          onNotificationPermissionChanged: onNotificationPermissionChanged,
          onClipboardPermissionChanged: onClipboardPermissionChanged,
        ),
        _AndroidListeningControls(
          env: env,
          way: way,
          onStartListening: onStartListening,
        ),
        _AndroidListeningWayControls(
          env: env,
          way: way,
          onListeningWayChanged: onListeningWayChanged,
          onStartListening: onStartListening,
        ),
      ],
    );
  }
}

class _EnvironmentPermissionControls extends StatelessWidget {
  const _EnvironmentPermissionControls({
    required this.hasAlertWindowPermission,
    required this.hasNotificationPermission,
    required this.hasAccessibilityPermission,
    required this.hasClipboardPermission,
    required this.onAlertWindowPermissionChanged,
    required this.onNotificationPermissionChanged,
    required this.onClipboardPermissionChanged,
  });

  final bool hasAlertWindowPermission;
  final bool hasNotificationPermission;
  final bool hasAccessibilityPermission;
  final bool hasClipboardPermission;
  final ValueChanged<bool> onAlertWindowPermissionChanged;
  final ValueChanged<bool> onNotificationPermissionChanged;
  final ValueChanged<bool> onClipboardPermissionChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(left: 10, right: 10),
          child: Row(
            children: [
              GestureDetector(
                onTap: () {
                  clipboardManager.requestPermission(EnvironmentType.shizuku);
                },
                child: const Chip(label: Text('Request Shizuka')),
              ),
              const SizedBox(width: 10),
              GestureDetector(
                onTap: () {
                  clipboardManager.requestPermission(EnvironmentType.root);
                },
                child: const Chip(label: Text('Request Root')),
              ),
              const SizedBox(width: 10),
            ],
          ),
        ),
        const Text('Permissions:'),
        Padding(
          padding: const EdgeInsets.only(left: 10, right: 10),
          child: Wrap(
            children: [
              RawChip(
                label: const Text('Alert Window'),
                selected: hasAlertWindowPermission,
                onSelected: (_) => _requestAlertWindowPermission(context),
              ),
              const SizedBox(width: 10),
              RawChip(
                label: const Text('Notification'),
                selected: hasNotificationPermission,
                onSelected: (_) => _requestNotificationPermission(context),
              ),
              const SizedBox(width: 10),
              RawChip(
                label: const Text('Accessibility'),
                selected: hasAccessibilityPermission,
                onSelected: (_) {
                  if (hasAccessibilityPermission) {
                    return;
                  }
                  clipboardManager.requestAccessibility();
                },
              ),
              const SizedBox(width: 10),
              RawChip(
                label: const Text('Clipboard'),
                selected: hasClipboardPermission,
                onSelected: (_) => _requestClipboardPermission(),
              ),
            ],
          ),
        ),
      ],
    );
  }

  /// Requests Android overlay permission and mirrors the granted state.
  Future<void> _requestAlertWindowPermission(BuildContext context) async {
    if (hasAlertWindowPermission) {
      return;
    }

    final result = await Permission.systemAlertWindow.request();
    debugPrint('result isDenied = ${result.isDenied}');
    if (!context.mounted) return;

    if (result.isGranted) {
      onAlertWindowPermissionChanged(result.isGranted);
      showSnackBarSuc(context, 'SystemAlertWindow granted');
    } else {
      showSnackBarErr(context, 'SystemAlertWindow denied');
    }
  }

  /// Requests Android notification permission and mirrors the granted state.
  Future<void> _requestNotificationPermission(BuildContext context) async {
    if (hasNotificationPermission) {
      return;
    }

    final result = await Permission.notification.request();
    if (!context.mounted) return;

    if (result.isGranted) {
      onNotificationPermissionChanged(result.isGranted);
      showSnackBarSuc(context, 'Notification granted');
    } else {
      showSnackBarErr(context, 'Notification denied');
    }
  }

  /// Requests the plugin-level Android clipboard permission and refreshes state.
  Future<void> _requestClipboardPermission() async {
    if (hasClipboardPermission) {
      return;
    }

    await clipboardManager.requestClipboardPermission();
    final granted = await clipboardManager.checkClipboardPermission();
    onClipboardPermissionChanged(granted);
  }
}

class _AndroidListeningControls extends StatelessWidget {
  const _AndroidListeningControls({
    required this.env,
    required this.way,
    required this.onStartListening,
  });

  final EnvironmentType env;
  final ClipboardListeningWay way;
  final AndroidListeningStarter onStartListening;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          margin: const EdgeInsets.symmetric(vertical: 5),
          child: const Text('Options:'),
        ),
        Padding(
          padding: const EdgeInsets.only(left: 10, right: 10),
          child: IntrinsicHeight(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                GestureDetector(
                  onTap: env == EnvironmentType.shizuku
                      ? () {
                          onStartListening(
                            context,
                            env: EnvironmentType.shizuku,
                            way: way,
                          );
                        }
                      : null,
                  child: Chip(
                    label: Text(
                      'Start listening by Shizuku',
                      style: env == EnvironmentType.shizuku ? null : const TextStyle(color: Colors.grey),
                    ),
                  ),
                ),
                GestureDetector(
                  onTap: env == EnvironmentType.root
                      ? () {
                          onStartListening(
                            context,
                            env: EnvironmentType.root,
                            way: way,
                          );
                        }
                      : null,
                  child: Chip(
                    label: Text(
                      'Start listening by Root',
                      style: env == EnvironmentType.root ? null : const TextStyle(color: Colors.grey),
                    ),
                  ),
                ),
                GestureDetector(
                  onTap: () {
                    clipboardManager.stopListening();
                    showSnackBarSuc(context, 'Listening stopped');
                  },
                  child: const Chip(label: Text('Stop listening')),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _AndroidListeningWayControls extends StatelessWidget {
  const _AndroidListeningWayControls({
    required this.env,
    required this.way,
    required this.onListeningWayChanged,
    required this.onStartListening,
  });

  final EnvironmentType env;
  final ClipboardListeningWay way;
  final ValueChanged<ClipboardListeningWay> onListeningWayChanged;
  final AndroidListeningStarter onStartListening;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          margin: const EdgeInsets.symmetric(vertical: 5),
          child: Text('listening way: ${way.name}'),
        ),
        Padding(
          padding: const EdgeInsets.only(left: 10, right: 10),
          child: Row(
            children: [
              RawChip(
                label: const Text('Hidden API'),
                selected: way == ClipboardListeningWay.hiddenApi,
                onSelected: (_) => _restartWithWay(context, ClipboardListeningWay.hiddenApi),
              ),
              const SizedBox(width: 10),
              RawChip(
                label: const Text('System Logs'),
                selected: way == ClipboardListeningWay.logs,
                onSelected: (_) => _restartWithWay(context, ClipboardListeningWay.logs),
              ),
            ],
          ),
        ),
      ],
    );
  }

  /// Restarts Android listening after switching the backend implementation.
  Future<void> _restartWithWay(BuildContext context, ClipboardListeningWay selectedWay) async {
    onListeningWayChanged(selectedWay);
    await clipboardManager.stopListening();
    Future.delayed(_restartListeningDelay, () {
      if (!context.mounted) return;
      onStartListening(
        context,
        env: env,
        way: selectedWay,
      );
    });
  }
}
