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
import top.coclyun.clipshare.clipboard_listener.ILogCallback
import top.coclyun.clipshare.clipboard_listener.ILogService
import top.coclyun.clipshare.clipboard_listener.R
import java.lang.ref.WeakReference


class ForegroundService : Service() {
    companion object {
        @JvmStatic
        val foregroundServiceNotificationId = 1

        @JvmStatic
        val foregroundServiceNotifyChannelId = "ForegroundService"

        @JvmStatic
        private var logService: ILogService? = null
    }

    private val TAG = "ForegroundService"

    //mHandler用于弱引用和主线程更新UI，避免内存泄漏。
    private var mHandler = MyHandler(this)
    private var plugin: ClipboardListenerPlugin? = null
    private lateinit var windowManager: WindowManager
    private lateinit var mainParams: LayoutParams
    private var view: ViewGroup? = null
    private var useRoot: Boolean = false

    //region read logs
    private val readLogCallback = object : ILogCallback.Stub() {
        override fun onReadLine(line: String?) {
            if (line!!.contains(plugin!!.config.applicationId)) {
                Log.d(TAG, "onReadLine: $line")
                if (plugin!!.config.ignoreNextCopy) {
                    plugin!!.config.ignoreNextCopy = false
                } else {
                    mHandler.sendMessage(Message())
                }
            }
        }
    }

    class MyHandler(foregroundService: ForegroundService) : Handler() {
        private val mOuter: WeakReference<ForegroundService> =
            WeakReference<ForegroundService>(foregroundService)

        override fun handleMessage(msg: Message) {
            mOuter.get().let {
                Log.d("read_logs", it.toString())
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
        logService?.stopReadLogs()
        logService = null
        useRoot = intent?.getBooleanExtra("useRoot", false) ?: false
        plugin = ClipboardListenerPlugin.instance
        Shizuku.addBinderReceivedListenerSticky(onBinderReceivedListener)
        Shizuku.addBinderDeadListener(onBinderDeadListener)
        createNotify()
        if (plugin == null) throw Exception("plugin instance is not init")
        if (!useRoot) {
            if (plugin!!.serviceConnection != null) {
                Shizuku.unbindUserService(
                    plugin!!.userServiceArgs!!,
                    plugin!!.serviceConnection!!,
                    true
                )
            }
            if (plugin!!.serviceConnection == null) {
                plugin!!.serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
                        Log.d(TAG, "onServiceConnected ${componentName.className}")
                        plugin!!.listening = true
                        notifyForeground(
                            plugin!!.config.notifyContentTitle,
                            plugin!!.config.notifyContentTextByShizuku
                        )
                        logService = ILogService.Stub.asInterface(binder)
                        startReadLogs()
                    }

                    override fun onServiceDisconnected(componentName: ComponentName) {
                        logService?.stopReadLogs()
                        logService = null
                        notifyForeground("Service disconnected", "LogService disconnected")
                    }
                }
            }

            Shizuku.bindUserService(plugin!!.userServiceArgs!!, plugin!!.serviceConnection!!)
        } else {
            logService = LogService()
            notifyForeground(
                plugin!!.config.notifyContentTitle,
                plugin!!.config.notifyContentTextByRoot
            )
            plugin!!.listening = true
            startReadLogs()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null;
    }

    private fun startReadLogs() {
        if (logService == null) {
            notifyForeground("Error", "LogService is null")
            return
        }
        Log.d(TAG, "ready to read logs: start")
        val t = Thread {
            try {
                Log.d(TAG, "startReadLogs, useRoot $useRoot")
                logService!!.startReadLogs(readLogCallback, useRoot)
            } catch (e: Exception) {
                e.printStackTrace()
                Log.d(TAG, "startReadLogs: error ${e.message}")
                notifyForeground("Error", "Clipboard listening stopped abnormally: ${e.message}")
            }
            plugin?.listening = false
            Log.d(TAG, "startReadLogs: end")
            notifyForeground("Warning", "Clipboard listening end")
        }
        t.isDaemon = true
        t.start()
    }

    override fun onDestroy() {
//        super.onDestroy()
        Log.d(TAG, "ForegroundService onDestroy $logService")
        logService?.stopReadLogs()
        logService = null
        plugin!!.listening = false
        Shizuku.unbindUserService(plugin!!.userServiceArgs!!, plugin!!.serviceConnection, true)
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
        builder.setContentTitle("Waiting to Running")
            .setSmallIcon(getAppIcon(applicationContext))
            .setOngoing(true)
            .setSound(null)
            .setContentIntent(createPendingIntent())
//            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setContentText("Waiting to Running Service")
        return builder.build()
    }

    private fun notifyForeground(title: String, content: String) {
        val updatedBuilder: NotificationCompat.Builder =
            NotificationCompat.Builder(this, foregroundServiceNotifyChannelId)
                .setSmallIcon(getAppIcon(applicationContext))
                .setContentTitle(plugin!!.config.notifyContentTitle)
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