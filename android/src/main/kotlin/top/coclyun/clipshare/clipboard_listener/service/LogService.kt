package top.coclyun.clipshare.clipboard_listener.service

import android.os.Build
import android.util.Log
import top.coclyun.clipshare.clipboard_listener.ILogCallback
import top.coclyun.clipshare.clipboard_listener.ILogService
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


open class LogService : ILogService.Stub() {
    private val TAG = "LogService"
    private var process: Process? = null
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

    override fun startReadLogs(callback: ILogCallback, useRoot: Boolean) {
        val fmt = "yyyy-MM-dd HH:mm:ss.SSS"
        val timeStamp = SimpleDateFormat(fmt, Locale.US).format(Date())
        val commands = arrayOf("logcat", "-T", timeStamp, "ClipboardService:E", "*:S")
        Log.d(TAG, "startReadLogs: ${buildCommandsWithSpace(commands)}")

        if (useRoot) {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process!!.outputStream)
            os.writeBytes("${buildCommandsWithSpace(commands)}\n")
            os.flush()
        } else {
            process = Runtime.getRuntime().exec(commands)
        }
        val reader = BufferedReader(InputStreamReader(process!!.inputStream))
        var line: String?
        try{
            while (reader.readLine().also { line = it } != null) {
                callback.onReadLine(line)
            }
        }catch (e:Exception){
            Log.w("LogService","read error in loop: ${e.message}")
            e.printStackTrace()
        }finally {
            reader.close()
        }

    }

    override fun stopReadLogs() {
        Log.d(TAG, "stopReadLogs: ")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process?.inputStream?.close()
            process?.destroyForcibly()
            process=null
        } else {
            process?.inputStream?.close()
            process?.destroy()
            process=null
        }
    }
}