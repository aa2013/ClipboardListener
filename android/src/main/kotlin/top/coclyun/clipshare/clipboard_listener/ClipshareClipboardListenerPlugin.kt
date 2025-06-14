package top.coclyun.clipshare.clipboard_listener

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterFragmentActivity.ACTIVITY_SERVICE
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import top.coclyun.clipshare.clipboard_listener.service.ActivityChangedService
import top.coclyun.clipshare.clipboard_listener.service.ClipboardListenerService
import top.coclyun.clipshare.clipboard_listener.service.ForegroundService
import top.coclyun.clipshare.clipboard_listener.service.LatestWriteClipboardPkgService
import top.coclyun.clipshare.clipboard_listener.utils.getAppIconAsBase64
import top.coclyun.clipshare.clipboard_listener.utils.getAppNameByPackageName
import java.io.DataOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val CHANNEL_NAME = "top.coclyun.clipshare/clipboard_listener"
const val ON_CLIPBOARD_CHANGED = "onClipboardChanged"
const val ON_PERMISSION_STATUS_CHANGED = "onPermissionStatusChanged"
const val START_LISTENING = "startListening"
const val STOP_LISTENING = "stopListening"
const val GET_LATEST_WRITE_CLIPBOARD_SOURCE = "getLatestWriteClipboardSource"
const val GET_SHIZUKUVERSION = "getShizukuVersion"
const val CHECK_IS_RUNNING = "checkIsRunning"
const val CHECK_PERMISSION = "checkPermission"
const val REQUEST_PERMISSION = "requestPermission"
const val CHECK_ACCESSIBILITY = "checkAccessibility"
const val REQUEST_ACCESSIBILITY = "requestAccessibility"
const val COPY = "copy"

