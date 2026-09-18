package top.coclyun.clipshare.clipboard_listener.service

import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 管理阻塞读取的监听子进程，每轮运行独立持有进程及取消状态。
 * launch 创建命令进程；report 仅接收退出元数据，不接收剪贴板内容。
 */
internal class ListenerProcessRunner(
    private val launch: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
    private val report: (Long, String, Int?, Throwable?) -> Unit,
) {
    private companion object {
        val nextId = AtomicLong()
        const val EXIT_CODE_WAIT_MS = 200L
        const val EXIT_CODE_POLL_MS = 10L
        const val GRACEFUL_STOP_WAIT_MS = 500L
        const val EXIT_COMMAND = "exit\n"
    }

    /** 单轮运行的可见取消状态，旧读取线程只能清理此处保存的进程。 */
    private class Run(val id: Long, val cooperativeExit: Boolean) {
        @Volatile var cancelled = false
        var process: Process? = null
        var disposed = false
    }

    private val lock = Any()
    private var active: Run? = null
    private var closed = false

    /**
     * 执行 commands 并逐行交给 onLine；回调返回 false 时结束当前读取。
     * useRoot 为 true 时由 Root shell 同时管理子进程和停止信号，保留命令退出码。
     * gracefulExit 用于支持 exit 标准输入协议的 hiddenApi 进程。
     */
    fun run(commands: List<String>, useRoot: Boolean, gracefulExit: Boolean = false, onLine: (String) -> Boolean) {
        val current: Run
        val previous: Run?
        synchronized(lock) {
            if (closed) return
            previous = active
            previous?.cancelled = true
            current = Run(nextId.incrementAndGet(), useRoot || gracefulExit)
            active = current
        }
        previous?.let { dispose(it) }
        var reason = "eof"
        var failure: Throwable? = null
        try {
            val process = synchronized(lock) {
                if (current.cancelled || closed || active !== current) return
                val created = launch(if (useRoot) listOf("su") else commands)
                current.process = created
                if (useRoot) {
                    val output = DataOutputStream(created.outputStream)
                    val command = commands.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
                    // su 可能由守护进程代为执行，销毁 su 客户端并不保证子进程退出。
                    // 先保存输入描述符，避免后台任务的标准输入被 shell 重定向到 /dev/null。
                    // 控制读端在显式停止或宿主管道关闭时终止本 shell 持有的子进程。
                    output.writeBytes(
                        "exec 3<&0; $command & listener_pid=$!; " +
                            "(read -r listener_stop; kill \"\$listener_pid\" 2>/dev/null) <&3 & control_pid=$!; " +
                            "wait \"\$listener_pid\"; listener_status=$?; " +
                            "kill \"\$control_pid\" 2>/dev/null; wait \"\$control_pid\" 2>/dev/null; " +
                            "exit \"\$listener_status\"\n"
                    )
                    output.flush()
                }
                created
            }
            report(current.id, "started", null, null)
            val reader = process.inputStream.bufferedReader()
            while (!current.cancelled) {
                val line = reader.readLine() ?: break
                if (current.cancelled) break
                if (!onLine(line)) {
                    reason = "protocol end"
                    break
                }
            }
        } catch (e: Exception) {
            failure = e
            reason = "read failed"
        } finally {
            val process = current.process
            if (current.cancelled) {
                reason = "requested stop"
                failure = null
            }
            // 在主动销毁之前读取退出码，避免将清理产生的终止误判为系统杀进程。
            val exitCode = process?.let { readExitCode(it, !current.cancelled && reason == "eof") }
            dispose(current)
            synchronized(lock) {
                if (active === current) active = null
            }
            report(current.id, reason, exitCode, failure)
        }
    }

    /** 取消当前运行；permanent 为 true 时拒绝已经排队但尚未开始的启动调用。 */
    fun stop(permanent: Boolean = false) {
        val current = synchronized(lock) {
            if (permanent) closed = true
            active?.also {
                it.cancelled = true
                active = null
            }
        }
        current?.let { dispose(it) }
    }

    /** 查询 process 的自然退出码；waitForExit 仅在 EOF 后短暂等待系统回收状态。 */
    private fun readExitCode(process: Process, waitForExit: Boolean): Int? {
        val deadline = System.nanoTime() + EXIT_CODE_WAIT_MS * 1_000_000L
        while (true) {
            try {
                return process.exitValue()
            } catch (_: IllegalThreadStateException) {
                if (!waitForExit || System.nanoTime() >= deadline) return null
                try {
                    Thread.sleep(EXIT_CODE_POLL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    /** 优先使用 current 的停止协议，避免只杀 su 客户端而遗留 Root 子进程。 */
    private fun dispose(current: Run) {
        val process = synchronized(current) {
            val value = current.process ?: return
            if (current.disposed) return
            current.disposed = true
            value
        }
        if (current.cooperativeExit && readExitCode(process, false) == null) {
            runCatching {
                process.outputStream.write(EXIT_COMMAND.toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }
            val deadline = System.nanoTime() + GRACEFUL_STOP_WAIT_MS * 1_000_000L
            while (readExitCode(process, false) == null && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(EXIT_CODE_POLL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        runCatching { process.destroy() }
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }
}
