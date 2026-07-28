# clipshare_clipboard_listener

剪贴板监听插件,支持在Android10+系统上后台同步（依赖于 Shizuku 或 Root）

该项目是从 [ClipShare](https://github.com/aa2013/ClipShare) 中抽离出来的

---

简体中文 | [English](./README-EN.md)

---

## 平台支持

| 平台      | 支持                                        |
|---------|:------------------------------------------|
| Android | ✔️ 完全支持，Android 10+ 需要依赖 Shizuku / Root权限 |
| Windows | ✔️ 完全支持                                   |
| Linux   | ✔️ 在 X11 和 Wayland(ext-data-control) 中支持  |
| macOS   | ✔️ 完全支持                                   |
| iOS     | ✔️ iOS14+ 支持基本的监听功能，但需要依赖画中画              |

## OHOS 构建说明

`flutter-hvigor-plugin` 是社区 `flutter-oh` 接入 OHOS Hvigor 构建系统的桥接插件，用于让 Hvigor 识别 Flutter 产物、插件 HAR 和相关打包任务。

如果构建时在 Hvigor 初始化阶段遇到：

```text
Cannot find module 'flutter-hvigor-plugin'
```

通常是因为部分社区 Flutter OHOS SDK 模板引用了该 Node 插件，但当前 SDK 内对应目录缺少 Node 可解析的包入口。

当前示例工程采用兼容处理：`example/ohos/hvigorfile.ts` 只保留 `@ohos/hvigor-ohos-plugin` 的 `appTasks`，并设置 `plugins: []`。这样 `flutter-oh` 会走旧版/Dart builder 路径，先生成 Flutter/HAR 产物，再交给 Hvigor 打包 HAP。

同时需要保留 `oh-package.json5` 中的 `har/*.har` 依赖或 `overrides`，否则 ArkTS 编译阶段可能找不到 `@ohos/flutter_ohos` 或插件 HAR。后续社区 SDK 修复 `flutter-hvigor-plugin` 包入口后，可以恢复模板默认构建路径。

## 剪贴板内容类型

| 文本 | 图片 |
|:--:|:--:|
| ✔️ | ✔️ |

## 快速开始

### 安装

将此添加到你的软件包的 `pubspec.yaml` 文件：

```yaml
dependencies:
  clipshare_clipboard_listener: ^1.3.4
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

  //剪贴板权限，Android 上部分系统默认设置为使用时允许，此时应用无法在后台操作剪贴板，请求该权限可解决
  bool hasClipboardPermission = false;

  //Shizuku binder 连接状态只表示服务是否可达，不等同于 Shizuku 授权状态
  bool? shizukuBinderAvailable;

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
    hasClipboardPermission = await clipboardManager.checkClipboardPermission();
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

  ///Shizuku binder 连接状态改变 (当前仅 Android)
  @override
  void onShizukuBinderStatusChanged(bool available) {
    debugPrint("Shizuku binder available: $available");
    setState(() {
      // binder 可用只代表 Shizuku 服务可连接，是否授权仍需通过 checkPermission 判断。
      shizukuBinderAvailable = available;
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

## Android

需要在 `AndroidManifest.xml` 中新增 `provider`

```xml

<provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.FileProvider"
    android:exported="false" android:grantUriPermissions="true">
    <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
</provider>
```

然后需要在 `res/xml` 中新增 `file_paths.xml`

```xml

<paths>
    <external-cache-path name="external_cache" path="." />
</paths>
```

## iOS

iOS 需要在 `info.plist` 中增加画中画支持

```
<key>UIBackgroundModes</key>
<array>
	<string>audio</string>
</array>
```

## API

| 方法                            | 描述                                                        | Android | Windows | Linux | macOS | IOS |
|-------------------------------|-----------------------------------------------------------|---------|---------|-------|-------|-----|
| onClipboardChanged            | 剪贴板内容更改事件，会携带内容类别、内容、来源信息，来源信息在Linux上基于X11实现              | ✔️      | ✔️      | ✔️    | ✔️    | ✔️  |
| startListening                | 开始监听                                                      | ✔️      | ✔️      | ✔️    | ✔️    | ✔️  |
| stopListening                 | 停止监听                                                      | ✔️      | ✔️      | ✔️    | ✔️    | ✔️  |
| checkIsRunning                | 检查是否正在监听                                                  | ✔️      | ✔️      | ✔️    | ✔️    | ✔️  |
| copy                          | 复制内容（不会触发`onClipboardChanged`事件）                          | ✔️      | ✔️      | ✔️    | ✔️    | ✔️  |
| startPIP                      | 启动画中画                                                     | ✖️      | ✖️      | ✖️    | ✖️    | ✔️  |
| stopPIP                       | 关闭画中画                                                     | ✖️      | ✖️      | ✖️    | ✖️    | ✔️  |
| getSelectedFiles              | 获取资源管理器中选择的文件                                             | ✖️      | ✔️      | ✖️    | ✖️    | ✖️  |
| isEnableExcludeFormat         | 是否接收排除格式                                                  | ✖️      | ✔️      | ✖️    | ✖️    | ✖️  |
| setExcludeFormatEnabled       | 设置是否接收排除格式                                                | ✖️      | ✔️      | ✖️    | ✖️    | ✖️  |
| pasteToPreviousWindow         | 粘贴到自动记录的上一个前台窗体中（macOS 为应用级别，Linux 仅支持 X11；Wayland 不支持激活前台窗体） | ✖️      | ✔️      | ✔️    | ✔️    | ✖️  |
| setTempFileDir                | 设置临时文件目录，复制图片后会将文件暂存到该路径下，默认为当前程序路径下                      | ✖️      | ✔️      | ✔️    | ✖️    | ✖️  |
| onPermissionStatusChanged     | 权限状态改变事件，当前只有Android有效                                    | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| onShizukuBinderStatusChanged  | Shizuku binder 可用/断开事件，仅表示服务连接状态，不代表已授权                     | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| checkPermission               | 检查相关权限，如Shizuku、Root等                                     | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| requestPermission             | 请求相关权限，如Shizuku、Root等                                     | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| getCurrentEnvironment         | 获取当前可用的 Android 工作环境，如 Shizuku、Root 或 Android 10 以下环境          | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| getShizukuVersion             | 获取Shizuku版本                                               | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| getLatestWriteClipboardSource | 获取最近一次写入剪贴板的应用信息（无需无障碍，通过dumpsys，需要Shizuku或Root权限）        | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| checkAccessibility            | 检查无障碍权限，如无该权限，Andorid系统下`onClipboardChanged`的来源信息为 `null` | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| requestAccessibility          | 请求无障碍权限，默认跳转系统设置；传入 `autoGrant: true` 时尝试通过当前 Shizuku/root 工作模式自动授权 | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| checkClipboardPermission      | 检查剪贴板权限                                                   | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| requestClipboardPermission    | 请求并修改剪贴板权限（需要 Shizuku 或 root）                             | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |
| executePrivilegedCommand      | 执行特权命令（需要 Shizuku 或 root）                                  | ✔️      | ✖️      | ✖️    | ✖️    | ✖️  |

