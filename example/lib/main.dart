import 'dart:io';

import 'package:clipboard_listener/clipboard_manager.dart';
import 'package:clipboard_listener/enums.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(const MyApp());
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
                              clipboardManager
                                  .requestPermission(EnvironmentType.shizuku);
                            },
                            child: const Chip(label: Text("Request Shizuka")),
                          ),
                          const SizedBox(
                            width: 10,
                          ),
                          GestureDetector(
                            onTap: () {
                              clipboardManager
                                  .requestPermission(EnvironmentType.root);
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
                                      startEnv: EnvironmentType.shizuku)
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
                            child: const Chip(
                                label: Text("Start listening by Shizuku")),
                          ),
                          const SizedBox(
                            width: 10,
                          ),
                          GestureDetector(
                            onTap: () {
                              clipboardManager
                                  .startListening(
                                      startEnv: EnvironmentType.root)
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
                            child: const Chip(
                                label: Text("Start listening by Root")),
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
              Text('type: $type\n\ncontent:\n$content\n\n'),
              const SizedBox(
                height: 10,
              ),
              const TextField()
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
}
