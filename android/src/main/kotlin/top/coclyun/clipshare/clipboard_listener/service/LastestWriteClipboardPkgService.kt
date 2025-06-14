package top.coclyun.clipshare.clipboard_listener.service

import android.util.Log
import top.coclyun.clipshare.clipboard_listener.ILatestWriteClipboardPkgService
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

class LatestWriteClipboardPkgService : ILatestWriteClipboardPkgService.Stub() {
    private val TAG = "LatestWriteClipboardPkgService"
    private var process: Process? = null
    private var isRootMode: Boolean = false
    private var running: Boolean = false
    private lateinit var scriptPath: String
    private var os: DataOutputStream? = null
    private var reader: BufferedReader? = null

    override fun destroy() {
        Log.i(TAG, "destroy")
        stopProcess()
        if (!isRootMode) {
            System.exit(0)
        }
    }

    override fun exit() {
        destroy()
    }

    override fun start(scriptPath: String, useRoot: Boolean) {
        if (running) return
        this.scriptPath = scriptPath
        isRootMode = useRoot
        try {
            val processBuilder: ProcessBuilder
            if (useRoot) {
                processBuilder = ProcessBuilder(arrayOf("su").toList())
                processBuilder.redirectErrorStream(true)
                process = processBuilder.start()
            } else {
                processBuilder = ProcessBuilder(arrayOf("sh").toList())
                processBuilder.redirectErrorStream(true)
                process = processBuilder.start()
            }
            os = DataOutputStream(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}. ${e.stackTraceToString()}")
            e.printStackTrace()
        } finally {
            running = false
        }
    }

    @Synchronized
    override fun getLatestWriteClipbaordAppSource(): String? {
        if (process == null) {
            Log.w(TAG, "process is null")
            return null
        }
        if (os == null) {
            Log.w(TAG, "os is null")
            return null
        }
        if (reader == null) {
            Log.w(TAG, "reader is null")
            return null
        }
        var line: String?
        val pkgPrefix = "pkg:"
        val totalPrefix = "total:"
        try {
            os!!.writeBytes("sh $scriptPath\n")
            os!!.flush()
            while (reader!!.readLine().also { line = it } != null) {
                Log.d(TAG, line!!)
                val data = line.split(",")
                val pkgName = data[0].substring(pkgPrefix.length);
                val totalMs = data[1].substring(totalPrefix.length);
                return "$pkgName,$totalMs"
            }
        } catch (_: Exception) {
            stopProcess()
        }
        return null
    }

    private fun stopProcess() {
        try {
            process?.destroy()
            os?.close()
            reader?.close()
            process = null
            os = null
            reader = null
        } catch (_: Exception) {

        }
    }
}