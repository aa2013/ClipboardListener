import 'dart:io';

import 'package:flutter/material.dart';

import 'example_app.dart';
import 'platform/desktop_multi_window.dart';

export 'example_app.dart';

const _multiWindowArg = 'multi_window';

void main(List<String> args) {
  final isDesktop = Platform.isMacOS || Platform.isLinux || Platform.isWindows;
  final isMultiWindow = args.firstOrNull == _multiWindowArg;

  // The secondary desktop window is only meaningful on platforms that support
  // desktop_multi_window.
  if (isDesktop && isMultiWindow) {
    runApp(const MultiWindow());
    return;
  }

  runApp(const MyApp());
}
