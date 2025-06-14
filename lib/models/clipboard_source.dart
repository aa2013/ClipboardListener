import 'dart:convert';
import 'dart:io';

import 'package:clipshare_clipboard_listener/models/app_info.dart';
import 'package:path/path.dart' as path;

class ClipboardSource extends AppInfo {
  final DateTime? time;

  ClipboardSource({
    required super.id,
    required super.name,
    required this.time,
    required super.iconB64,
  });

  @override
  Map<String, dynamic> toJson() {
    return {
      ...super.toJson(),
      "time": time.toString(),
    };
  }

  bool isTimeout(int timeoutMs) {
    if (time == null) return true;
    final now = DateTime.now();
    final offsetMs = now.difference(time!).inMilliseconds.abs();
    return offsetMs > timeoutMs;
  }

  @override
  String toString() {
    return jsonEncode(toJson());
  }
}

ClipboardSource? convert2Source(dynamic data) {
  try {
    final id = data["id"] as String;
    var name = data["name"] as String;
    if(Platform.isWindows) {
      if (name.isEmpty) {
        name = path.basenameWithoutExtension(id);
      }
    }
    final timeStr = data["time"];
    final iconB64 = data["iconB64"];
    DateTime? time;
    if (timeStr != null) {
      time = DateTime.parse(timeStr);
    }
    return ClipboardSource(
      id: id,
      name: name,
      time: time,
      iconB64: iconB64,
    );
  } catch (_) {
    return null;
  }
}