/** ClipboardListenerPlugin */
class ClipshareClipboardListenerPlugin : FlutterPlugin, MethodCallHandler,
    Shizuku.OnRequestPermissionResultListener,
    ClipboardListener.ClipboardObserver, ActivityAware {
    private val TAG = "ClipboardListenerPlugin"
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private val requestShizukuCode = 5001
    var currentEnv: EnvironmentType? = null
    var config: Config = Config()
    var mainActivity: Activity? = null
    var listening: Boolean = false
    var listeningServiceArgs: UserServiceArgs? = null
    var listeningServiceConn: ServiceConnection? = null
    var clipboardSourceArgs: UserServiceArgs? = null
    var clipboardSourceServiceConn: ServiceConnection? = null
    var latestWriteClipboardPkgService: ILatestWriteClipboardPkgService? = null
    val latestWriteClipboardPkgShellFileName = "readLastWriteClipboardPkg.sh"
    private val clipboardSourceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        @JvmStatic
        @SuppressLint("StaticFieldLeak")
        var instance: ClipshareClipboardListenerPlugin? = null
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, CHANNEL_NAME)
        context = flutterPluginBinding.applicationContext
        config.applicationId = context.applicationInfo.packageName
        listeningServiceArgs = UserServiceArgs(
            ComponentName(
                config.applicationId,
                ClipboardListenerService::class.java.name
            )
        ).daemon(false).processNameSuffix("listening-service")
        Log.d(TAG, "applicationId: ${config.applicationId}")
        Shizuku.addRequestPermissionResultListener(this);
        currentEnv = getCurrentEnvironment()
        instance = this
        ClipboardListener.init(this, context)
        ClipboardListener.instance.addObserver(this)
        channel.setMethodCallHandler(this)
        Log.d(TAG, "currentEnv $currentEnv")
        initClipboardSourceShizukuService()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        instance = null
        Shizuku.removeRequestPermissionResultListener(this);
        ClipboardListener.instance.removeObserver(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            START_LISTENING -> onStartListeningCalled(call, result)

            STOP_LISTENING -> onStopListeningCalled(call, result)

            GET_SHIZUKUVERSION -> onGetShizukuVersionCalled(call, result)

            CHECK_IS_RUNNING -> onCheckIsRunningCalled(call, result)

            CHECK_PERMISSION -> onCheckPermissionCalled(call, result)

            REQUEST_PERMISSION -> onRequestPermissionCalled(call, result)

            COPY -> onCopyCalled(call, result)

            GET_LATEST_WRITE_CLIPBOARD_SOURCE -> onGetLatestWriteClipboardSourceCalled(
                call,
                result
            )

            CHECK_ACCESSIBILITY -> checkAccessibility(call, result)

            REQUEST_ACCESSIBILITY -> requestAccessibility(call, result)
        }
    }

    //region Flutter Method Channel Events

    private fun onStartListeningCalled(call: MethodCall, result: Result) {
        val envStr = call.argument<String>("env")
        val wayStr = call.argument<String>("way")
        var env: EnvironmentType? = null
        var way: ClipboardListeningWay? = null

        //region 工作环境参数
        if (envStr != null) {
            try {
                env = EnvironmentType.valueOf(envStr)
                if (env == EnvironmentType.androidPre10) {
                    result.success(true)
                    return
                }
            } catch (_: Exception) {

            }
        }
        //endregion

        //region 监听方式参数
        if (wayStr == null) {
            result.success(false)
            return
        }
        try {
            way = ClipboardListeningWay.valueOf(wayStr)
        } catch (_: Exception) {
            result.success(false)
            return
        }
        //endregion

        //region 通知参数赋值
        config = Config().apply {
            applicationId = config.applicationId
            ignoreNextCopy = config.ignoreNextCopy
            //region 通知参数赋值
            call.argument<String>("errorTitle")?.let {
                errorTitle = it
            }
            call.argument<String>("errorTextPrefix")?.let {
                errorTextPrefix = it
            }

            call.argument<String>("stopListeningTitle")?.let {
                stopListeningTitle = it
            }

            call.argument<String>("stopListeningText")?.let {
                stopListeningText = it
            }

            call.argument<String>("serviceRunningTitle")?.let {
                serviceRunningTitle = it
            }

            call.argument<String>("shizukuRunningText")?.let {
                shizukuRunningText = it
            }

            call.argument<String>("rootRunningText")?.let {
                rootRunningText = it
            }

            call.argument<String>("shizukuDisconnectedTitle")?.let {
                shizukuDisconnectedTitle = it
            }

            call.argument<String>("shizukuDisconnectedText")?.let {
                shizukuDisconnectedText = it
            }

            call.argument<String>("waitingRunningTitle")?.let {
                waitingRunningTitle = it
            }

            call.argument<String>("waitingRunningText")?.let {
                waitingRunningText = it
            }
            //endregion
        }
        val cfgClz = config::class.java
        for (field in cfgClz.declaredFields) {
            field.isAccessible = true
            val value = call.argument<String>(field.name)
            value?.let { field.set(config, value) }
        }
        //endregion

        val res = startListeningClipboard(env, way)
        startClipboardSourceService(env)
        result.success(res)
    }

    private fun onStopListeningCalled(call: MethodCall, result: Result) {
        try {
            stopListening()
            result.success(true)
        } catch (e: Exception) {
            e.printStackTrace()
            result.success(false)
        }
    }

    private fun onGetShizukuVersionCalled(call: MethodCall, result: Result) {
        result.success(Shizuku.getVersion())
    }

    private fun onCheckIsRunningCalled(call: MethodCall, result: Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            result.success(true)
        }
        result.success(listening)
    }

    private fun onCheckPermissionCalled(call: MethodCall, result: Result) {
        val envName = call.argument<String>("env")
        try {
            val env = EnvironmentType.valueOf(envName!!)
            val hasPermission = when (env) {
                EnvironmentType.shizuku -> checkShizukuPermission()
                EnvironmentType.root -> checkRootPermission()
                EnvironmentType.androidPre10 -> checkAndroidPre10()
            }
            result.success(hasPermission)
        } catch (e: Exception) {
            result.success(false)
        }
    }

    private fun onRequestPermissionCalled(call: MethodCall, result: Result) {
        val envName = call.argument<String>("env")
        val env = EnvironmentType.valueOf(envName!!)
        when (env) {
            EnvironmentType.shizuku -> {
                try {
                    Shizuku.requestPermission(requestShizukuCode)
                } catch (e: Exception) {
                    onPermissionStatusChanged(EnvironmentType.shizuku, false)
                }
            }

            EnvironmentType.root -> {
                val granted = checkRootPermission()
                onPermissionStatusChanged(EnvironmentType.root, granted)
            }

            else -> {}
        }
        result.success(null)
    }

    private fun onCopyCalled(call: MethodCall, result: Result) {
        try {
            config.ignoreNextCopy = true
            val type = call.argument<String>("type")
            val content = call.argument<String>("content")
            // 获取剪贴板管理器
            val clipboardManager = ContextCompat.getSystemService(
                context,
                ClipboardManager::class.java
            ) as ClipboardManager
            val contentType = ClipboardContentType.parse(type!!)
            Log.d(TAG, "onMethodCall: Copy $contentType")
            when (contentType) {
                ClipboardContentType.Text -> {
                    // 创建一个剪贴板数据
                    val clipData = ClipData.newPlainText("text", content)
                    // 将数据放入剪贴板
                    clipboardManager.setPrimaryClip(clipData)
                }

                ClipboardContentType.Image -> {
                    val uri =
                        Uri.parse("content://${context.packageName}.FileProvider/$content")
                    val clipData =
                        ClipData.newUri(
                            context.contentResolver,
                            "image",
                            uri
                        )
                    // 将数据放入剪贴板
                    clipboardManager.setPrimaryClip(clipData)
                }
            }
            result.success(true)

        } catch (e: Exception) {
            config.ignoreNextCopy = false;
            result.success(false)
        }
    }

    private fun onGetLatestWriteClipboardSourceCalled(call: MethodCall, result: Result) {
        clipboardSourceScope.launch {
            try {
                Log.d(
                    TAG,
                    "$GET_LATEST_WRITE_CLIPBOARD_SOURCE latestWriteClipboardPkgService: $latestWriteClipboardPkgService"
                )

                val source = withContext(Dispatchers.IO) {
                    latestWriteClipboardPkgService?.latestWriteClipbaordAppSource
                } ?: run {
                    result.success(null)
                    return@launch
                }

                // 处理source数据
                val (pkg, totalMsStr) = source.split(",")
                val totalMs = totalMsStr.toLongOrNull() ?: 0L

                // 返回结果
                result.success(getClipboardSource(pkg, totalMs))

            } catch (e: Exception) {
                Log.e(TAG, "Error getting clipboard source", e)
                result.success(null)
            }
        }
    }

    private fun checkAccessibility(call: MethodCall, result: Result) {
        result.success(isAccessibilityServiceEnabled(context, ActivityChangedService::class.java))
    }

    fun isAccessibilityServiceEnabled(
        context: Context,
        service: Class<out AccessibilityService?>
    ): Boolean {
        val prefString = Settings.Secure.getString(
            context.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return prefString != null && prefString.contains(context.getPackageName() + "/" + service.getName())
    }

    private fun requestAccessibility(call: MethodCall, result: Result) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        mainActivity?.startActivity(intent)
    }

    //endregion

    private fun getClipboardSource(pkg: String, timeMs: Long?): Map<String, String?> {
        var time: String? = null
        if (timeMs != null) {
            // 获取当前时间并减去毫秒数
            val currentTime = Date()
            val adjustedTime = Date(currentTime.time - timeMs)

            // 定义格式化器
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            time = formatter.format(adjustedTime)
        }

        return mapOf(
            "id" to pkg,
            "name" to getAppNameByPackageName(context, pkg),
            "time" to time,
            "iconB64" to getAppIconAsBase64(context, pkg)
        )
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        // Do stuff based on the result and the request code
        when (requestCode) {
            requestShizukuCode -> {
                onPermissionStatusChanged(EnvironmentType.shizuku, granted)
            }
        }
    }

    override fun onClipboardChanged(
        type: ClipboardContentType,
        content: String,
        pkg: String?
    ) {
        Log.d(TAG, "onClipboardChanged $pkg")
        if (config.ignoreNextCopy) return
        var source: Map<String, String?>? = null
        if (pkg != null) {
            source = getClipboardSource(pkg, null)
        }
        channel.invokeMethod(
            ON_CLIPBOARD_CHANGED,
            mapOf(
                "content" to content,
                "type" to type.name,
                "source" to source
            )
        )
    }

    private fun startListeningClipboard(
        env: EnvironmentType? = null,
        way: ClipboardListeningWay
    ): Boolean {
        if (listening) return false
        if (env == EnvironmentType.root) {
            if (!checkRootPermission()) {
                return false
            }
        } else if (env == EnvironmentType.shizuku) {
            if (!checkShizukuPermission()) {
                return false
            }
        }
        if (currentEnv == EnvironmentType.androidPre10) return true
        return startListening(env, way)
    }

    private fun startListening(env: EnvironmentType?, way: ClipboardListeningWay): Boolean {
        try {
            val running = isServiceRunning(
                context,
                ForegroundService::class.java
            )
            Log.d(TAG, "service is running: $running")
            val serviceIntent = Intent(context, ForegroundService::class.java)
            serviceIntent.putExtra(
                "useRoot",
                (env ?: currentEnv) == EnvironmentType.root
            )
            serviceIntent.putExtra("way", way.name)
            if (running) {
                context.stopService(serviceIntent)
            }
            context.startService(serviceIntent)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun stopListening() {
        val serviceIntent = Intent(context, ForegroundService::class.java)
        listening = false
        stopClipboardSourceService()
        context.stopService(serviceIntent)
    }

    private fun initClipboardSourceShizukuService() {
        if (!copyAssetToExternalPrivateDir(
                context,
                latestWriteClipboardPkgShellFileName,
                latestWriteClipboardPkgShellFileName
            )
        ) {
            Log.w(TAG, "Can not copy file $latestWriteClipboardPkgShellFileName")
        }
        clipboardSourceArgs = UserServiceArgs(
            ComponentName(
                config.applicationId,
                LatestWriteClipboardPkgService::class.java.name
            )
        ).daemon(false).processNameSuffix("clipboard-service-service")
        clipboardSourceServiceConn = object : ServiceConnection {
            private var service: ILatestWriteClipboardPkgService? = null
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (service != null) {
                    service?.destroy()
                    return
                }
                Log.d(TAG, "onServiceConnected ${name.className}")
                latestWriteClipboardPkgService =
                    ILatestWriteClipboardPkgService.Stub.asInterface(binder)
                service = latestWriteClipboardPkgService
                try {
                    val path =
                        File(
                            context.getExternalFilesDir(null),
                            latestWriteClipboardPkgShellFileName
                        ).path
                    latestWriteClipboardPkgService?.start(
                        path,
                        false
                    )
                } catch (e: Exception) {
                    latestWriteClipboardPkgService = null
                    service = null
                    e.printStackTrace()
                    Log.d(TAG, "onServiceConnected Error: ${e.message}")
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                try {
                    latestWriteClipboardPkgService?.destroy()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                Log.w(TAG, "onServiceDisconnected ${name.className}")
            }

        }
    }

    private fun startClipboardSourceService(env: EnvironmentType?): Boolean {
        if (env == null || env == EnvironmentType.androidPre10) {
            return false;
        }
        if (latestWriteClipboardPkgService != null) return false
        try {
            val isRootMode = env == EnvironmentType.root
            if (isRootMode) {
                latestWriteClipboardPkgService = LatestWriteClipboardPkgService()
                latestWriteClipboardPkgService?.start(
                    latestWriteClipboardPkgShellFileName,
                    true
                )
            } else {
                if (clipboardSourceArgs != null && clipboardSourceServiceConn != null) {
                    Shizuku.bindUserService(clipboardSourceArgs!!, clipboardSourceServiceConn!!)
                } else {
                    return false
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "startClipboardSourceService failed! Error: ${e.message}")
        }
        return false
    }

    private fun stopClipboardSourceService() {
        try {
            latestWriteClipboardPkgService?.destroy()
            latestWriteClipboardPkgService = null
            if (listeningServiceArgs != null && clipboardSourceServiceConn != null) {
                Shizuku.unbindUserService(
                    listeningServiceArgs!!,
                    clipboardSourceServiceConn!!,
                    true
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun onPermissionStatusChanged(env: EnvironmentType, isGranted: Boolean) {
        channel.invokeMethod(
            ON_PERMISSION_STATUS_CHANGED,
            mapOf("env" to env.name, "isGranted" to isGranted)
        )
        if (isGranted) {
            currentEnv = env
        }
    }

    /**
     * 判断服务是否运行
     * @param context 上下文
     * @param serviceClass 服务类
     */
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val activityManager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager

        // 获取运行中的服务列表
        for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                // 如果找到匹配的服务类名，表示服务在运行
                return true
            }
        }
        // 未找到匹配的服务类名，表示服务未在运行
        return false
    }
    //region check permissions

    private fun getCurrentEnvironment(): EnvironmentType? {
        if (checkShizukuPermission()) {
            return EnvironmentType.shizuku
        }
        if (checkRootPermission()) {
            return EnvironmentType.root
        }
        if (checkAndroidPre10()) {
            return EnvironmentType.androidPre10
        }
        return null
    }

    private fun checkShizukuPermission(): Boolean {
        if (Shizuku.isPreV11()) {
            Log.d(TAG, "Pre-v11 is unsupported")
            Toast.makeText(this.context, "Pre-v11 is unsupported", Toast.LENGTH_LONG).show()
            return false
        }
        try {
            return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                // Granted
                true
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                // Users choose "Deny and don't ask again"
                Log.d(TAG, "shouldShowRequestPermissionRationale")
                Toast.makeText(
                    this.context,
                    "shouldShowRequestPermissionRationale",
                    Toast.LENGTH_LONG
                ).show()
                false
            } else {
                // Request the permission
                Log.d(TAG, "else")
                false
            }
        } catch (e: Exception) {
            return false;
        }
    }

    private fun checkRootPermission(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun checkAndroidPre10(): Boolean {
        val current = Build.VERSION.SDK_INT
        val versionQ = Build.VERSION_CODES.Q
        return current < versionQ
    }
    //endregion

    //region ActivityAware
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        mainActivity = binding.activity
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        mainActivity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        mainActivity = null
        clipboardSourceScope.cancel()
    }


    override fun onDetachedFromActivity() {
        mainActivity = null
        stopListening()
        clipboardSourceScope.cancel()
    }
    //endregion
}
