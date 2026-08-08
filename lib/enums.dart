import 'package:flutter/cupertino.dart';

enum ClipboardContentType {
  text,
  image,
  file,
  unknown;

  static ClipboardContentType parse(String value) => ClipboardContentType.values.firstWhere(
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

  static EnvironmentType parse(String value) => EnvironmentType.values.firstWhere(
        (e) => e.name == value,
        orElse: () {
          debugPrint("ClipboardContentType '$value' unknown");
          return EnvironmentType.none;
        },
      );
}

enum ClipboardListeningWay {
  logs,
  hiddenApi;

  static ClipboardListeningWay parse(String value) {
    return ClipboardListeningWay.values.firstWhere(
      (e) => e.name.toUpperCase() == value.toUpperCase(),
      orElse: () {
        throw Exception("UnSupport ClipboardListeningWay $value");
      },
    );
  }
}

enum PasteResult {
  /// Paste was injected successfully.
  success,

  /// No eligible target window was found to receive the paste action.
  noTargetWindow,

  /// The target process could not be opened for inspection.
  openTargetProcessFailed,

  /// The target process was found, but its integrity level could not be inspected.
  inspectTargetFailed,

  /// The current process integrity level could not be inspected.
  inspectSelfFailed,

  /// The target window is running at a higher integrity level than this app.
  targetIntegrityHigher,

  /// The native side returned a result unknown to this Dart version.
  unknown;

  static PasteResult parse(String value) => PasteResult.values.firstWhere(
        (e) => e.name == value,
        orElse: () {
          debugPrint("PasteResult '$value' unknown");
          return PasteResult.unknown;
        },
      );
}
