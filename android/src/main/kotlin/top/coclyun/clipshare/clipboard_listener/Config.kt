package top.coclyun.clipshare.clipboard_listener

class Config {
    var ignoreNextCopy = false
    lateinit var applicationId: String
    var errorTitle: String = "Error"
    var errorTextPrefix: String = ""
    var stopListeningTitle: String = "Warning"
    var stopListeningText: String = "Clipboard listening stopped"
    var serviceRunningTitle: String = "Service is running"
    var shizukuRunningText: String = "Shizuku mode is active"
    var rootRunningText: String = "Root mode is active"
    var shizukuDisconnectedTitle: String = "Error"
    var shizukuDisconnectedText: String = "Shizuku service has been disconnected"
    var waitingRunningTitle: String = "Waiting to Running"
    var waitingRunningText: String = "Waiting to Running Service"
}