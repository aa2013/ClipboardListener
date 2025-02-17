import 'package:flutter/cupertino.dart';

enum ClipboardContentType {
  text,
  image,
  file,
  unknown;

  static ClipboardContentType parse(String value) =>
      ClipboardContentType.values.firstWhere(
        (e) => e.name.toUpperCase() == value.toUpperCase(),
        orElse: () {
          debugPrint("ClipboardContentType '$value' unknown");
          return ClipboardContentType.unknown;
        },
      );
}

enum EnvironmentType {
  shizuku,
  root,
  androidPre10,
  none;

  static EnvironmentType parse(String value) =>
      EnvironmentType.values.firstWhere(
        (e) => e.name == value,
        orElse: () {
          debugPrint("ClipboardContentType '$value' unknown");
          return EnvironmentType.none;
        },
      );
}
