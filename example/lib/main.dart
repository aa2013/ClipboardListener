import 'dart:io';
import 'dart:math';

import 'package:clipboard_listener/clipboard_manager.dart';
import 'package:clipboard_listener/enums.dart';
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

class _MyAppState extends State<MyApp> with ClipboardListener {
  String? type;
  String? content;
  String env = EnvironmentType.none.name;
  bool isGranted = false;

  @override
  void initState() {
    super.initState();
    clipboardManager.addListener(this);
    initHotKey();
    initMultiWindowEvent();
    if (Platform.isAndroid) {
      Permission.systemAlertWindow.request();
      clipboardManager.getCurrentEnvironment().then((env) {
        setState(() {
          this.env = env.name;
          this.isGranted = env != EnvironmentType.none;
        });
      });
    }
  }

  @override
  void dispose() {
    super.dispose();
    clipboardManager.removeListener(this);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Example'),
        ),
        body: Builder(builder: (BuildContext context) {
          return Column(
            children: [
              const SizedBox(
                height: 10,
              ),
              //region Android
              Visibility(
                visible: Platform.isAndroid,
                child: Column(
                  children: [
                    Text('current environment: $env, status: $isGranted'),
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
                    Padding(
                      padding: const EdgeInsets.only(left: 10, right: 10),
                      child: Row(
                        children: [
                          GestureDetector(
                            onTap: () {
                              clipboardManager
                                  .startListening(
                                startEnv: EnvironmentType.shizuku,
                                way: ClipboardListeningWay.hiddenApi,
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
                            },
                            child: const Chip(label: Text("Start listening by Shizuku")),
                          ),
                          const SizedBox(
                            width: 10,
                          ),
                          GestureDetector(
                            onTap: () {
                              clipboardManager
                                  .startListening(
                                startEnv: EnvironmentType.root,
                                way: ClipboardListeningWay.hiddenApi,
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
                            },
                            child: const Chip(label: Text("Start listening by Root")),
                          ),
                          const SizedBox(
                            width: 10,
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
                visible: Platform.isLinux,
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
          );
        }),
      ),
    );
  }

  @override
  void onClipboardChanged(ClipboardContentType type, String content) {
    setState(() {
      this.type = type.name;
      this.content = content;
    });
  }

  @override
  void onPermissionStatusChanged(EnvironmentType environment, bool isGranted) {
    debugPrint("env: ${environment.name}, granted: $isGranted");
    setState(() {
      env = environment.name;
      this.isGranted = isGranted;
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
