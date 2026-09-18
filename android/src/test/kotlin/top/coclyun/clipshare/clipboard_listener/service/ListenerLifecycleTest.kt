package top.coclyun.clipshare.clipboard_listener.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.coclyun.clipshare.clipboard_listener.ClipboardListeningWay
import top.coclyun.clipshare.clipboard_listener.EnvironmentType

/** 验证会话交错时的状态归属，不依赖 Android 调度时序。 */
class ListenerLifecycleTest {
    /** 同配置启动必须合并，绑定完成前不能报告正在运行。 */
    @Test
    fun duplicateStartsReusePendingAndRunningSession() {
        val lifecycle = ListenerLifecycle()
        val first = lifecycle.start(EnvironmentType.shizuku, ClipboardListeningWay.logs)
        assertSame(first, lifecycle.start(EnvironmentType.shizuku, ClipboardListeningWay.logs))
        assertFalse(lifecycle.running)
        assertTrue(lifecycle.markRunning(first))
        assertSame(first, lifecycle.start(EnvironmentType.shizuku, ClipboardListeningWay.logs))
        assertTrue(lifecycle.running)
        assertFalse(lifecycle.markRunning(first))
    }

    /** 启动后立即停止，迟到的连接不得将状态改为运行。 */
    @Test
    fun stopBeforeConnectionRejectsLateCallback() {
        val lifecycle = ListenerLifecycle()
        val session = lifecycle.start(EnvironmentType.shizuku, ClipboardListeningWay.logs)
        lifecycle.stop()
        assertFalse(lifecycle.isActive(session))
        assertFalse(lifecycle.markRunning(session))
        lifecycle.finish(session)
        assertNull(lifecycle.current)
    }

    /** 旧会话退出不能清除切换后的 Root 会话。 */
    @Test
    fun oldCompletionCannotOverwriteNewSession() {
        val lifecycle = ListenerLifecycle()
        val old = lifecycle.start(EnvironmentType.shizuku, ClipboardListeningWay.logs)
        lifecycle.markRunning(old)
        val current = lifecycle.start(EnvironmentType.root, ClipboardListeningWay.hiddenApi)
        assertNotSame(old, current)
        assertFalse(lifecycle.isActive(old))
        assertTrue(lifecycle.markRunning(current))
        lifecycle.stop(old)
        lifecycle.finish(old)
        assertSame(current, lifecycle.current)
        assertTrue(lifecycle.running)
    }

    /** 同配置在停止后必须创建新会话，不能复用已撤销的绑定任务。 */
    @Test
    fun restartAfterStopCreatesNewSession() {
        val lifecycle = ListenerLifecycle()
        val old = lifecycle.start(EnvironmentType.root, ClipboardListeningWay.logs)
        lifecycle.stop()
        val current = lifecycle.start(EnvironmentType.root, ClipboardListeningWay.logs)
        lifecycle.finish(old)
        assertNotSame(old, current)
        assertTrue(lifecycle.isActive(current))
        assertFalse(lifecycle.markRunning(old))
    }
}
