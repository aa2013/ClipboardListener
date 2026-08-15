## 1.3.6

* Linux X11：新增 `onForegroundChanged` 事件，前台窗口变化时上报给 Dart（与 Windows 一致）。Wayland 下不提供（无稳定的全局前台窗口句柄）。
* macOS：新增 `onForegroundChanged` 事件，前台应用切换时上报给 Dart（与 Windows/Linux 一致），`isSelf` 标记是否切回本进程。
---
* Linux X11: Emit `onForegroundChanged` when the foreground window changes (parity with Windows). Not available on Wayland (no stable global foreground window handle).
* macOS: Emit `onForegroundChanged` when the frontmost app changes (parity with Windows/Linux); `isSelf` marks whether the switch returned to this process.

## 1.3.5

* Windows 调整：`pasteToPreviousWindow` 返回 `PasteResult` 枚举，便于上层统一处理粘贴结果。
* Windows 调整：高权限窗口与完整性级别探测失败场景改为稳定状态返回
* Windows 调整：将粘贴方案修改为 `SendInput`
---
* Windows change: `pasteToPreviousWindow` now returns a `PasteResult` enum for consistent result handling in Dart.
* Windows change: Elevated-target and integrity-inspection failures now return stable result states.
* Windows change: Switched the paste implementation to SendInput.

## 1.3.4

* 桌面端调整：`pasteToPreviousWindow` 会自动跟踪上一个前台窗口/应用，移除公开的 `storeCurrentWindowHwnd` API。Linux 仅支持 X11，Wayland 不向普通客户端提供全局前台窗口激活能力。
---
* Desktop change: Automatically tracks the previous foreground window/app for `pasteToPreviousWindow`; removed the public `storeCurrentWindowHwnd` API. Linux support is X11-only because Wayland does not expose global foreground window activation to regular clients.

## 1.3.3
* Android 新增：无障碍权限请求支持 `autoGrant` 参数，可通过 Shizuku/root 自动授权。
* Android 修复：增强无障碍权限检测，降低不同 ROM 设置格式导致的误判。
* Android 修复：切换 Shizuku/root 工作模式时重建特权命令与剪贴板来源服务，避免复用旧后端。
* Android 修复：无障碍自动授权命令异常时明确返回失败，方便上层回退到系统设置页。
---
* Android feat: Adds an `autoGrant` option for accessibility requests through Shizuku/root.
* Android fix: Improves accessibility permission checks to avoid false negatives on ROM-specific setting formats.
* Android fix: Rebuilds privileged command and clipboard source services after switching between Shizuku/root modes.
* Android fix: Returns a clear failure when accessibility auto-grant commands fail, allowing callers to fall back to system settings.

## 1.3.2
* Android 修复：Shizuku binder 断开时安全清理本地状态，避免继续调用失效的 user service。
* Android 新增：提供 Shizuku binder 可用/断开状态回调，并与权限请求结果回调区分。
---
* Android fix: Safely clears local state when the Shizuku binder disconnects, avoiding calls into dead user services.
* Android feat: Adds a Shizuku binder availability callback separated from permission request results.

## 1.3.1
* Mac 修复: Mac 图片复制时未指定格式可能导致粘贴为 TIFF 格式的问题
---
* Mac Fix: Resolves an issue where copying an image without specifying a format could result in it being pasted as a TIFF file.

## 1.3.0
* 新增 iOS 支持
---
* Added iOS support

## 1.2.19
* Linux 修复：函数前置声明
---
* Linux fix: add forward declarations for clipboard callbacks

## 1.2.18
* Linux 新增：支持Wayland(ext-data-control)中监听
---
* Linux feat: Supported Wayland(ext-data-control)

## 1.2.17
* Android 修复：v1.2.16 导致的日志模式进程泄露问题
---
* Android fix: Log mode process leakage caused by v1.2.16

## 1.2.16
* Android 修复：Root 模式下的隐藏api监听进程的泄露问题
---
* Android fix: Leakage issue of hidden API listening process in Root mode

