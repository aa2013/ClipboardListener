import 'dart:convert';

import 'package:clipboard_listener/models/app_info.dart';

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
    final id = data["id"];
    final name = data["name"];
    final timeStr = data["time"];
    print("timStr type ${timeStr.runtimeType}");
    final iconB64 = data["iconB64"];
    DateTime? time;
    if (timeStr != null) {
      time = DateTime.parse(timeStr);
    }
    return ClipboardSource(
      id: id!,
      name: name!,
      time: time,
      iconB64: iconB64,
    );
  } catch (err, stack) {
    return null;
  }
}
