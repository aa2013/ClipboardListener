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
import android.os.Message
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
import rikka.shizuku.Shizuku.OnBinderDeadListener
import rikka.shizuku.Shizuku.OnBinderReceivedListener
import top.coclyun.clipshare.clipboard_listener.ClipboardListener
import top.coclyun.clipshare.clipboard_listener.ClipshareClipboardListenerPlugin
import top.coclyun.clipshare.clipboard_listener.ClipboardListeningWay
import top.coclyun.clipshare.clipboard_listener.IClipboardListenerService
import top.coclyun.clipshare.clipboard_listener.IOnClipboardChanged
import top.coclyun.clipshare.clipboard_listener.R
import top.coclyun.clipshare.clipboard_listener.copyAssetToExternalPrivateDir
import java.io.File
import java.lang.ref.WeakReference

//在OriginOS系统上有概率shizuku启动会失败，可以重新尝试
//https://github.com/RikkaApps/Shizuku/issues/451
class ForegroundService : Service() {
    companion object {
        @JvmStatic
        val foregroundServiceNotificationId = 1

        @JvmStatic
        val foregroundServiceNotifyChannelId = "ForegroundService"

        @JvmStatic
        private var listenerService: IClipboardListenerService? = null

    }

    private val TAG = "ForegroundService"
    private val listenerZipFileName = "listener.zip"

    private val mainHandler = MyHandler(this)
//    private val mainHandler = Handler(Looper.getMainLooper())
    private var plugin: ClipshareClipboardListenerPlugin? = null
    private lateinit var windowManager: WindowManager
    private lateinit var mainParams: LayoutParams
    private lateinit var view: ViewGroup
    private var useRoot: Boolean = false
    private lateinit var listeningWay: ClipboardListeningWay
    private var listenerThread: Thread? = null
    private var lastChangedTime: Long = 0
    private var changedMinIntervalTime: Long = 200
    @Volatile
    private var isShowFloatView: Boolean = false
    private val viewLock = Any()

    //region Clipboard Listener
    private val clipboardListenerCallback = object : IOnClipboardChanged.Stub() {
        override fun onChanged(logLine: String?) {
            if (logLine != null && !logLine.contains(plugin!!.config.applicationId)) {
                return
            }
            if (plugin!!.config.ignoreNextCopy) {
                plugin!!.config.ignoreNextCopy = false
            } else {
                mainHandler.sendMessage(Message())
            }
        }

    }

    class MyHandler(foregroundService: ForegroundService) : Handler() {
        private val mOuter: WeakReference<ForegroundService> = WeakReference<ForegroundService>(foregroundService)

        override fun handleMessage(msg: Message) {
            mOuter.get().let {
                Log.d("listener onChanged", it.toString())
                it?.let {
                    synchronized(it.viewLock) {
                        it.showFloatFocusView()
                    }
                }
            }
        }
    }

    //endregion

    //region float view
    override fun onCreate() {
        super.onCreate()
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

    //region Shizuku

    private val onBinderReceivedListener = OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binderReceived")
    }
    private val onBinderDeadListener = OnBinderDeadListener {
        Log.d(TAG, "Shizuku binderDead")
    }
    //endregion

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        listenerService?.stopListening()
        listenerService = null
        useRoot = intent?.getBooleanExtra("useRoot", false) ?: false
        plugin = ClipshareClipboardListenerPlugin.instance
        if (plugin == null) {
            Log.e(TAG, "plugin not initialized")
            stopSelf()
            return START_NOT_STICKY
        }
        Shizuku.addBinderReceivedListenerSticky(onBinderReceivedListener)
        Shizuku.addBinderDeadListener(onBinderDeadListener)
        createNotify()
        if (!copyAssetToExternalPrivateDir(
                applicationContext,
                listenerZipFileName,
                listenerZipFileName
            )
        ) {
            notifyForeground("Error", "Can not copy listener file")
            throw RuntimeException("Can not copy listener file")
        }
        if (plugin == null) throw Exception("plugin instance is not init")
        val wayStr = intent?.getStringExtra("way")
        if (wayStr == null) {
            notifyForeground("Error", "Listening way is null")
            throw RuntimeException("Listening way is null")
        }
        try {
            listeningWay = ClipboardListeningWay.valueOf(wayStr)
            Log.d(TAG, "listeningWay $listeningWay")
        } catch (e: Exception) {
            notifyForeground("Error", "Can not mapping listen way for $wayStr")
            throw RuntimeException("Can not mapping listen way for $wayStr")
        }
        if (!useRoot) {
            if (plugin!!.listeningServiceConn != null) {
                Shizuku.unbindUserService(
                    plugin!!.listeningServiceArgs!!,
                    plugin!!.listeningServiceConn,
                    true
                )
                plugin!!.listeningServiceConn = null
            }
            plugin!!.listeningServiceConn = object : ServiceConnection {

                private var service: IClipboardListenerService? = null

                //在OriginOS系统上有概率shizuku启动会失败，可以重新尝试
                //https://github.com/RikkaApps/Shizuku/issues/451
                override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
                    if (service != null) {
                        try {
                            service?.stopListening()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        return
                    }
                    Log.d(TAG, "onServiceConnected ${componentName.className}")
                    plugin!!.listening = true
                    notifyForeground(
                        plugin!!.config.serviceRunningTitle,
                        plugin!!.config.shizukuRunningText
                    )
                    listenerService = IClipboardListenerService.Stub.asInterface(binder)
                    service = listenerService
                    try {
                        startListening()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        plugin!!.listening = false
                        listenerService = null
                        Log.w(TAG, "onServiceConnected Error: ${e.message}")
                    }
                }

                override fun onServiceDisconnected(componentName: ComponentName) {
                    try {
                        listenerService?.stopListening()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        listenerService = null
                    }
                    notifyForeground(
                        plugin!!.config.shizukuDisconnectedTitle,
                        plugin!!.config.shizukuDisconnectedText
                    )
                }
            }
            Handler().postDelayed({
                Shizuku.bindUserService(
                    plugin!!.listeningServiceArgs!!,
                    plugin!!.listeningServiceConn!!
                )
            }, 500)
        } else {
            listenerService = ClipboardListenerService()
            notifyForeground(
                plugin!!.config.serviceRunningTitle,
                plugin!!.config.rootRunningText
            )
            plugin!!.listening = true
            startListening()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null;
    }

