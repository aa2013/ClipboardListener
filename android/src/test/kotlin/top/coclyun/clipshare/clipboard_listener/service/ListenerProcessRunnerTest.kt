package top.coclyun.clipshare.clipboard_listener.service

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 使用可控进程模拟管道 EOF、停止与迟到退出，不依赖真实 su 或 logcat。 */
class ListenerProcessRunnerTest {
    private companion object {
        const val TEST_TIMEOUT_SECONDS = 3L
    }

    /** 自然 EOF 时必须保留原退出码，不能记录成清理产生的退出。 */
    @Test
    fun eofReportsExitCodeBeforeCleanup() {
        val process = FakeProcess(ByteArrayInputStream(ByteArray(0)), 137)
        val reports = mutableListOf<Pair<String, Int?>>()
        val runner = ListenerProcessRunner({ process }) { _, reason, code, _ -> reports.add(reason to code) }
        runner.run(listOf("logcat"), false) { true }
        assertEquals("eof" to 137, reports.last())
        assertTrue(process.destroyed)
    }

    /** stop 之后的新进程能继续读取，旧读取线程结束不能清除新进程引用。 */
    @Test
    fun stoppedReaderCannotClearRestartedProcess() {
        val firstInput = BlockingInput()
        val secondInput = BlockingInput()
        val first = FakeProcess(firstInput)
        val second = FakeProcess(secondInput)
        val processes = ArrayDeque(listOf(first, second))
        val reports = CopyOnWriteArrayList<String>()
        val runner = ListenerProcessRunner({ processes.removeFirst() }) { _, reason, _, _ -> reports.add(reason) }
        val oldThread = thread { runner.run(listOf("logcat"), false) { true } }
        assertTrue(firstInput.entered.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        runner.stop()
        val newThread = thread { runner.run(listOf("logcat"), false) { true } }
        assertTrue(secondInput.entered.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        firstInput.release.countDown()
        oldThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
        assertFalse(oldThread.isAlive)
        assertFalse(second.destroyed)
        runner.stop()
        assertTrue(second.destroyed)
        secondInput.release.countDown()
        newThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
        assertFalse(newThread.isAlive)
        assertEquals(2, reports.count { it == "requested stop" })
    }

    /** 永久销毁必须拒绝尚未执行的迟到启动。 */
    @Test
    fun destroyedRunnerRejectsLateStart() {
        var launches = 0
        val runner = ListenerProcessRunner({ launches++; FakeProcess(ByteArrayInputStream(ByteArray(0)), 0) }) { _, _, _, _ -> }
        runner.stop(permanent = true)
        runner.run(listOf("logcat"), false) { true }
        assertEquals(0, launches)
    }

    /** hiddenApi 停止时先发送既有 exit 协议，管道关闭不能代替退出命令。 */
    @Test
    fun hiddenApiReceivesExitBeforeProcessIsDestroyed() {
        val input = BlockingInput()
        val process = FakeProcess(input)
        val runner = ListenerProcessRunner({ process }) { _, _, _, _ -> }
        val worker = thread { runner.run(listOf("app_process"), false, gracefulExit = true) { true } }
        assertTrue(input.entered.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        runner.stop()
        assertEquals("exit\n", process.output.toString("UTF-8"))
        assertTrue(process.destroyed)
        input.release.countDown()
        worker.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
        assertFalse(worker.isAlive)
    }

    /** Root 启动的命令参数必须完整保留，并由 shell 处理停止及退出码。 */
    @Test
    fun rootCommandTracksChildAndQuotesArguments() {
        val process = FakeProcess(ByteArrayInputStream(ByteArray(0)), 0)
        val runner = ListenerProcessRunner({ command ->
            assertEquals(listOf("su"), command)
            process
        }) { _, _, _, _ -> }
        runner.run(listOf("app_process", "a b", "a'b"), true) { true }
        val script = process.output.toString("UTF-8")
        assertTrue(script.startsWith("exec 3<&0; 'app_process' 'a b' 'a'\\''b' & listener_pid=$!;"))
        assertTrue(script.contains("(read -r listener_stop; kill \"\$listener_pid\" 2>/dev/null) <&3 &"))
        assertTrue(script.endsWith("exit \"\$listener_status\"\n"))
    }

    /** 输入管道只由测试释放，模拟进程销毁后读取线程仍晚到的情况。 */
    private class BlockingInput : InputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        /** 标记已开始读取并等待测试允许 EOF。 */
        override fun read(): Int {
            entered.countDown()
            check(release.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            return -1
        }
    }

    /** input 为可控输出管道，code 非空表示进程已自然退出。 */
    private class FakeProcess(private val input: InputStream, private val code: Int? = null) : Process() {
        val output = ByteArrayOutputStream()
        @Volatile var destroyed = false
        override fun getOutputStream() = output
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = exitValue()
        override fun exitValue(): Int = code ?: if (destroyed) 143 else throw IllegalThreadStateException()
        override fun destroy() { destroyed = true }
    }
}
