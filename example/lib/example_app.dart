import 'dart:io';

import 'package:clipshare_clipboard_listener/clipboard_manager.dart';
import 'package:clipshare_clipboard_listener/enums.dart';
import 'package:clipshare_clipboard_listener/models/clipboard_source.dart';
import 'package:clipshare_clipboard_listener/models/notification_content_config.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import 'platform/android_controls.dart';
import 'platform/desktop_multi_window.dart';
import 'platform/ios_controls.dart';
import 'platform/non_android_controls.dart';
import 'platform/windows_controls.dart';
import 'widgets/common_clipboard_controls.dart';
import 'widgets/snack_bar_helper.dart';

const _sourceTimeoutMillis = 2000;

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> with ClipboardListener, WidgetsBindingObserver {
  String? type;
  String? content;
  ClipboardSource? source;
  EnvironmentType env = EnvironmentType.none;
  bool isGranted = false;
  bool excludeFormatEnabled = true;
  ClipboardListeningWay way = ClipboardListeningWay.logs;
  bool hasAlertWindowPermission = false;
  bool hasNotificationPermission = false;
  bool hasAccessibilityPermission = false;
  bool hasClipboardPermission = false;
  bool startedPip = false;

  // Binder availability only means the Shizuku service is reachable, not that
  // the app already has Shizuku permission.
  bool? shizukuBinderAvailable;

  /// The latest foreground-window ownership reported by Windows (null before
  /// the first event).
  bool? lastForegroundIsSelf;

  final controller = TextEditingController();

  /// Returns the user-facing Shizuku binder connectivity status.
  String get shizukuBinderStatusText {
    final available = shizukuBinderAvailable;
    if (available == null) return 'unknown';
    return available ? 'available' : 'unavailable';
  }

  @override
  void initState() {
    super.initState();
    clipboardManager.addListener(this);
    WidgetsBinding.instance.addObserver(this);

    if (Platform.isMacOS || Platform.isLinux || Platform.isWindows) {
      initDesktopHotKey();
      initDesktopMultiWindowEvent();
    }
    if (Platform.isAndroid) {
      _loadAndroidEnvironment();
      checkAndroidPermissions();
    }
    if (Platform.isWindows) {
      _loadWindowsExcludeFormatState();
    }
  }

  @override
  void dispose() {
    clipboardManager.removeListener(this);
    WidgetsBinding.instance.removeObserver(this);
    controller.dispose();
    super.dispose();
  }

  /// Refreshes Android permission states that are displayed by the example UI.
  Future<void> checkAndroidPermissions() async {
    final hasAlertWindow = await Permission.systemAlertWindow.isGranted;
    final hasNotification = await Permission.notification.isGranted;
    final hasAccessibility = await clipboardManager.checkAccessibility();
    final hasClipboard = await clipboardManager.checkClipboardPermission();

    if (!mounted) return;
    setState(() {
      hasAlertWindowPermission = hasAlertWindow;
      hasNotificationPermission = hasNotification;
      hasAccessibilityPermission = hasAccessibility;
      hasClipboardPermission = hasClipboard;
    });
  }

  /// Starts Android clipboard listening after checking the required permissions.
  Future<void> startListeningOnAndroid(
    BuildContext context, {
    NotificationContentConfig? notificationContentConfig,
    EnvironmentType? env,
    ClipboardListeningWay? way,
  }) async {
    if (!Platform.isAndroid) return;

    // Android 10 and above need an overlay window for background clipboard access.
    if (!hasAlertWindowPermission) {
      showSnackBarErr(context, 'No Alert Window permission');
      return;
    }
    // The foreground notification is required while the Android listener service runs.
    if (!hasNotificationPermission) {
      showSnackBarErr(context, 'No Notification permission');
      return;
    }

    final started = await clipboardManager.startListening(
      env: env,
      way: way,
      notificationContentConfig: notificationContentConfig,
    );
    if (!context.mounted) return;

    if (started) {
      showSnackBarSuc(context, 'Listening started successfully');
    } else {
      showSnackBarErr(context, 'Listening failed to start');
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Example'),
        ),
        body: SafeArea(
          child: Builder(
            builder: (context) {
              return SingleChildScrollView(
                scrollDirection: Axis.vertical,
                child: Column(
                  children: [
                    const SizedBox(height: 10),
                    if (Platform.isAndroid)
                      AndroidControls(
                        env: env,
                        isGranted: isGranted,
                        shizukuBinderStatusText: shizukuBinderStatusText,
                        hasAlertWindowPermission: hasAlertWindowPermission,
                        hasNotificationPermission: hasNotificationPermission,
                        hasAccessibilityPermission: hasAccessibilityPermission,
                        hasClipboardPermission: hasClipboardPermission,
                        way: way,
                        onAlertWindowPermissionChanged: _setAlertWindowPermission,
                        onNotificationPermissionChanged: _setNotificationPermission,
                        onClipboardPermissionChanged: _setClipboardPermission,
                        onListeningWayChanged: _setListeningWay,
                        onStartListening: startListeningOnAndroid,
                      ),
                    if (!Platform.isAndroid) const NonAndroidControls(),
                    if (Platform.isWindows)
                      WindowsControls(
                        excludeFormatEnabled: excludeFormatEnabled,
                        onExcludeFormatChanged: _setExcludeFormatEnabled,
                      ),
                    if (Platform.isWindows)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 4),
                        child: Text(
                          'Foreground window: $foregroundStatusText',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ),
                    if (Platform.isIOS)
                      IosControls(
                        startedPip: startedPip,
                        onStartedPipChanged: _setStartedPip,
                      ),
                    CommonClipboardControls(
                      type: type,
                      content: content,
                      source: source,
                      controller: controller,
                    ),
                  ],
                ),
              );
            },
          ),
        ),
      ),
    );
  }

  @override
  void onClipboardChanged(ClipboardContentType type, String content, ClipboardSource? source) {
    debugPrint('type: ${type.name}, content: $content');
    setState(() {
      this.type = type.name;
      this.content = content;
      this.source = source;
    });

    final start = DateTime.now();
    clipboardManager.getLatestWriteClipboardSource().then((source) {
      if (source == null || !mounted) {
        return;
      }

      final isTimeout = source.isTimeout(_sourceTimeoutMillis);
      debugPrint('source time: ${source.time?.toString()}, timeout: $isTimeout');
      final end = DateTime.now();
      debugPrint('source: ${source.name}, offset: ${end.difference(start).inMilliseconds}');
      if (isTimeout) {
        return;
      }

      setState(() {
        this.source = source;
      });
    }).catchError((err) {
      debugPrint('error: $err');
    });
  }

  @override
  void onPermissionStatusChanged(EnvironmentType environment, bool isGranted) {
    debugPrint('env: ${environment.name}, granted: $isGranted');
    setState(() {
      env = environment;
      this.isGranted = isGranted;
    });
  }

  @override
  void onShizukuBinderStatusChanged(bool available) {
    debugPrint('Shizuku binder available: $available');
    setState(() {
      shizukuBinderAvailable = available;
    });
  }

  @override
  void onForegroundChanged(bool isSelf) {
    debugPrint('foreground isSelf: $isSelf');
    setState(() {
      lastForegroundIsSelf = isSelf;
    });
  }

  /// Returns the display text for the latest foreground-window ownership.
  String get foregroundStatusText {
    final isSelf = lastForegroundIsSelf;
    if (isSelf == null) return 'unknown';
    return isSelf ? 'this app' : 'another app';
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    switch (state) {
      case AppLifecycleState.resumed:
        if (Platform.isAndroid) {
          checkAndroidPermissions();
        }
        break;
      default:
    }
  }

  /// Reads the current Android privileged environment before rendering controls.
  Future<void> _loadAndroidEnvironment() async {
    final currentEnv = await clipboardManager.getCurrentEnvironment();
    if (!mounted) return;

    setState(() {
      env = currentEnv;
      isGranted = currentEnv != EnvironmentType.none;
    });
  }

  /// Reads the Windows privacy-format switch so the UI mirrors native state.
  Future<void> _loadWindowsExcludeFormatState() async {
    final enabled = await clipboardManager.isEnableExcludeFormat();
    if (!mounted) return;

    setState(() {
      excludeFormatEnabled = enabled;
    });
  }

  /// Stores the Android overlay permission result selected from the controls.
  void _setAlertWindowPermission(bool value) {
    setState(() {
      hasAlertWindowPermission = value;
    });
  }

  /// Stores the Android notification permission result selected from the controls.
  void _setNotificationPermission(bool value) {
    setState(() {
      hasNotificationPermission = value;
    });
  }

  /// Stores the Android clipboard permission result selected from the controls.
  void _setClipboardPermission(bool value) {
    setState(() {
      hasClipboardPermission = value;
    });
  }

  /// Changes the Android listening implementation shown by the controls.
  void _setListeningWay(ClipboardListeningWay value) {
    setState(() {
      way = value;
    });
  }

  /// Persists and displays the Windows exclude-format option.
  Future<void> _setExcludeFormatEnabled(bool value) async {
    await clipboardManager.setExcludeFormatEnabled(value);
    if (!mounted) return;

    setState(() {
      excludeFormatEnabled = value;
    });
  }

  /// Tracks whether iOS Picture in Picture is currently active.
  void _setStartedPip(bool value) {
    setState(() {
      startedPip = value;
    });
  }
}