## 1.2.15
* Windows 修复：某些情况下可能监听不到剪贴板的问题
* Linux 修复：在Wayland下崩溃的问题
* Android 修复：Shizuku进程泄露和特定场景下的闪退问题
---
* Windows fix: An issue where clipboard content is sometimes not detected.
* Linux fix: Crash on wayland
* Android fix: Shizuku process leak and crashes in specific scenarios

## 1.2.14
* Android 优化：移除 FileProvider
---
* Android Optimization: Remove FileProvider

## 1.2.13
* Android 修复：复制图片后在某些app中无法粘贴的问题
---
* Android fix: The problem of not being able to paste copied images in some apps

## 1.2.12
* Android 新增：增加特权命令执行方法
---
* Android feat: Added privileged command execution method


## 1.2.11
* Android 修复：无Shizuku或Root权限时检查剪贴板权限崩溃的问题
---
* Android fix: Check clipboard permission crash issue when there is no Shizuku or Root permission

## 1.2.10
* Android 优化：剪贴板权限检测和请求
* Android 优化：优化初始化逻辑
---
* Android Optimization: Clipboard permission detection and request
* Android Optimization: Improved initialization logic

## 1.2.9
* Android 新增：剪贴板权限的检测和修改方法（需要 Shizuku 或 root）
---
* Android feat: Added methods to check and modify clipboard permissions (requires Shizuku or root).

## 1.2.8
* Android 修复 尝试修复 Android 悬浮窗可能未正确关闭的问题
---
* Android fix: Attempted to fix an issue where Android floating windows might not close properly

## 1.2.7
* Windows 允许启用或关闭排除格式记录
---
* Windows allows enable or disable the 'ExcludeClipboardContentFromMonitorProcessing' format

## 1.2.6
* Windows 修复 当剪贴板中含有 'ExcludeClipboardContentFromMonitorProcessing' 格式时未释放剪贴板导致其他进程无法复制的问题
* Android 优化 增加方法手动设置 Activity Class
---
* Windows fix: Fixed an issue where the clipboard was not released when it contained the 'ExcludeClipboardContentFromMonitorProcessing' format, preventing other processes from copying.
* Android optimization: Added a method to manually set the Activity Class.

## 1.2.5
* Android 优化 前台服务启动相关代码，不要使用 Activity 的 context
---
* Android optimization frontend service startup related code, do not use the context of the activity


## 1.2.4
* Android 修复 在ColorOS上因创建悬浮窗可能引起的系统奔溃导致的软重启
---
* Android Fix: Possible system crashes and restarts caused by creating floating windows on ColorOS

## 1.2.3
* Android 修复 当写入的图片 MIMEType 非 'image' 时通过文件头判断
---
* Android Fix: Determine file type via file header when the written image's MIMEType is not 'image'.

## 1.2.2
* Windows 排除 'ExcludeClipboardContentFromMonitorProcessing' 格式
---
* Windows exclude 'ExcludeClipboardContentFromMonitorProcessing' format

## 1.2.1
update README.md

## 1.2.0
* Linux 端支持获取剪贴板来源和粘贴到前一窗口功能（基于X11）

---

* Linux supports obtaining clipboard source and pasting to previous window (based on X11).

## 1.1.0
* 新增 MacOS 支持

---

* Added MacOS support

## 1.0.5
* 修复 因屏幕旋转重建Activity时可能导致的访问已死亡进程对象导致的异常闪退问题

---

* Fix the screen rotation to rebuild Activity, which may cause abnormal access to dead objects

## 1.0.4
* 修复 Android 通过插件复制图片失败的问题

---

* Fix the issue of failing to copy images via plugin on Android

## 1.0.3
* 从包中移除 `desktop_multi_window` 插件（误引用）

---

* Remove the desktop_multi_window plugin from the package (incorrectly referenced)

## 1.0.2
* 修复 Android 某些情况下 无障碍服务中包名为null的问题

---

* Fix the issue where the package name is null in AccessibilityService under certain conditions on Android.

## 1.0.1
* 修改包名

---

* Change package name

## 1.0.0

* 初始版本，支持Android、Windows、Linux平台的剪贴板监听，支持Andoroid10+系统后台监听

  ---
* Initial version, supports clipboard monitoring on Android, Windows, and Linux platforms, and supports background monitoring on Android 10+ systems.

