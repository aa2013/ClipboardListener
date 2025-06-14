import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/cupertino.dart';

class AppInfo {
  final String id;
  final String name;
  final String? iconB64;
  Uint8List? _iconBytes;

  Uint8List? get iconBytes {
    if (iconB64 == null || iconB64!.isEmpty) return null;
    try {
      _iconBytes ??= base64.decode(iconB64!);
    } catch (err, stack) {
      debugPrintStack(stackTrace: stack, label: err.toString());
    }
    return _iconBytes;
  }

  Map<String, dynamic> toJson() {
    return {
      "id": id,
      "name": name,
      "iconB64": iconB64,
    };
  }

  @override
  String toString() {
    return jsonEncode(toJson());
  }

  AppInfo({
    required this.id,
    required this.name,
    required this.iconB64,
  });
}
