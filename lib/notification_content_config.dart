class NotificationContentConfig {
  final String errorTitle;
  final String errorTextPrefix;
  final String stopListeningTitle;
  final String stopListeningText;
  final String serviceRunningTitle;
  final String shizukuRunningText;
  final String rootRunningText;
  final String shizukuDisconnectedTitle;
  final String shizukuDisconnectedText;
  final String waitingRunningTitle;
  final String waitingRunningText;

  const NotificationContentConfig({
    required this.errorTitle,
    required this.errorTextPrefix,
    required this.stopListeningTitle,
    required this.stopListeningText,
    required this.serviceRunningTitle,
    required this.shizukuRunningText,
    required this.rootRunningText,
    required this.shizukuDisconnectedTitle,
    required this.shizukuDisconnectedText,
    required this.waitingRunningTitle,
    required this.waitingRunningText,
  });

  Map<String, String> toJson() {
    return {
      "errorTitle": errorTitle,
      "errorTextPrefix": errorTextPrefix,
      "stopListeningTitle": stopListeningTitle,
      "stopListeningText": stopListeningText,
      "serviceRunningTitle": serviceRunningTitle,
      "shizukuRunningText": shizukuRunningText,
      "rootRunningText": rootRunningText,
      "shizukuDisconnectedTitle": shizukuDisconnectedTitle,
      "shizukuDisconnectedText": shizukuDisconnectedText,
      "waitingRunningTitle": waitingRunningTitle,
      "waitingRunningText": waitingRunningText,
    };
  }
}
