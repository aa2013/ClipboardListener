package top.coclyun.clipshare.clipboard_listener.service

import android.util.Log
import top.coclyun.clipshare.clipboard_listener.EventEnum
import top.coclyun.clipshare.clipboard_listener.IClipboardListenerService
import top.coclyun.clipshare.clipboard_listener.IOnClipboardChanged
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess


open class ClipboardListenerService @JvmOverloads constructor(
    private val rootInstance: Boolean = false,
) : IClipboardListenerService.Stub() {
    private val TAG = "ClipboardListenerService"
    @Volatile
    private var isRootMode = rootInstance
    private val runner = ListenerProcessRunner { id, reason, exitCode, failure ->
        val message = "listener process session=$id, hostPid=${android.os.Process.myPid()}, " +
            "reason=$reason, exitCode=$exitCode"
        if (failure == null) {
            Log.i(TAG, message)
        } else {
            Log.w(TAG, message, failure)
        }
    }

    override fun startListening(
        callback: IOnClipboardChanged,
        useRoot: Boolean,
        filePath: String,
        useHiddenApi: Boolean
    ) {
        isRootMode = rootInstance || useRoot
        val commands = if (useHiddenApi) {
            listOf(
                "app_process",
                "-Djava.class.path=$filePath",
                File(filePath).parent!!,
                "top.coclyun.clipshare.clipboard_listener.ClipboardListener",
                android.os.Process.myPid().toString()
            )
        } else {
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            listOf("logcat", "-T", timeStamp, "ClipboardService:E", "*:S")
        }
        Log.i(TAG, "start listener process useRoot=$useRoot, useHiddenApi=$useHiddenApi")
        runner.run(commands, useRoot, gracefulExit = useHiddenApi) { line ->
            if (!useHiddenApi) {
                callback.onChanged(line)
                true
            } else {
                when {
                    line.startsWith(EventEnum.onChanged.name + ":") -> {
                        callback.onChanged(null)
                        true
                    }
                    line.startsWith(EventEnum.fatal.name + ":") -> {
                        Log.w(TAG, "listener process reported fatal event")
                        false
                    }
                    line.startsWith(EventEnum.eof.name + ":") -> false
                    else -> true
                }
            }
        }
    }

    override fun stopListening() {
        Log.i(TAG, "stopListening requested")
        runner.stop()
    }

    override fun destroy() {
        Log.i(TAG, "destroy listener process")
        runner.stop(permanent = true)
        if (!isRootMode) exitProcess(0)
    }

    override fun exit() {
        destroy()
    }
}