import 'dart:io';
import 'dart:math';

import 'package:clipboard_listener/clipboard_manager.dart';
import 'package:clipboard_listener/enums.dart';
import 'package:clipboard_listener/notification_content_config.dart';
import 'package:desktop_multi_window/desktop_multi_window.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:hotkey_manager/hotkey_manager.dart';
import 'package:permission_handler/permission_handler.dart';

void main(List<String> args) {
  var isMultiWindow = args.firstOrNull == 'multi_window';
  if (isMultiWindow) {
    runApp(MultiWindow());
  } else {
    runApp(const MyApp());
  }
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> with ClipboardListener, WidgetsBindingObserver {
  String? type;
  String? content;
  EnvironmentType env = EnvironmentType.none;
  bool isGranted = false;
  ClipboardListeningWay way = ClipboardListeningWay.logs;
  bool hasAlertWindowPermission = false;
  bool hasNotificationPermission = false;

  @override
  void initState() {
    super.initState();
    clipboardManager.addListener(this);
    WidgetsBinding.instance.addObserver(this);
    initHotKey();
    initMultiWindowEvent();
    if (Platform.isAndroid) {
      clipboardManager.getCurrentEnvironment().then((env) {
        setState(() {
          this.env = env;
          this.isGranted = env != EnvironmentType.none;
        });
      });
      checkAndroidPermissions();
    }
  }

  Future<void> checkAndroidPermissions() async {
    hasAlertWindowPermission = await Permission.systemAlertWindow.isGranted;
    hasNotificationPermission = await Permission.notification.isGranted;
    setState(() {});
  }

  @override
  void dispose() {
    super.dispose();
    clipboardManager.removeListener(this);
    WidgetsBinding.instance.removeObserver(this);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Example'),
        ),
        body: Builder(builder: (context,){
          return SingleChildScrollView(
            scrollDirection: Axis.vertical,
            child: Column(
              children: [
                const SizedBox(
                  height: 10,
                ),
                //region Android
                Visibility(
                  visible: Platform.isAndroid,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Current environment: ${env.name}, Status: $isGranted'),
                      Padding(
                        padding: const EdgeInsets.only(left: 10, right: 10),
                        child: Row(
                          children: [
                            GestureDetector(
                              onTap: () {
                                clipboardManager.requestPermission(EnvironmentType.shizuku);
                              },
                              child: const Chip(label: Text("Request Shizuka")),
                            ),
                            const SizedBox(
                              width: 10,
                            ),
                            GestureDetector(
                              onTap: () {
                                clipboardManager.requestPermission(EnvironmentType.root);
                              },
                              child: const Chip(label: Text("Request Root")),
                            ),
                            const SizedBox(
                              width: 10,
                            ),
                          ],
                        ),
                      ),
                      const Text('Permissions:'),
                      Padding(
                        padding: const EdgeInsets.only(left: 10, right: 10),
                        child: Row(
                          children: [
                            RawChip(
                              label: const Text("Alert Window"),
                              selected: hasAlertWindowPermission,
                              onSelected: (_) async {
                                if (hasAlertWindowPermission) {
                                  return;
                                }
                                final result = await Permission.systemAlertWindow.request();
                                print("result isDenied = ${result.isDenied}");
                                if (result.isGranted) {
                                  setState(() {
                                    hasAlertWindowPermission = result.isGranted;
                                  });
                                  showSnackBarSuc(context, "SystemAlertWindow granted");
                                } else {
                                  showSnackBarErr(context, "SystemAlertWindow denied");
                                }
                              },
                            ),
                            const SizedBox(width: 10),
                            RawChip(
                              label: const Text("Notification"),
                              selected: hasNotificationPermission,
                              onSelected: (_) async {
                                if (hasNotificationPermission) {
                                  return;
                                }
                                final result = await Permission.notification.request();
                                if (result.isGranted) {
                                  setState(() {
                                    hasNotificationPermission = result.isGranted;
                                  });
                                  showSnackBarSuc(context, "Notification granted");
                                } else {
                                  showSnackBarErr(context, "Notification denied");
                                }
                              },
                            ),
                          ],
                        ),
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
                                  startListeningOnAndroid(
                                    context,
                                    env: EnvironmentType.shizuku,
                                    way: way,
                                  );
                                }
                                    : null,
                                child: Chip(
                                  label: Text(
                                    "Start listening by Shizuku",
                                    style: env == EnvironmentType.shizuku ? null : const TextStyle(color: Colors.grey),
                                  ),
                                ),
                              ),
                              GestureDetector(
                                onTap: env == EnvironmentType.root
                                    ? () {
                                  startListeningOnAndroid(
                                    context,
                                    env: EnvironmentType.root,
                                    way: way,
                                  );
                                }
                                    : null,
                                child: Chip(
                                  label: Text(
                                    "Start listening by Root",
                                    style: env == EnvironmentType.root ? null : const TextStyle(color: Colors.grey),
                                  ),
                                ),
                              ),
                              const SizedBox(
                                width: 10,
                              ),
                            ],
                          ),
                        ),
                      ),
                      Container(
                        margin: const EdgeInsets.symmetric(vertical: 5),
                        child: Text("listening way: ${way.name}"),
                      ),
                      Padding(
                        padding: const EdgeInsets.only(left: 10, right: 10),
                        child: Row(
                          children: [
                            RawChip(
                              label: const Text("Hidden API"),
                              selected: way == ClipboardListeningWay.hiddenApi,
                              onSelected: (_) async {
                                setState(() {
                                  way = ClipboardListeningWay.hiddenApi;
                                });
                                await clipboardManager.stopListening();
                                Future.delayed(const Duration(seconds: 1), () {
                                  startListeningOnAndroid(
                                    context,
                                    env: env,
                                    way: ClipboardListeningWay.hiddenApi,
                                  );
                                });
                              },
                            ),
                            const SizedBox(width: 10),
                            RawChip(
                              label: const Text("System Logs"),
                              selected: way == ClipboardListeningWay.logs,
                              onSelected: (_) async {
                                setState(() {
                                  way = ClipboardListeningWay.logs;
                                });
                                await clipboardManager.stopListening();
                                Future.delayed(const Duration(seconds: 1), () {
                                  startListeningOnAndroid(
                                    context,
                                    env: env,
                                    way: ClipboardListeningWay.logs,
                                  );
                                });
                              },
                            ),
                          ],
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.only(left: 10, right: 10),
                        child: Row(
                          children: [
                            GestureDetector(
                              onTap: () {
                                clipboardManager.stopListening();
                                showSnackBarSuc(
                                  context,
                                  "Listening stopped",
                                );
                              },
                              child: const Chip(label: Text("Stop listening")),
                            ),
                            const SizedBox(
                              width: 10,
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                //endregion

                //region Linux
                Visibility(
                  visible: !Platform.isAndroid,
                  child: Column(
                    children: [
                      GestureDetector(
                        onTap: () {
                          clipboardManager.startListening().then((res) {
                            if (res) {
                              showSnackBarSuc(
                                context,
                                "Listening started successfully",
                              );
                            } else {
                              showSnackBarErr(
                                context,
                                "Listening failed to start",
                              );
                            }
                          });
                        },
                        child: const Chip(label: Text("Start listening")),
                      ),
                      GestureDetector(
                        onTap: () {
                          clipboardManager.stopListening().then((res) {
                            showSnackBarSuc(
                              context,
                              "Listening stopped successfully",
                            );
                          }).catchError((err) {
                            showSnackBarErr(
                              context,
                              "Listening failed to stop",
                            );
                          });
                        },
                        child: const Chip(label: Text("Stop listening")),
                      ),
                    ],
                  ),
                ),
                //endregion
                Text('type: $type\n\ncontent:\n$content\n\n'),
                const SizedBox(
                  height: 10,
                ),
                const TextField(),
                const SizedBox(
                  height: 10,
                ),
                GestureDetector(
                  onTap: () {
                    clipboardManager.copy(ClipboardContentType.text, Random().nextInt(99999).toString());
                  },
                  child: const Chip(label: Text("Copy Random Data")),
                ),
                GestureDetector(
                  onTap: () {
                    clipboardManager.copy(ClipboardContentType.image, "/tmp/2025-01-16_22-29-42-6.png");
                  },
                  child: const Chip(label: Text("Copy Test Image(mannal set on code)")),
                ),
              ],
            ),
          );
        }),
      ),
    );
  }

  @override
  void onClipboardChanged(ClipboardContentType type, String content) {
    print("type: ${type.name}, content: $content");
    setState(() {
      this.type = type.name;
      this.content = content;
    });
  }

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
        if (Platform.isAndroid) {
          checkAndroidPermissions();
        }
        break;
      default:
    }
  }

  Future startListeningOnAndroid(
    BuildContext context, {
    NotificationContentConfig? notificationContentConfig,
    EnvironmentType? env,
    ClipboardListeningWay? way,
  }) {
    if (!Platform.isAndroid) return Future.value();
    //Android version>= 10
    if (!hasAlertWindowPermission) {
      showSnackBarErr(context, "No Alert Window permission");
      return Future.value();
    }
    //Android version>= 10
    if (!hasNotificationPermission) {
      showSnackBarErr(context, "No Notification permission");
      return Future.value();
    }
    return clipboardManager
        .startListening(
      env: env,
      way: way,
      notificationContentConfig: notificationContentConfig,
    )
        .then((res) {
      if (res) {
        showSnackBarSuc(
          context,
          "Listening started successfully",
        );
      } else {
        showSnackBarErr(
          context,
          "Listening failed to start",
        );
      }
    });
  }

  void showSnackBar(BuildContext context, String text, Color color) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(text),
        backgroundColor: color,
      ),
    );
  }

  void showSnackBarSuc(BuildContext context, String text) {
    showSnackBar(context, text, Colors.lightBlue);
  }

  void showSnackBarErr(BuildContext context, String text) {
    showSnackBar(context, text, Colors.redAccent);
  }

  Future<void> initHotKey() async {
    await hotKeyManager.unregisterAll();
    final key = HotKey(
      key: PhysicalKeyboardKey.keyG,
      modifiers: [HotKeyModifier.control, HotKeyModifier.alt],
      scope: HotKeyScope.system,
    );
    await hotKeyManager.register(
      key,
      keyDownHandler: (hotKey) async {
        if (Platform.isWindows) {
          await clipboardManager.storeCurrentWindowHwnd();
        }
        //createWindow里面的参数必须传
        final window = await DesktopMultiWindow.createWindow('{}');
        window
          ..setFrame(const Offset(500, 500) & const Size(355.0, 630.0))
          ..setTitle('Window')
          ..show();
      },
    );
  }

  void initMultiWindowEvent() {
    DesktopMultiWindow.setMethodHandler((
      MethodCall call,
      int fromWindowId,
    ) {
      Clipboard.setData(ClipboardData(text: DateTime.now().toString()));
      clipboardManager.pasteToPreviousWindow();
      return Future.value();
    });
  }
}

class MultiWindow extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text("MultiWindow"),
        ),
        body: Column(
          children: [
            TextButton(
                onPressed: () {
                  DesktopMultiWindow.invokeMethod(
                    0,
                    "methodName",
                    "{}",
                  );
                },
                child: const Text("click me"))
          ],
        ),
      ),
    );
  }
}
