# clipshare_clipboard_listener

剪贴板监听插件，当前支持 Android、Windows、Linux版本，同时，支持在Android10+系统上后台同步（依赖于Shizuku或Root）

该项目是从 [ClipShare](https://github.com/aa2013/ClipShare) 中抽离出来的

---

简体中文 | [English](./README-EN.md)

---

## 平台支持

| 平台    | 支持                                                |
| ------- | :-------------------------------------------------- |
| Android | ✔️ 完全支持，Android 10+ 需要依赖 Shizuku / Root权限 |
| Windows | ✔️ 完全支持                                          |
| Linux   | ✔️ 完全支持                                          |
| macOS   | ✖️ 暂不支持                                          |
| IOS     | ✖️ 暂不支持                                          |

## 剪贴板内容类型

| 文本 | 图片 |
| :----: | :----: |
| ✔️    | ✔️    |



## 快速开始

### 安装

将此添加到你的软件包的 `pubspec.yaml` 文件：

```yaml
dependencies:
  clipshare_clipboard_listener: ^1.0.3
```

### 用法

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
  //悬浮窗权限，Android10+需要请求该权限
  bool hasAlertWindowPermission = false;

  //通知权限，Android10+需要请求该权限
  bool hasNotificationPermission = false;

  //无障碍服务，Android 上若需要剪贴板来源需要请求该权限
  bool hasAccessibilityPermission = false;
    
  var env = EnvironmentType.none;
  
  var isGranted = false;

  @override
  void initState() {
    super.initState();
    clipboardManager.addListener(this);
    //监听生命周期检查权限
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
    // 若剪贴板内容是图片，那么content为图片的本机地址或者是URI
    //...
  }

  ///检查 Android 权限
  Future<void> checkAndroidPermissions() async {
    //使用 permission_handler 包简化权限请求操作
    hasAlertWindowPermission = await Permission.systemAlertWindow.isGranted;
    hasNotificationPermission = await Permission.notification.isGranted;
    hasAccessibilityPermission = await clipboardManager.checkAccessibility();
    setState(() {});
  }

  ///权限状态改变 (当前仅 Android)
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
        //监听生命周期检查权限
        if (Platform.isAndroid) {
          checkAndroidPermissions();
        }
        break;
      default:
    }
  }
}


```

> 请看这个插件的示例应用，以了解完整的例子。

## API

| 方法                          | 描述                                                         | Android | Windows | Linux | macOS | IOS  |
| ----------------------------- | ------------------------------------------------------------ | ------- | ------- | ----- | ----- | ---- |
| onClipboardChanged            | 剪贴板内容更改事件，会携带内容类别、内容、来源信息，来源信息当前只支持 Windows 和 Android | ✔️       | ✔️       | ✔️     | ✖️     | ✖️    |
| startListening                | 开始监听                                                     | ✔️       | ✔️       | ✔️     | ✖️     | ✖️    |
| stopListening                 | 停止监听                                                     | ✔️       | ✔️       | ✔️     | ✖️     | ✖️    |
| checkIsRunning                | 检查是否正在监听                                             | ✔️       | ✔️       | ✔️     | ✖️     | ✖️    |
| copy                          | 复制内容（不会触发`onClipboardChanged`事件）                 | ✔️       | ✔️       | ✔️     | ✖️     | ✖️    |
| getSelectedFiles              | 获取资源管理器中选择的文件                                   | ✖️       | ✔️       | ✖️     | ✖️     | ✖️    |
| storeCurrentWindowHwnd        | 存储当前窗体的句柄                                           | ✖️       | ✔️       | ✖️     | ✖️     | ✖️    |
| pasteToPreviousWindow         | 粘贴到前一个窗体中（需要在复制前先调用`storeCurrentWindowHwnd`） | ✖️       | ✔️       | ✖️     | ✖️     | ✖️    |
| setTempFileDir                | 设置临时文件目录，复制图片后会将文件暂存到该路径下，默认为当前程序路径下 | ✖️       | ✔️       | ✔️     | ✖️     | ✖️    |
| onPermissionStatusChanged     | 权限状态改变事件，当前只有Android有效                        | ✔️       | ✖️       | ✖️     | ✖️     | ✖️    |
| checkPermission               | 检查相关权限，如Shizuku、Root等                              | ✔️       | ✖️       | ✖️     | ✖️     | ✖️    |
| requestPermission             | 请求相关权限，如Shizuku、Root等                              | ✔️       | ✖️       | ✖️     | ✖️     | ✖️    |
| getShizukuVersion             | 获取Shizuku版本                                              | ✔️       | ✖️       | ✖️     | ✖️     | ✖️    |
| getLatestWriteClipboardSource | 获取最近一次写入剪贴板的应用信息（无需无障碍，通过dumpsys，需要Shizuku或Root权限） | ✔️       | ✖️       | ✖️     | ✖️     | ✖️    |
| checkAccessibility            | 检查无障碍权限，如无该权限，Andorid系统下`onClipboardChanged`的来源信息为 `null` | ✔️       | ✖️       | ✖️     | ✖️     | ✖️    |
| requestAccessibility          | 请求无障碍权限                                               | ✔️       | ✖️       | ✖️     | ✖️     | ✖️    |