    private fun startListening() {
        var errorTextPrefix = plugin!!.config.errorTextPrefix
        errorTextPrefix = if (errorTextPrefix.isEmpty()) "" else ": "
        if (listenerService == null) {
            notifyForeground(
                plugin!!.config.errorTitle,
                "${errorTextPrefix}ListenerService is null"
            )
            return
        }
        Log.d(TAG, "listening start")
        listenerThread?.interrupt()
        listenerThread = Thread {
            try {
                Log.d(TAG, "listening, useRoot $useRoot, listeningWay $listeningWay")
                val path =
                    File(applicationContext.getExternalFilesDir(null), listenerZipFileName).path
                listenerService!!.startListening(
                    clipboardListenerCallback,
                    useRoot,
                    path,
                    listeningWay == ClipboardListeningWay.hiddenApi,
                )
            } catch (e: Exception) {
                e.printStackTrace()
                Log.i(TAG, "startListening: error ${e.message} listening ${!plugin!!.listening}")
                notifyForeground(
                    plugin!!.config.errorTitle,
                    "${errorTextPrefix}${e.message}"
                )
            }
            plugin?.listening = false
            Log.i(TAG, "startListening: stopped")
            notifyForeground(plugin!!.config.stopListeningTitle, plugin!!.config.stopListeningText)
        }
        listenerThread?.isDaemon = true
        listenerThread?.start()
    }

    override fun onDestroy() {
        Log.i(TAG, "ForegroundService onDestroy $listenerService")
        plugin?.listening = false
        val t = listenerThread
        listenerThread = null
        t?.interrupt()
        try {
            listenerService?.exit()
        } catch (_: Exception) {

        }
        try {
            plugin?.listeningServiceArgs?.let { args ->
                plugin?.listeningServiceConn?.let { conn ->
                    Shizuku.unbindUserService(args, conn, true)
                }
            }
            plugin?.listeningServiceConn = null
        } catch (e: Exception) {
            Log.w(TAG, "unbind listening service failed", e)
        }
        Shizuku.removeBinderReceivedListener(onBinderReceivedListener)
        Shizuku.removeBinderDeadListener(onBinderDeadListener)
        stopForeground(STOP_FOREGROUND_REMOVE)
        listenerService = null
        super.onDestroy()
    }

    //region notify

    private fun createNotify() {
        // 在 Android 8.0 及以上版本，需要创建通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        // 创建并显示通知
        val notification = buildNotification()

        val manger = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manger.notify(foregroundServiceNotificationId, notification)
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

    private fun buildNotification(): Notification {
        // 创建通知
        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, foregroundServiceNotifyChannelId)
        } else {
            Notification.Builder(this)
        }

        // 设置通知的标题、内容等
        builder.setContentTitle(plugin!!.config.waitingRunningTitle)
            .setSmallIcon(getAppIcon(applicationContext))
            .setOngoing(true)
            .setSound(null)
            .setContentIntent(createPendingIntent())
//            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setContentText(plugin!!.config.waitingRunningText)
        return builder.build()
    }

    private fun notifyForeground(title: String, content: String) {
        val updatedBuilder: NotificationCompat.Builder =
            NotificationCompat.Builder(this, foregroundServiceNotifyChannelId)
                .setSmallIcon(getAppIcon(applicationContext))
                .setContentTitle(plugin!!.config.serviceRunningTitle)
                .setOngoing(true)
                .setSound(null)
                .setContentIntent(createPendingIntent())
                .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
                .setContentText(content)
                .setContentTitle(title)
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
        val intent = Intent(plugin!!.context, ClipshareClipboardListenerPlugin.activityClass)
        return PendingIntent.getActivity(
            plugin!!.context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }
    //endregion
}
