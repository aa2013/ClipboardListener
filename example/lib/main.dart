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
    } else if (Platform.isWindows) {
      clipboardManager.startListening().then((res){
        print("startListening $res");
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
        body: Column(
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
                  Row(
                    children: [
                      GestureDetector(
                        onTap: isGranted
                            ? null
                            : () {
                                clipboardManager
                                    .requestPermission(EnvironmentType.shizuku);
                              },
                        child: const Chip(label: Text("Request Shizuka")),
                      ),
                      const SizedBox(
                        width: 10,
                      ),
                      GestureDetector(
                        onTap: isGranted
                            ? null
                            : () {
                                clipboardManager
                                    .requestPermission(EnvironmentType.root);
                              },
                        child: const Chip(label: Text("Request Root")),
                      ),
                    ],
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
        ),
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
}
