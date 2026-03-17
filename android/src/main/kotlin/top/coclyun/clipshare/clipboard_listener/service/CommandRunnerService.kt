package top.coclyun.clipshare.clipboard_listener.service

import android.util.Log
import top.coclyun.clipshare.clipboard_listener.ICommandRunnerService
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

class CommandRunnerService : ICommandRunnerService.Stub() {
    private val TAG = "CommandRunnerService"

    override fun destroy() {
        Log.i(TAG, "destroy")
    }

    override fun exit() {
        destroy()
    }

    override fun run(command: String, useRoot: Boolean): String {
        var process: Process? = null
        val result = StringBuilder()

        try {
            process = if (useRoot) {
                ProcessBuilder("su")
            } else {
                ProcessBuilder("sh")
            }.redirectErrorStream(true).start()

            val os = DataOutputStream(process.outputStream)
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            // 写入命令
            os.writeBytes(command + "\n")
            os.writeBytes("exit\n")
            os.flush()

            // 读取结果
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { Log.e(TAG, it) }
                result.append(line).append("\n")
            }

            process.waitFor()

        } catch (e: Exception) {
            Log.e(TAG, "run failed", e)
        } finally {
            process?.destroy()
        }

        return result.toString()
    }
}