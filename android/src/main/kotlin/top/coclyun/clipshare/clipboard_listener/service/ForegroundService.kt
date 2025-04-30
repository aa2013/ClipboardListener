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
import android.os.Message
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnBinderDeadListener
import rikka.shizuku.Shizuku.OnBinderReceivedListener
import top.coclyun.clipshare.clipboard_listener.ClipboardListener
import top.coclyun.clipshare.clipboard_listener.ClipboardListenerPlugin
import top.coclyun.clipshare.clipboard_listener.ClipboardListeningWay
import top.coclyun.clipshare.clipboard_listener.EnvironmentType
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

    //mHandler用于弱引用和主线程更新UI，避免内存泄漏。
    private var mHandler = MyHandler(this)
    private var plugin: ClipboardListenerPlugin? = null
    private lateinit var windowManager: WindowManager
    private lateinit var mainParams: LayoutParams
    private var view: ViewGroup? = null
    private var useRoot: Boolean = false
    private lateinit var listeningWay: ClipboardListeningWay
    private var listenerThread: Thread? = null

    //region Clipboard Listener
    private val clipboardListenerCallback = object : IOnClipboardChanged.Stub() {
        override fun onChanged(logLine: String?) {
            if (logLine != null && !logLine.contains(plugin!!.config.applicationId)) {
                return
            }
            if (plugin!!.config.ignoreNextCopy) {
                plugin!!.config.ignoreNextCopy = false
            } else {
                mHandler.sendMessage(Message())
            }
        }

    }

    class MyHandler(foregroundService: ForegroundService) : Handler() {
        private val mOuter: WeakReference<ForegroundService> =
            WeakReference<ForegroundService>(foregroundService)

        override fun handleMessage(msg: Message) {
            mOuter.get().let {
                Log.d("listener onChanged", it.toString())
                it?.showFloatFocusView()
            }
        }
    }

    //endregion

    //region float view
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mainParams = LayoutParams()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mainParams.type = LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            mainParams.type = LayoutParams.TYPE_PHONE;
        }
        mainParams.format = PixelFormat.RGBA_8888;
        //不能使用warp_content 否则无法获取焦点
        mainParams.width = 1
        mainParams.height = 1
    }

    private fun showFloatFocusView() {
        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "canDrawOverlays: false")
            return
        }
        Log.d(TAG, "canDrawOverlays: true")
        val layoutInflater = baseContext.getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        view = layoutInflater.inflate(R.layout.float_focus, null) as ViewGroup
        if (view == null) return
        windowManager.addView(view, mainParams)
        val hasFocus = view!!.requestFocus()
        Log.d(TAG, "hasFocus: $hasFocus")
        ClipboardListener.instance.onClipboardChanged()
        removeFloatFocusView()
    }

    private fun removeFloatFocusView() {
        if (view == null) return
        windowManager.removeView(view)
        view = null
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
        plugin = ClipboardListenerPlugin.instance
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
        Log.d(TAG, "wayStr $wayStr")
        try {
            listeningWay = ClipboardListeningWay.valueOf(wayStr)
            Log.d(TAG, "listeningWay $listeningWay")
        } catch (e: Exception) {
            notifyForeground("Error", "Can not mapping listen way for $wayStr")
            throw RuntimeException("Can not mapping listen way for $wayStr")
        }
        if (!useRoot) {
            if (plugin!!.serviceConnection != null) {
                Shizuku.unbindUserService(
                    plugin!!.userServiceArgs!!,
                    plugin!!.serviceConnection!!,
                    true
                )
            }
            if (plugin!!.serviceConnection != null) {
                Shizuku.unbindUserService(
                    plugin!!.userServiceArgs!!,
                    plugin!!.serviceConnection,
                    true
                )
                plugin!!.serviceConnection = null
            }
            listenerService?.stopListening()
            plugin!!.serviceConnection = object : ServiceConnection {

                private var service: IClipboardListenerService? = null

                //在OriginOS系统上有概率shizuku启动会失败，可以重新尝试
                //https://github.com/RikkaApps/Shizuku/issues/451
                override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
                    if (service != null) {
                        service?.stopListening()
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
                        Log.w(TAG, "onServiceConnected ${e.message}")
                    }
                }

                override fun onServiceDisconnected(componentName: ComponentName) {
                    listenerService?.stopListening()
                    listenerService = null
                    notifyForeground(
                        plugin!!.config.shizukuDisconnectedTitle,
                        plugin!!.config.shizukuDisconnectedText
                    )
                }
            }
            Shizuku.bindUserService(plugin!!.userServiceArgs!!, plugin!!.serviceConnection!!)
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
            listenerService?.stopListening()
        } catch (_: Exception) {

        }
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

        val manger = getSystemService(
            NOTIFICATION_SERVICE
        ) as NotificationManager
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
        if (plugin!!.mainActivity == null) {
            return;
        }
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
        val intent = Intent(plugin!!.mainActivity, plugin!!.mainActivity!!::class.java)
        return PendingIntent.getActivity(
            plugin!!.mainActivity,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }
    //endregion
}