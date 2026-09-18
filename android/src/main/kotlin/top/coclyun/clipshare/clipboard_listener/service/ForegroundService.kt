package top.coclyun.clipshare.clipboard_listener.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import top.coclyun.clipshare.clipboard_listener.ClipboardListener
import top.coclyun.clipshare.clipboard_listener.ClipshareClipboardListenerPlugin
import top.coclyun.clipshare.clipboard_listener.ClipboardListeningWay
import top.coclyun.clipshare.clipboard_listener.Config
import top.coclyun.clipshare.clipboard_listener.EnvironmentType
import top.coclyun.clipshare.clipboard_listener.IClipboardListenerService
import top.coclyun.clipshare.clipboard_listener.IOnClipboardChanged
import top.coclyun.clipshare.clipboard_listener.R
import top.coclyun.clipshare.clipboard_listener.copyAssetToExternalPrivateDir
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

//在OriginOS系统上有概率shizuku启动会失败，可以重新尝试
//https://github.com/RikkaApps/Shizuku/issues/451
class ForegroundService : Service() {
    companion object {
        @JvmStatic
        val foregroundServiceNotificationId = 1

        @JvmStatic
        val foregroundServiceNotifyChannelId = "ForegroundService"

        internal const val EXTRA_SESSION_ID = "listenerSessionId"
        private const val BIND_DELAY_MS = 500L
        private var activeService = WeakReference<ForegroundService>(null)
        // 跨服务实例串行释放远端资源，避免旧实例的解绑终止新实例刚建立的连接。
        private val lifecycleExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "clipboard-lifecycle").apply { isDaemon = true }
        }

        /** 停止 owner 的服务；尚未收到启动命令时由 onStartCommand 完成前台化后收口。 */
        internal fun requestStop(owner: ClipshareClipboardListenerPlugin, reason: String) {
            val service = activeService.get()?.takeIf { it.plugin === owner } ?: return
            service.onMain { service.stopRequested(reason) }
        }

        /** Shizuku 失效时仅清理 owner 的 Shizuku 会话。 */
        internal fun shizukuUnavailable(owner: ClipshareClipboardListenerPlugin, reason: String) {
            val service = activeService.get()?.takeIf { it.plugin === owner } ?: return
            val session = owner.listenerLifecycle.current
            service.onMain {
                if (service.resources?.session === session) service.stopShizukuListeningLocally(reason)
            }
        }
    }

    private val TAG = "ForegroundService"
    private val listenerZipFileName = "listener.zip"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var plugin: ClipshareClipboardListenerPlugin? = null
    private lateinit var windowManager: WindowManager
    private lateinit var mainParams: LayoutParams
    private lateinit var view: ViewGroup
    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private var resources: SessionResources? = null
    private var lastStartId = 0
    private var destroyed = false
    private val fallbackConfig = Config()
    private var lastChangedTime: Long = 0
    private var changedMinIntervalTime: Long = 200
    @Volatile
    private var isShowFloatView: Boolean = false

    //region Clipboard Listener
    /** 资源只归属 session，回调不得转而读取其他代次的服务或连接。 */
    private class SessionResources(
        val session: ListenerLifecycle.Session,
        val owner: ClipshareClipboardListenerPlugin,
    ) {
        val useRoot = session.environment == EnvironmentType.root
        val args = owner.listeningServiceArgs
        var service: IClipboardListenerService? = null
        var connection: ServiceConnection? = null
        var bindingIssued = false
        var bindTask: Runnable? = null
        var thread: Thread? = null
    }

    /** 将 action 串行投递到主线程，兼容后台权限检查触发的服务失效回调。 */
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else lifecycleHandler.post { action() }
    }

    //endregion

    //region float view
    override fun onCreate() {
        super.onCreate()
        plugin = ClipshareClipboardListenerPlugin.instance
        createNotify()
        activeService = WeakReference(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            LayoutParams.TYPE_PHONE;
        }
        mainParams = LayoutParams(
            16,
            16,
            type,
            FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.RGBA_8888
        )
        mainParams.flags
        mainParams.gravity = Gravity.START or Gravity.TOP
        mainParams.x = 0
        mainParams.y = 0
        val layoutInflater = baseContext.getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        view = layoutInflater.inflate(R.layout.float_focus, null) as ViewGroup
    }

    private fun showFloatFocusView() {
        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "canDrawOverlays: false")
            return
        }
        Log.d(TAG, "canDrawOverlays: true")
        val now = System.currentTimeMillis()
        val offset = now - lastChangedTime
        if (offset < changedMinIntervalTime) {
            Log.d(TAG, "Time since last change is less than $changedMinIntervalTime ms, actually is $offset ms")
            return
        }
        lastChangedTime = now
        if(!isShowFloatView) {
            try {
                windowManager.addView(view, mainParams)
                isShowFloatView = true
            } catch (e: Exception) {
                Log.w(TAG, "addView failed", e)
                return
            }
        }
        val topPkgName = ActivityChangedService.topPkgName;
        Log.d(TAG, "topPkgName: ${ActivityChangedService.topPkgName}, this:${this}")
        mainHandler.post {
            // 延后一帧执行
            if (ClipboardListener.instance.onClipboardChanged(topPkgName)) {
                ActivityChangedService.topPkgName = null
            } else {
                Log.d(TAG, "fetch clipboard data failed!")
            }
            removeFloatFocusView()
        }
        //第二层保险延后移除,  在之前的代码中延后一帧执行仍然有可能无法移除悬浮窗导致输入法无法弹出等问题
        //虽然有可能是并发的问题，但是还是加上试试看吧
        mainHandler.postDelayed({
            removeFloatFocusView()
        }, 100)
    }

    @Synchronized
    private fun removeFloatFocusView() {
        try {
            if (isShowFloatView) {
                windowManager.removeViewImmediate(view)
            }
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed", e)
        } finally {
            isShowFloatView = false
        }

    }
    //endregion

    /** 判断 Shizuku Binder 是否可用，失效后不再调用远端清理接口。 */
    private fun isShizukuBinderAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /** 仅清理当前 Shizuku 会话；reason 用于日志。 */
    private fun stopShizukuListeningLocally(reason: String) {
        val current = resources ?: return
        if (current.useRoot || destroyed) return
        Log.w(TAG, "stop Shizuku listening locally: $reason")
        releaseSession(current, reason, remoteAvailable = false)
        notifyForeground(
            plugin?.config?.shizukuDisconnectedTitle ?: "Shizuku disconnected",
            plugin?.config?.shizukuDisconnectedText ?: "Shizuku service has been disconnected"
        )
    }

    /** 按请求编号启动监听；过期启动命令也需完成前台化后才能结束服务。 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // 每条 startForegroundService 请求都需确认，不能因重复或取消而提前返回。
        createNotify()
        resources?.takeIf { isCurrent(it) && it.session.state == ListenerLifecycle.State.RUNNING }
            ?.let { notifyRunning(it) }
        val owner = plugin
        if (owner == null) {
            Log.e(TAG, "plugin not initialized")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val session = owner.listenerLifecycle.current
        Log.d(TAG, "onStartCommand startId=$startId, session=${session?.id}, state=${session?.state}")
        if (session == null || !owner.listenerLifecycle.isActive(session)) {
            stopRequested("start request cancelled")
            return START_NOT_STICKY
        }
        if (intent?.getLongExtra(EXTRA_SESSION_ID, 0L) != session.id) return START_NOT_STICKY
        if (resources?.session === session) return START_NOT_STICKY
        resources?.let { releaseSession(it, "configuration changed") }
        val current = SessionResources(session, owner)
        resources = current
        // 资源准备排在上一会话清理之后，远端调用不能阻塞主线程前台化。
        lifecycleExecutor.execute {
            if (!owner.listenerLifecycle.isActive(session)) return@execute
            val copied = try {
                copyAssetToExternalPrivateDir(applicationContext, listenerZipFileName, listenerZipFileName)
            } catch (e: Exception) {
                Log.e(TAG, "copy listener asset session=${session.id} failed", e)
                false
            }
            lifecycleHandler.post {
                if (!isCurrent(current)) return@post
                if (!copied) {
                    notifyForeground("Error", "Can not copy listener file")
                    releaseSession(current, "asset copy failed")
                } else if (current.useRoot) {
                    current.service = ClipboardListenerService(true)
                    startListening(current)
                } else {
                    bindListener(current)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    /** 判断 current 的回调是否仍归属本服务的有效会话。 */
    private fun isCurrent(current: SessionResources): Boolean = !destroyed &&
        resources === current && current.owner.listenerLifecycle.isActive(current.session)

    /** 为 current 建立独立连接；重复 Binder 回调不能停止或重新启动已有监听。 */
    private fun bindListener(current: SessionResources) {
        val connection = object : ServiceConnection {
            /** 将当前会话首次收到的 Binder 交给监听线程。 */
            override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
                if (!isCurrent(current) || current.service != null) {
                    Log.d(TAG, "ignore listener connection session=${current.session.id}")
                    return
                }
                current.service = IClipboardListenerService.Stub.asInterface(binder)
                startListening(current)
            }

            /** 子服务断开不等于 Shizuku 整体不可用，旧连接不能改变新会话状态。 */
            override fun onServiceDisconnected(componentName: ComponentName) {
                if (!isCurrent(current)) return
                Log.w(TAG, "listener disconnected session=${current.session.id}")
                if (!isShizukuBinderAvailable()) {
                    current.owner.handleShizukuUnavailable("listener service disconnected")
                } else {
                    listenerFinished(current, null)
                }
            }
        }
        current.connection = connection
        val task = Runnable {
            if (!isCurrent(current)) return@Runnable
            if (!isShizukuBinderAvailable()) {
                current.owner.handleShizukuUnavailable("bind listener skipped because Shizuku is unavailable")
                return@Runnable
            }
            try {
                current.bindingIssued = true
                Shizuku.bindUserService(requireNotNull(current.args), connection)
                Log.d(TAG, "bind listener session=${current.session.id}")
            } catch (e: Exception) {
                Log.w(TAG, "bind listener session=${current.session.id} failed", e)
                if (!isShizukuBinderAvailable()) {
                    current.owner.handleShizukuUnavailable("bind listener failed: ${e.message}")
                } else {
                    listenerFinished(current, e)
                }
            }
        }
        current.bindTask = task
        lifecycleHandler.postDelayed(task, BIND_DELAY_MS)
    }

    /** 启动 current 的唯一监听线程，变化回调和退出通知均携带原会话身份。 */
    private fun startListening(current: SessionResources) {
        if (!isCurrent(current)) return
        var errorTextPrefix = current.owner.config.errorTextPrefix
        errorTextPrefix = if (errorTextPrefix.isEmpty()) "" else ": "
        val service = current.service
        if (service == null) {
            notifyForeground(
                current.owner.config.errorTitle,
                "${errorTextPrefix}ListenerService is null"
            )
            releaseSession(current, "listener service is null")
            return
        }
        if (!current.owner.listenerLifecycle.markRunning(current.session)) return
        notifyRunning(current)
        val callback = object : IOnClipboardChanged.Stub() {
            /** 停止后排队的旧变化事件不能触发悬浮窗。 */
            override fun onChanged(logLine: String?) {
                mainHandler.post {
                    if (!isCurrent(current)) return@post
                    val config = current.owner.config
                    if (logLine != null && !logLine.contains(config.applicationId)) return@post
                    if (config.ignoreNextCopy) {
                        config.ignoreNextCopy = false
                    } else {
                        showFloatFocusView()
                    }
                }
            }
        }
        val path = File(applicationContext.getExternalFilesDir(null), listenerZipFileName).path
        current.thread = Thread {
            var failure: Exception? = null
            try {
                if (!current.owner.listenerLifecycle.isActive(current.session)) return@Thread
                Log.i(TAG, "listening session=${current.session.id}, useRoot=${current.useRoot}, way=${current.session.way}")
                service.startListening(
                    callback,
                    current.useRoot,
                    path,
                    current.session.way == ClipboardListeningWay.hiddenApi,
                )
            } catch (e: Exception) {
                failure = e
                Log.w(TAG, "listener returned with error session=${current.session.id}", e)
            }
            lifecycleHandler.post { listenerFinished(current, failure) }
        }.apply {
            isDaemon = true
            name = "clipboard-listener-${current.session.id}"
            start()
        }
    }

    /** 使用 current 环境对应的既有通知文案，重复启动不能留下等待通知。 */
    private fun notifyRunning(current: SessionResources) {
        notifyForeground(
            current.owner.config.serviceRunningTitle,
            if (current.useRoot) current.owner.config.rootRunningText else current.owner.config.shizukuRunningText
        )
    }

    /** 仅为当前会话的异常结束通知；failure 为空代表监听调用自行返回。 */
    private fun listenerFinished(current: SessionResources, failure: Exception?) {
        if (!isCurrent(current)) return
        Log.w(TAG, "listener stopped unexpectedly session=${current.session.id}, error=${failure?.javaClass?.simpleName}")
        if (failure != null) {
            var errorTextPrefix = current.owner.config.errorTextPrefix
            errorTextPrefix = if (errorTextPrefix.isEmpty()) "" else ": "
            notifyForeground(
                plugin?.config?.errorTitle ?: "Error",
                "${errorTextPrefix}${failure.message}"
            )
        } else {
            notifyForeground(
                plugin?.config?.stopListeningTitle ?: "Stopped",
                plugin?.config?.stopListeningText ?: "Clipboard listener stopped"
            )
        }
        releaseSession(current, "listener returned")
    }

    /** 撤销 current 并排队清理；remoteAvailable=false 时不访问失效 Binder。 */
    private fun releaseSession(current: SessionResources, reason: String, remoteAvailable: Boolean = true) {
        current.owner.listenerLifecycle.stop(current.session)
        current.bindTask?.let { lifecycleHandler.removeCallbacks(it) }
        current.bindTask = null
        current.thread = null
        if (resources === current) {
            resources = null
            mainHandler.removeCallbacksAndMessages(null)
            removeFloatFocusView()
        }
        Log.i(TAG, "release listener session=${current.session.id}, reason=$reason, bound=${current.bindingIssued}")
        lifecycleExecutor.execute {
            try {
                if (current.useRoot) {
                    current.service?.destroy()
                } else if (remoteAvailable && isShizukuBinderAvailable()) {
                    // 仅解绑实际提交过的连接，不能用当前插件的其他会话连接清理旧资源。
                    if (current.bindingIssued) {
                        current.args?.let { args ->
                            current.connection?.let { connection ->
                                Shizuku.unbindUserService(args, connection, true)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "release listener session=${current.session.id} failed", e)
            } finally {
                current.owner.listenerLifecycle.finish(current.session)
            }
        }
    }

    /** 主动停止时使用已收到的 startId，防止结束框架已经接收的新启动请求。 */
    private fun stopRequested(reason: String) {
        if (destroyed) return
        resources?.let {
            if (it.owner.listenerLifecycle.isActive(it.session)) return
            releaseSession(it, reason)
        }
        val current = plugin?.listenerLifecycle?.current
        if (current != null && plugin?.listenerLifecycle?.isActive(current) == true) return
        current?.let { plugin?.listenerLifecycle?.finish(it) }
        if (lastStartId != 0) stopSelfResult(lastStartId)
    }

    /** 只清理本实例拥有的资源，旧实例销毁不得覆盖后续请求的状态。 */
    override fun onDestroy() {
        destroyed = true
        Log.i(TAG, "ForegroundService onDestroy session=${resources?.session?.id}")
        resources?.let { releaseSession(it, "service destroyed") }
        lifecycleHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
        removeFloatFocusView()
        if (activeService.get() === this) activeService.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    //region notify

    /** 立即以前台通知确认启动，不先执行其他通知或远端操作。 */
    private fun createNotify() {
        // 在 Android 8.0 及以上版本，需要创建通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        // 创建并显示通知
        val notification = buildNotification()

        startForeground(foregroundServiceNotificationId, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name: CharSequence = "ServiceStatus"
            val description = "Notify service running status"
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel = NotificationChannel(foregroundServiceNotifyChannelId, name, importance)
            channel.description = description
            val notificationManager = getSystemService(
                NOTIFICATION_SERVICE
            ) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d("notify", "createNotificationChannel")
        }
    }

    /** 插件尚未初始化时复用 Config 的既有默认文案，保证服务仍可前台化。 */
    private fun buildNotification(): Notification {
        // 创建通知
        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, foregroundServiceNotifyChannelId)
        } else {
            Notification.Builder(this)
        }

        // 设置通知的标题、内容等
        val config = plugin?.config ?: fallbackConfig
        builder.setContentTitle(config.waitingRunningTitle)
            .setSmallIcon(getAppIcon(applicationContext))
            .setOngoing(true)
            .setSound(null)
            .setContentIntent(createPendingIntent())
//            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setContentText(config.waitingRunningText)
        return builder.build()
    }

    private fun notifyForeground(title: String, content: String) {
        val updatedBuilder: NotificationCompat.Builder =
            NotificationCompat.Builder(this, foregroundServiceNotifyChannelId)
                .setSmallIcon(getAppIcon(applicationContext))
                .setContentTitle(title)
                .setOngoing(true)
                .setSound(null)
                .setContentIntent(createPendingIntent())
                .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
                .setContentText(content)
        val manger = getSystemService(
            NOTIFICATION_SERVICE
        ) as NotificationManager
        // 更新通知
        manger.notify(foregroundServiceNotificationId, updatedBuilder.build())
    }

    private fun getAppIcon(context: Context): Int {
        // 获取包管理器
        val packageManager = context.packageManager

        // 获取主应用的包名
        val packageName = context.packageName

        // 通过包名获取应用信息
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)

        // 获取应用的图标资源 ID
        return applicationInfo.icon
    }

    private fun createPendingIntent(): PendingIntent {
        // 前台服务失效收口时 plugin 可能已经被释放，通知点击回主界面不能依赖 plugin 引用。
        val intent = try {
            Intent(applicationContext, ClipshareClipboardListenerPlugin.activityClass)
        } catch (e: Exception) {
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        }
        return PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }
    //endregion
}
