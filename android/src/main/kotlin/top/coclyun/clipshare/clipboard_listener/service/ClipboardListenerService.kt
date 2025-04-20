package top.coclyun.clipshare.clipboard_listener.service

import android.os.Build
import android.util.Log
import top.coclyun.clipshare.clipboard_listener.EventEnum
import top.coclyun.clipshare.clipboard_listener.IClipboardListenerService
import top.coclyun.clipshare.clipboard_listener.IOnClipboardChanged
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


open class ClipboardListenerService : IClipboardListenerService.Stub() {
    private val TAG = "ClipboardListenerService"
    private var process: Process? = null
    private var stopped: Boolean = false
    private fun buildCommandsWithSpace(commands: Array<String>): String {
        val res = Array(commands.size) { "" }
        for (i in commands.indices) {
            if (commands[i].contains(" ")) {
                res[i] = "'" + commands[i] + "'" // �������
            } else {
                res[i] = commands[i]
            }
        }
        return res.joinToString(" ")
    }

    override fun startListening(
        callback: IOnClipboardChanged,
        useRoot: Boolean,
        filePath: String,
        useHiddenApi: Boolean
    ) {
        var success = false;
        if (useHiddenApi) {
            success = tryRunProcess(callback, useRoot, filePath)
        } else {
            success = tryReadLogs(callback, useRoot)
        }
        if (!success) {
            Log.d(TAG, "error, useHiddenApi = $useHiddenApi")
        }
    }

    private fun tryRunProcess(
        callback: IOnClipboardChanged,
        useRoot: Boolean,
        filePath: String
    ): Boolean {
        val dirPath = File(filePath).parent
        val commands = arrayOf(
            "app_process",
            "-Djava.class.path=$filePath",
            dirPath,
            "top.coclyun.clipshare.clipboard_listener.ClipboardListener"
        )
        Log.d(TAG, "try run process: ${buildCommandsWithSpace(commands)}")
        return tryRunAndWait(callback, useRoot, commands, false)
    }

    private fun tryReadLogs(callback: IOnClipboardChanged, useRoot: Boolean): Boolean {
        val fmt = "yyyy-MM-dd HH:mm:ss.SSS"
        val timeStamp = SimpleDateFormat(fmt, Locale.US).format(Date())
        val commands = arrayOf("logcat", "-T", timeStamp, "ClipboardService:E", "*:S")
        Log.d(TAG, "try read logs: ${buildCommandsWithSpace(commands)}")
        return tryRunAndWait(callback, useRoot, commands, true)
    }

    private fun tryRunAndWait(
        callback: IOnClipboardChanged,
        useRoot: Boolean,
        commands: Array<String>,
        useLog: Boolean
    ): Boolean {
        if (stopped) {
            return true
        }
        var reader: BufferedReader? = null
        var error = false
        try {
            val processBuilder: ProcessBuilder
            if (useRoot) {
                processBuilder = ProcessBuilder(arrayOf("su").toList())
                processBuilder.redirectErrorStream(true)
                process = processBuilder.start()
                val os = DataOutputStream(process!!.outputStream)
                os.writeBytes("${buildCommandsWithSpace(commands)}\n")
                os.flush()
            } else {
                processBuilder = ProcessBuilder(commands.toList())
                processBuilder.redirectErrorStream(true)
                process = processBuilder.start()
            }
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, line!!)
                if (useLog) {
                    callback.onChanged(line!!)
                } else {
                    if (line!!.startsWith(EventEnum.onChanged.name + ":")) {
                        callback.onChanged(null)
                    }
                    if (line!!.startsWith(EventEnum.fatal.name + ":")) {
                        error = true
                        break
                    }
                    if (line!!.startsWith(EventEnum.eof.name + ":")) {
                        error = false
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "read error in loop: ${e.message}. ${e.stackTraceToString()}")
            e.printStackTrace()
            error = true;
        } finally {
            reader?.close()
        }
        return error
    }

    override fun stopListening() {
        Log.d(TAG, "stopListen: ")
        stopped = true
        process?.inputStream?.close()
        process?.outputStream?.close()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process?.destroyForcibly()
        } else {
            process?.destroy()
        }
        process = null
    }
}