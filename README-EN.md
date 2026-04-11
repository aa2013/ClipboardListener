# clipshare_clipboard_listener

A clipboard listener plugin that currently supports Android, Windows, MacOS and Linux. It also
supports background synchronization on Android 10+ systems (dependent on Shizuku or Root).

This project was extracted from [ClipShare](https://github.com/aa2013/ClipShare).

> The English documentation was translated
> by [DeepSeek]([DeepSeek | 深度求索](https://www.deepseek.com/)).

------

[简体中文](./README.md) |English

------

## Platform Support

| Platform | Support                                                               |
|----------|:----------------------------------------------------------------------|
| Android  | ✔️ Fully supported (Android 10+ requires Shizuku or Root permissions) |
| Windows  | ✔️ Fully supported                                                    |
| Linux    | ✔️ Fully supported on X11                                             |
| macOS    | ✔️ Fully supported                                                    |
| iOS      | ✖️ Not supported yet                                                  |

## Clipboard Content Types

| Text | Image |
|:----:|:-----:|
|  ✔️  |  ✔️   |

## Quick Start

### Installation

Add this to your package's `pubspec.yaml` file:

```yaml
dependencies:
  clipshare_clipboard_listener: ^1.2.14
```

### Usage

```dart
import 'dart:io';

import 'package:clipshare_clipboard_listener/clipboard_manager.dart';
import 'package:clipshare_clipboard_listener/enums.dart';
import 'package:clipshare_clipboard_listener/models/clipboard_source.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> with ClipboardListener, WidgetsBindingObserver {
  // Alert window permission (required for Android 10+)  
  bool hasAlertWindowPermission = false;

  // Notification permission (required for Android 10+)  
  bool hasNotificationPermission = false;

  // Accessibility service (required for clipboard source info on Android)  
  bool hasAccessibilityPermission = false;

  // Clipboard permission: on some Android systems, the default is “allow only while using the app,”
  // which prevents the app from accessing the clipboard in the background. Requesting this permission resolves the issue.
  bool hasClipboardPermission = false;

  var env = EnvironmentType.none;

  var isGranted = false;

  @override
  void initState() {
    super.initState();
    clipboardManager.addListener(this);
    // Monitor lifecycle to check permissions  
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    super.dispose();
    clipboardManager.removeListener(this);
    WidgetsBinding.instance.removeObserver(this);
  }

  @override
  Widget build(BuildContext context) {
    //...  
  }

  @override
  void onClipboardChanged(ClipboardContentType type, String content, ClipboardSource? source) {
    // If the clipboard content is an image, then content is the local path or URI of the image.
    //...  
  }

  /// Check Android permissions  
  Future<void> checkAndroidPermissions() async {
    // Use the permission_handler package to simplify permission requests  
    hasAlertWindowPermission = await Permission.systemAlertWindow.isGranted;
    hasNotificationPermission = await Permission.notification.isGranted;
    hasAccessibilityPermission = await clipboardManager.checkAccessibility();
    hasClipboardPermission = await clipboardManager.checkClipboardPermission();
    setState(() {});
  }

  /// Permission status changed (currently Android only)  
  @override
  void onPermissionStatusChanged(EnvironmentType environment, bool isGranted) {
    debugPrint("env: ${environment.name}, granted: $isGranted");
    setState(() {
      env = environment;
      this.isGranted = isGranted;
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    switch (state) {
      case AppLifecycleState.resumed:
      // Check permissions when the app resumes  
        if (Platform.isAndroid) {
          checkAndroidPermissions();
        }
        break;
      default:
    }
  }
}  
```

> Check the example app of this plugin for a complete implementation.

## Android

You need to add a `provider` in `AndroidManifest.xml`:

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.FileProvider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths"/>
</provider>
```

Then, you need to add `file_paths.xml` under `res/xml`:

```xml
<paths>
    <external-cache-path name="external_cache" path="." />
</paths>
```

## API

| Method                        | Description                                                                                                                      | Android | Windows | Linux | macOS | iOS |
|-------------------------------|----------------------------------------------------------------------------------------------------------------------------------|---------|---------|-------|-------|-----|
| onClipboardChanged            | Clipboard content change event (includes content type, content, and source). Currently, source information is based X11 on Linux | ✔️      | ✔️      | ✔️    | ✔️    | ✖️  |
| startListening                | Start listening                                                                                                                  | ✔️      | ✔️      | ✔️    | ✔️    | ✖️  |
| stopListening                 | Stop listening                                                                                                                   | ✔️      | ✔️      | ✔️    | ✔️    | ✖️  |
| checkIsRunning                | Check if listening is active                                                                                                     | ✔️      | ✔️      | ✔️    | ✔️    | ✖️  |
| copy                          | Copy content (does not trigger `onClipboardChanged`)                                                                             | ✔️      | ✔️      | ✔️    | ✔️    | ✖️  |
| getSelectedFiles              | Get selected files in the file explorer                                                                                          | ✖️      | ✔️      | ✖️    | ✖️    | ✖️  |
| isEnableExcludeFormat         | return whether to receive excluded formats                                                                                       | ✖️      | ✔️      | ✖️    | ✖️    | ✖️  |
| setExcludeFormatEnabled       | Set whether to receive excluded formats                                                                                          | ✖️      | ✔️      | ✖️    | ✖️    | ✖️  |
| storeCurrentWindowHwnd        | Store the handle of the current window                                                                                           | ✖️      | ✔️      | ✔️    | ✔️    | ✖️  |
| pasteToPreviousWindow         | Paste into the previous window (requires calling `storeCurrentWindowHwnd` first)                                                 | ✖️      | ✔️      | ✔️    | ✔️    | ✖️  |
| setTempFileDir                | Set the temporary file directory (default is the current program path)                                                           | ✖️      | ✔️      | ✔️    | ✖️    | ✖️  |
| onPermissionStatusChanged     | Permission status change event (Android only)                                                                                    | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| checkPermission               | Check permissions (e.g., Shizuku, Root)                                                                                          | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| requestPermission             | Request permissions (e.g., Shizuku, Root)                                                                                        | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| getShizukuVersion             | Get the Shizuku version                                                                                                          | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| getLatestWriteClipboardSource | Get the latest app that wrote to the clipboard (requires Shizuku or Root)                                                        | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| checkAccessibility            | Check accessibility permissions (required for clipboard source info on Android)                                                  | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| requestAccessibility          | Request accessibility permissions                                                                                                | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| checkClipboardPermission      | Check clipboard permission                                                                                                       | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| requestClipboardPermission    | Request and modify clipboard permission (requires Shizuku or root)                                                               | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
