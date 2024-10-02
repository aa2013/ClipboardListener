package top.coclyun.clipshare.clipboard_listener

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class ForegroundService : Service() {
    companion object {
        @JvmStatic
        val foregroundServiceNotificationId = 1

        @JvmStatic
        val foregroundServiceNotifyChannelId = "ForegroundService"

        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
        @JvmStatic
        fun needShizuku(): Boolean {
            return Build.VERSION.SDK_INT > Build.VERSION_CODES.P
        }
    }

    private val TAG = "ForegroundService"

    //mHandler用于弱引用和主线程更新UI，避免内存泄漏。
    private var mHandler = MyHandler(this)
    private var plugin: ClipboardListenerPlugin? = null
    private lateinit var windowManager: WindowManager
    private lateinit var mainParams: LayoutParams
    private var view: ViewGroup? = null

    class MyHandler(foregroundService: ForegroundService) : Handler() {
        private val mOuter: WeakReference<ForegroundService> =
            WeakReference<ForegroundService>(foregroundService)

        override fun handleMessage(msg: Message) {
            mOuter.get().let {
                Log.d("read_logs", it.toString())
                it?.showFloatFocusView()
//                it?.startActivity(ClipboardFocusActivity.getIntent(it))
            }
        }
    }

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        plugin = ClipboardListenerPlugin.instance
        if (plugin != null) {
            createNotify()
            readLogByShizuku()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null;
    }

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
        notifyForeground(plugin!!.config.shizukuNotifyContentText)
    }

    private fun readLogByShizuku() {
        //Android 10以下才需要shizuku
        if (!needShizuku()) {
            return
        }
        val timeStamp: String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val cmdStrArr = arrayOf(
            "logcat",
            "-T",
            timeStamp,
            "ClipboardService:E",
            "*:S"
        )
        val t = Thread {
            try {
                val process = Shizuku.newProcess(cmdStrArr, null, null)
                Log.d("read_logs", "start")
                val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                plugin?.listening = true;
                var line: String?
                while (bufferedReader.readLine().also { line = it } != null) {
                    line?.let { Log.d("read_logs", it) }
                    if (line!!.contains(plugin!!.config.applicationId)) {
                        if (plugin!!.config.ignoreNextCopy) {
                            plugin!!.config.ignoreNextCopy = false
                        } else {
                            line?.let { Log.d("read_logs", "self log") }
                            mHandler.sendMessage(Message())
                        }
                    }
                }
                notifyForeground("日志读取异常停止")
                Log.d("read_logs", "finished")
                plugin?.listening = false;
            } catch (_: RemoteException) {
                stopSelf()
            } catch (e: Exception) {
                plugin?.listening = false
                notifyForeground("Shizuku服务异常停止：" + e.message)
                e.printStackTrace()
                e.message?.let { Log.e("read_logs", it) }
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name: CharSequence = "前台通知"
            val description = "前台通知服务，告知服务状态允许"
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
        builder.setContentTitle(plugin!!.config.shizukuNotifyContentTitle)
            .setSmallIcon(getAppIcon(applicationContext))
            .setOngoing(true)
            .setSound(null)
            .setContentIntent(createPendingIntent())
//            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setContentText(plugin!!.config.shizukuNotifyContentText)
        return builder.build()
    }

    private fun notifyForeground(content: String) {
        val updatedBuilder: NotificationCompat.Builder =
            NotificationCompat.Builder(this, foregroundServiceNotifyChannelId)
                .setSmallIcon(getAppIcon(applicationContext))
                .setContentTitle(plugin!!.config.shizukuNotifyContentTitle)
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
        val intent = Intent(plugin!!.mainActivity, plugin!!.mainActivity!!::class.java)
        return PendingIntent.getActivity(
            plugin!!.mainActivity,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }
}