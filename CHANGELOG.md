## 1.2.10
* Android 优化：剪贴板权限检测和请求
* Android 优化：优化初始化逻辑
---
* Android Optimization: Clipboard permission detection and request
* Android Optimization: Improved initialization logic

翻译英文

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

