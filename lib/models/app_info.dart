import 'dart:convert';
import 'dart:typed_data';

class AppInfo {
  final String id;
  final String name;
  final String? iconB64;
  Uint8List? _iconBytes;

  Uint8List? get iconBytes {
    if (iconB64 == null) return null;
    _iconBytes ??= base64.decode(iconB64!);
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
