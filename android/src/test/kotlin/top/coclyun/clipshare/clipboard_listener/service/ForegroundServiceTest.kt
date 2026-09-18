package top.coclyun.clipshare.clipboard_listener.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import rikka.shizuku.Shizuku
import top.coclyun.clipshare.clipboard_listener.ClipboardListeningWay
import top.coclyun.clipshare.clipboard_listener.ClipshareClipboardListenerPlugin
import top.coclyun.clipshare.clipboard_listener.EnvironmentType
import top.coclyun.clipshare.clipboard_listener.IClipboardListenerService
import top.coclyun.clipshare.clipboard_listener.IOnClipboardChanged
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 使用 Android 服务与主线程调度验证竞态，Shizuku 连接由测试控制。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ForegroundServiceTest {
    private companion object {
        const val WAIT_SECONDS = 10L
        val BIND_DELAY = Duration.ofMillis(500)
    }

    private lateinit var owner: ClipshareClipboardListenerPlugin
    private lateinit var shizuku: MockedStatic<Shizuku>
    private var controller: ServiceController<ForegroundService>? = null
    private val connections = mutableListOf<ServiceConnection>()
    private val remotes = mutableListOf<BlockingListener>()

    /** 注册可控 Binder，不启动设备上的 Shizuku 或真正的监听进程。 */
    @Before
    fun setUp() {
        owner = ClipshareClipboardListenerPlugin().apply {
            context = RuntimeEnvironment.getApplication()
            config.applicationId = context.packageName
            currentEnv = EnvironmentType.shizuku
            listeningServiceArgs = Shizuku.UserServiceArgs(ComponentName(context, ClipboardListenerService::class.java))
        }
        ClipshareClipboardListenerPlugin.instance = owner
        ClipshareClipboardListenerPlugin.activityClass = Activity::class.java
        shizuku = Mockito.mockStatic(Shizuku::class.java)
        shizuku.`when`<Boolean> { Shizuku.pingBinder() }.thenReturn(true)
        shizuku.`when`<Unit> {
            Shizuku.bindUserService(Mockito.any(Shizuku.UserServiceArgs::class.java), Mockito.any(ServiceConnection::class.java))
        }.thenAnswer {
            connections.add(it.getArgument(1))
            null
        }
    }

    /** 释放阻塞读取及生命周期队列，防止测试之间遗留后台任务。 */
    @After
    fun tearDown() {
        owner.listenerLifecycle.stop()
        controller?.destroy()
        remotes.forEach { it.finish.countDown() }
        remotes.forEach { it.thread?.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }
        drainLifecycle()
        shizuku.close()
        ClipshareClipboardListenerPlugin.instance = null
    }

    /** 服务创建阶段必须前台化，即使插件尚未初始化也保留原默认文案。 */
    @Test
    fun foregroundNotificationExistsBeforeStartCommandWithoutPlugin() {
        ClipshareClipboardListenerPlugin.instance = null
        val service = createService()
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull(notification)
        assertEquals("Waiting to Running", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Waiting to Running Service", notification.extras.getString(Notification.EXTRA_TEXT))
        service.onStartCommand(null, 0, 1)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    /** 尚未进入 onStartCommand 的启动被撤销时，也要先前台化再停止。 */
    @Test
    fun stopBeforeStartCommandDoesNotBind() {
        val session = owner.listenerLifecycle.start(EnvironmentType.shizuku, ClipboardListeningWay.logs)
        owner.listenerLifecycle.stop()
        val service = createService()
        service.onStartCommand(intent(session), 0, 1)
        drainLifecycle()
        shadowOf(Looper.getMainLooper()).idleFor(BIND_DELAY)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(connections.isEmpty())
        assertFalse(owner.listening)
    }

    /** 取消已排队的 500ms 绑定任务，不能在服务销毁后继续绑定。 */
    @Test
    fun stopCancelsDelayedBind() {
        startSession()
        drainLifecycle()
        owner.listenerLifecycle.stop()
        ForegroundService.requestStop(owner, "test stop")
        controller!!.destroy()
        controller = null
        shadowOf(Looper.getMainLooper()).idleFor(BIND_DELAY)
        assertTrue(connections.isEmpty())
        assertEquals(0, notifications().size())
    }

    /** 同一个连接重复回调只启动一次，通知仍使用现有运行文案。 */
    @Test
    fun duplicateConnectionDoesNotStopRunningListener() {
        startSession()
        val connection = awaitConnection()
        val remote = connect(connection)
        connection.onServiceConnected(component(), remote)
        assertEquals(1, remote.starts)
        assertEquals(0, remote.stops)
        assertTrue(owner.listening)
        assertEquals(owner.config.shizukuRunningText, notificationText())
    }

    /** 已发起绑定的旧连接迟到时不能启动线程或改变新会话。 */
    @Test
    fun oldConnectionCannotStartAfterReplacement() {
        startSession()
        val oldConnection = awaitConnection()
        startSession(ClipboardListeningWay.hiddenApi, 2)
        val newConnection = awaitConnection()
        val oldRemote = BlockingListener().also { remotes.add(it) }
        oldConnection.onServiceConnected(component(), oldRemote)
        assertEquals(0, oldRemote.starts)
        connect(newConnection)
        assertTrue(owner.listening)
    }

    /** 旧 Binder 调用忽略中断并晚于新会话返回时，不能覆盖新会话或发送停止通知。 */
    @Test
    fun oldThreadCannotOverwriteNewRunningNotification() {
        startSession()
        val old = connect(awaitConnection())
        startSession(ClipboardListeningWay.hiddenApi, 2)
        connect(awaitConnection())
        old.finish.countDown()
        assertTrue(old.returned.await(WAIT_SECONDS, TimeUnit.SECONDS))
        awaitListenerThread(old)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(owner.listening)
        assertEquals(owner.config.shizukuRunningText, notificationText())
    }

    /** 真正 EOF 仍应通知用户，标题与正文必须逐字保留现有配置。 */
    @Test
    fun unexpectedReturnUsesExistingStoppedNotification() {
        startSession()
        val remote = connect(awaitConnection())
        remote.finish.countDown()
        assertTrue(remote.returned.await(WAIT_SECONDS, TimeUnit.SECONDS))
        awaitListenerThread(remote)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(owner.listening)
        val notification = notifications().getNotification(ForegroundService.foregroundServiceNotificationId)
        assertEquals(owner.config.stopListeningTitle, notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(owner.config.stopListeningText, notificationText())
    }

    /** 异常分支保留原错误标题与格式，不被通用停止通知立即覆盖。 */
    @Test
    fun exceptionKeepsExistingErrorNotification() {
        startSession()
        val connection = awaitConnection()
        val remote = BlockingListener().apply { failure = IllegalStateException("test failure") }
        remotes.add(remote)
        connection.onServiceConnected(component(), remote)
        assertTrue(remote.returned.await(WAIT_SECONDS, TimeUnit.SECONDS))
        awaitListenerThread(remote)
        shadowOf(Looper.getMainLooper()).idle()
        val notification = notifications().getNotification(ForegroundService.foregroundServiceNotificationId)
        assertEquals(owner.config.errorTitle, notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("test failure", notificationText())
        assertFalse(owner.listening)
    }

    /** Shizuku 死亡事件不得撤销 Root 会话或将环境改为空。 */
    @Test
    fun shizukuFailureDoesNotInvalidateRootSession() {
        val session = owner.listenerLifecycle.start(EnvironmentType.root, ClipboardListeningWay.logs)
        owner.currentEnv = EnvironmentType.root
        owner.listenerLifecycle.markRunning(session)
        owner.handleShizukuUnavailable("test binder dead")
        assertTrue(owner.listening)
        assertEquals(EnvironmentType.root, owner.currentEnv)
    }

    /** 创建并注册测试服务，仅执行 Android onCreate。 */
    private fun createService(): ForegroundService {
        val created = Robolectric.buildService(ForegroundService::class.java).create()
        controller = created
        return created.get()
    }

    /** 为 way 发出 startId 对应的新请求，复用同一个前台服务。 */
    private fun startSession(way: ClipboardListeningWay = ClipboardListeningWay.logs, startId: Int = 1) {
        val session = owner.listenerLifecycle.start(EnvironmentType.shizuku, way)
        val service = controller?.get() ?: createService()
        service.onStartCommand(intent(session), 0, startId)
    }

    /** 构造携带 session 编号的真实服务启动参数。 */
    private fun intent(session: ListenerLifecycle.Session) = Intent(owner.context, ForegroundService::class.java)
        .putExtra(ForegroundService.EXTRA_SESSION_ID, session.id)
        .putExtra("useRoot", session.environment == EnvironmentType.root)
        .putExtra("way", session.way.name)

    /** 等待后台准备完成并推进绑定延迟，返回当前实际提交的连接。 */
    private fun awaitConnection(): ServiceConnection {
        drainLifecycle()
        shadowOf(Looper.getMainLooper()).idleFor(BIND_DELAY)
        return connections.last()
    }

    /** 向 connection 交付可阻塞的 Binder，等待真实监听线程进入远端调用。 */
    private fun connect(connection: ServiceConnection): BlockingListener {
        val remote = BlockingListener().also { remotes.add(it) }
        connection.onServiceConnected(component(), remote)
        assertTrue(remote.started.await(WAIT_SECONDS, TimeUnit.SECONDS))
        return remote
    }

    /** 等待远端调用外层线程完成投递，避免仅等待 Binder 方法返回造成测试竞态。 */
    private fun awaitListenerThread(remote: BlockingListener) {
        remote.thread?.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertFalse(remote.thread?.isAlive == true)
    }

    /** 排空串行资源队列及主线程上的即时任务，不自动推进延迟绑定。 */
    private fun drainLifecycle() {
        val field = ForegroundService::class.java.getDeclaredField("lifecycleExecutor").apply { isAccessible = true }
        (field.get(null) as ExecutorService).submit {}.get(WAIT_SECONDS, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** 返回插件的监听组件名。 */
    private fun component() = ComponentName(owner.context, ClipboardListenerService::class.java)

    /** 获取实际 Android 通知管理器的测试视图。 */
    private fun notifications() = shadowOf(owner.context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    /** 读取当前前台通知正文，用于验证旧线程不能覆盖文案。 */
    private fun notificationText(): String? = notifications()
        .getNotification(ForegroundService.foregroundServiceNotificationId)?.extras?.getString(Notification.EXTRA_TEXT)

    /** 模拟不能靠 Thread.interrupt 中止的 Binder 调用，直到测试显式允许返回。 */
    private class BlockingListener : IClipboardListenerService.Stub() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val returned = CountDownLatch(1)
        @Volatile var starts = 0
        @Volatile var stops = 0
        @Volatile var thread: Thread? = null
        var failure: Exception? = null

        /** 捕获监听线程并模拟长时间阻塞的远端监听。 */
        override fun startListening(callback: IOnClipboardChanged, useRoot: Boolean, filePath: String, useHiddenApi: Boolean) {
            thread = Thread.currentThread()
            starts++
            started.countDown()
            try {
                failure?.let { throw it }
                while (true) {
                    try {
                        finish.await()
                        break
                    } catch (_: InterruptedException) {
                        // Binder 调用不会因宿主线程的中断标记立即结束。
                    }
                }
            } finally {
                returned.countDown()
            }
        }

        /** 记录主动停止，供重复连接测试检查。 */
        override fun stopListening() { stops++; finish.countDown() }

        /** 释放模拟远端调用。 */
        override fun destroy() { finish.countDown() }

        /** 兼容 AIDL 的退出入口。 */
        override fun exit() { destroy() }
    }
}
