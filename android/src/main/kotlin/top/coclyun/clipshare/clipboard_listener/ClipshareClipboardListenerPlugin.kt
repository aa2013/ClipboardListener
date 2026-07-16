package top.coclyun.clipshare.clipboard_listener

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipDescription
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
import androidx.core.content.FileProvider
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnBinderDeadListener
import rikka.shizuku.Shizuku.OnBinderReceivedListener
import rikka.shizuku.Shizuku.UserServiceArgs
import top.coclyun.clipshare.clipboard_listener.service.ActivityChangedService
import top.coclyun.clipshare.clipboard_listener.service.ClipboardListenerService
import top.coclyun.clipshare.clipboard_listener.service.CommandRunnerService
import top.coclyun.clipshare.clipboard_listener.service.ForegroundService
import top.coclyun.clipshare.clipboard_listener.service.LatestWriteClipboardPkgService
import top.coclyun.clipshare.clipboard_listener.utils.getAppIconAsBase64
import top.coclyun.clipshare.clipboard_listener.utils.getAppNameByPackageName
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val CHANNEL_NAME = "top.coclyun.clipshare/clipboard_listener"
const val ON_CLIPBOARD_CHANGED = "onClipboardChanged"
const val ON_PERMISSION_STATUS_CHANGED = "onPermissionStatusChanged"
const val ON_SHIZUKU_BINDER_STATUS_CHANGED = "onShizukuBinderStatusChanged"
const val START_LISTENING = "startListening"
const val STOP_LISTENING = "stopListening"
const val START_SHIZUKU_BINDER_OBSERVER = "startShizukuBinderObserver"
const val GET_LATEST_WRITE_CLIPBOARD_SOURCE = "getLatestWriteClipboardSource"
const val GET_SHIZUKUVERSION = "getShizukuVersion"
const val CHECK_IS_RUNNING = "checkIsRunning"
const val CHECK_PERMISSION = "checkPermission"
const val REQUEST_PERMISSION = "requestPermission"
const val CHECK_CLIPBOARD_PERMISSION = "checkClipboardPermission"
const val REQUEST_CLIPBOARD_PERMISSION = "requestClipboardPermission"
const val CHECK_ACCESSIBILITY = "checkAccessibility"
const val REQUEST_ACCESSIBILITY = "requestAccessibility"
const val EXECUTE_PRIVILEGED_COMMAND = "executePrivilegedCommand"
const val COPY = "copy"

/** ClipboardListenerPlugin */
class ClipshareClipboardListenerPlugin : FlutterPlugin, MethodCallHandler,
    Shizuku.OnRequestPermissionResultListener,
    ClipboardListener.ClipboardObserver, ActivityAware {
    private val TAG = "ClipboardListenerPlugin"
    private lateinit var channel: MethodChannel
    lateinit var context: Context
    private val requestShizukuCode = 5001
    var currentEnv: EnvironmentType? = null
    var config: Config = Config()
    var listening: Boolean = false
    var listeningServiceArgs: UserServiceArgs? = null
    var listeningServiceConn: ServiceConnection? = null
    var clipboardSourceArgs: UserServiceArgs? = null
    var clipboardSourceServiceConn: ServiceConnection? = null
    var latestWriteClipboardPkgService: ILatestWriteClipboardPkgService? = null
    val latestWriteClipboardPkgShellFileName = "readLastWriteClipboardPkg.sh"
    private var commandRunnerService: ICommandRunnerService? = null
    private var commandRunnerServiceArgs: UserServiceArgs? = null
    private var commandRunnerServiceConn: ServiceConnection? = null
    @Volatile
    private var commandRunnerBinding = false
    private val commandRunnerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clipboardSourceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var observingShizukuBinder = false
    @Volatile
    private var lastShizukuBinderAvailable: Boolean? = null

    private val onShizukuBinderReceivedListener = OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        notifyShizukuBinderStatusChanged(true)
    }

    private val onShizukuBinderDeadListener = OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        handleShizukuUnavailable("binder dead")
    }

    companion object {
        @JvmStatic
        @SuppressLint("StaticFieldLeak")
        var instance: ClipshareClipboardListenerPlugin? = null

        @JvmStatic
        lateinit var activityClass: Class<out Activity>
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
        ).daemon(false).processNameSuffix("clipshare-listening-service")
        Log.d(TAG, "applicationId: ${config.applicationId}")
        Shizuku.addRequestPermissionResultListener(this);
        startShizukuBinderObserver()
        instance = this
        ClipboardListener.init(this, context)
        channel.setMethodCallHandler(this)
        initClipboardSourceShizukuService()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        destroyCommandRunnerService()
        stopShizukuBinderObserver()
        channel.setMethodCallHandler(null)
        instance = null
        Shizuku.removeRequestPermissionResultListener(this);
        ClipboardListener.instance.removeObserver(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            START_LISTENING -> onStartListeningCalled(call, result)

            STOP_LISTENING -> onStopListeningCalled(call, result)

            START_SHIZUKU_BINDER_OBSERVER -> onStartShizukuBinderObserverCalled(call, result)

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

            CHECK_CLIPBOARD_PERMISSION -> checkClipboardPermission(call, result)

            REQUEST_CLIPBOARD_PERMISSION -> requestClipboardPermission(call, result)

            EXECUTE_PRIVILEGED_COMMAND -> executePrivilegedCommand(call, result)
        }
    }

    //region Flutter Method Channel Events

    private fun onStartShizukuBinderObserverCalled(call: MethodCall, result: Result) {
        startShizukuBinderObserver()
        result.success(null)
    }

    /**
     * 注册 Shizuku binder 生命周期监听。
     *
     * binder 可用只表示 Shizuku 服务已经可以连接，不代表当前应用已经获得 Shizuku 授权。
     * 使用 sticky listener 是为了在 Dart 侧订阅后，如果 binder 已经可用，也能立即收到一次状态回调。
     */
    private fun startShizukuBinderObserver() {
        if (observingShizukuBinder) {
            lastShizukuBinderAvailable?.let { notifyShizukuBinderStatusChanged(it) }
            return
        }
        try {
            Shizuku.addBinderReceivedListenerSticky(onShizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(onShizukuBinderDeadListener)
            observingShizukuBinder = true
        } catch (e: Exception) {
            Log.w(TAG, "start Shizuku binder observer failed", e)
            notifyShizukuBinderStatusChanged(false)
        }
    }

    /**
     * 移除 Shizuku binder 监听，避免 Flutter 引擎销毁后继续向失效 channel 发送事件。
     */
    private fun stopShizukuBinderObserver() {
        if (!observingShizukuBinder) return
        try {
            Shizuku.removeBinderReceivedListener(onShizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(onShizukuBinderDeadListener)
        } catch (e: Exception) {
            Log.w(TAG, "stop Shizuku binder observer failed", e)
        } finally {
            observingShizukuBinder = false
        }
    }

    /**
     * 将 Shizuku binder 状态通知给 Dart 层。
     *
     * 该事件只承载服务可连接性，权限请求结果仍由 onPermissionStatusChanged 单独通知。
     */
    private fun notifyShizukuBinderStatusChanged(available: Boolean) {
        lastShizukuBinderAvailable = available
        if (!::channel.isInitialized) return
        channel.invokeMethod(
            ON_SHIZUKU_BINDER_STATUS_CHANGED,
            mapOf("available" to available)
        )
    }

    /**
     * 判断 Shizuku binder 是否仍可用。
     *
     * 该方法只用于插件内部防御，不向 Dart 暴露主动查询能力。
     */
    private fun isShizukuBinderAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Shizuku 断开或授权被撤销后的统一收口。
     *
     * 此时远端 user service 可能已经失效，不能再调用 destroy、exit、unbind 等依赖
     * live binder 的 API，只清理本地引用并把状态通知给 Dart。
     */
    fun handleShizukuUnavailable(reason: String) {
        Log.w(TAG, "Shizuku unavailable: $reason")
        listening = false
        commandRunnerBinding = false
        commandRunnerService = null
        latestWriteClipboardPkgService = null
        if (currentEnv == EnvironmentType.shizuku) {
            currentEnv = null
        }
        notifyShizukuBinderStatusChanged(false)
    }

    private fun onStartListeningCalled(call: MethodCall, result: Result) {
        currentEnv = getCurrentEnvironment()
        Log.d(TAG, "currentEnv $currentEnv")
        ClipboardListener.instance.addObserver(this)
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
        if (!isShizukuBinderAvailable()) {
            result.success(null)
            return
        }
        try {
            result.success(Shizuku.getVersion())
        } catch (e: Exception) {
            handleShizukuUnavailable("get Shizuku version failed: ${e.message}")
            result.success(null)
        }
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
                    if (isShizukuBinderAvailable()) {
                        Shizuku.requestPermission(requestShizukuCode)
                    } else {
                        onPermissionStatusChanged(EnvironmentType.shizuku, false)
                    }
                } catch (e: Exception) {
                    handleShizukuUnavailable("request Shizuku permission failed: ${e.message}")
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
                    if (content == null || !copyImageToPrivateDir(context, content)) {
                        result.success(false)
                        return
                    }
                    val sourceFile = File(content)
                    val file = File(context.externalCacheDir, sourceFile.name)

                    val uri = FileProvider.getUriForFile(
                        context,
                        context.packageName + ".FileProvider",
                        file
                    )
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipData = ClipData(
                        ClipDescription(
                            "image",
                            arrayOf("image/*")
                        ),
                        ClipData.Item(uri)
                    )
                    cm.setPrimaryClip(clipData)
                }
            }
            result.success(true)

        } catch (e: Exception) {
            config.ignoreNextCopy = false;
            result.success(false)
        }
    }

    fun copyImageToPrivateDir(context: Context, sourcePath: String): Boolean {
        val sourceFile = File(sourcePath)

        if (!sourceFile.exists()) {
            Log.e(TAG, "Source file not found")
            return false
        }

        val destFile = File(context.externalCacheDir, sourceFile.name)

        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "Copied to: ${destFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Copy failed", e)
            return false
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

    private fun isAccessibilityServiceEnabled(
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun checkClipboardPermission(call: MethodCall, result: Result) {
        //Android 10以前默认有权限
        if (currentEnv == EnvironmentType.androidPre10) {
            result.success(true)
            return;
        }
        if (!ensureClipboardCommandEnvironment()) {
            result.success(false)
            return
        }
        // 环境探测可能在上一步才识别出 Android 10 以下系统，此类系统不需要额外 AppOps 授权。
        if (currentEnv == EnvironmentType.androidPre10) {
            result.success(true)
            return;
        }
        commandRunnerScope.launch {
            if (commandRunnerService == null) {
                initCommandRunnerService()
                waitCommandRunnerService()
            }
            if (commandRunnerService != null) {
                val res = commandRunnerService!!.run(
                    "cmd appops get ${config.applicationId} | grep CLIPBOARD",
                    currentEnv == EnvironmentType.root
                )
                Log.d(TAG, res)
                if (res.contains("READ_CLIPBOARD: allow") && res.contains("WRITE_CLIPBOARD: allow")) {
                    result.success(true)
                } else {
                    result.success(false)
                }
            } else {
                result.error("commandRunnerService not init", null, null)
            }
        }
    }

    private fun requestClipboardPermission(call: MethodCall, result: Result) {
        //Android 10以前默认有权限
        if (currentEnv == EnvironmentType.androidPre10) {
            result.success(null)
            return;
        }
        if (!ensureClipboardCommandEnvironment()) {
            result.success(null)
            return
        }
        // 环境探测可能在上一步才识别出 Android 10 以下系统，此类系统不需要执行授权命令。
        if (currentEnv == EnvironmentType.androidPre10) {
            result.success(null)
            return;
        }
        commandRunnerScope.launch {
            if (commandRunnerService == null) {
                initCommandRunnerService()
                waitCommandRunnerService()
            }
            if (commandRunnerService != null) {
                commandRunnerService!!.run(
                    "cmd appops set ${config.applicationId} WRITE_CLIPBOARD allow",
                    currentEnv == EnvironmentType.root
                )
                commandRunnerService!!.run(
                    "cmd appops set ${config.applicationId} READ_CLIPBOARD allow",
                    currentEnv == EnvironmentType.root
                )
                result.success(null)
            } else {
                handleShizukuUnavailable("clipboard permission command runner not initialized")
                result.success(null)
            }
        }
    }

    /**
     * 确保剪贴板 AppOps 命令执行前已经解析出可用工作环境。
     *
     * 设置页可能早于 startListening 调用剪贴板权限检查，此时 currentEnv 仍为空；
     * 这里复用现有环境探测逻辑，避免因初始化顺序竞态把可用的 Shizuku/root 误判为无权限。
     */
    private fun ensureClipboardCommandEnvironment(): Boolean {
        if (currentEnv == null) {
            currentEnv = getCurrentEnvironment()
        }
        if (currentEnv == null) {
            Log.w(TAG, "clipboard command environment unavailable")
            return false
        }
        return true
    }

    private fun executePrivilegedCommand(call: MethodCall, result: Result) {
        commandRunnerScope.launch {
            if (commandRunnerService == null) {
                initCommandRunnerService()
                waitCommandRunnerService()
            }
            if (commandRunnerService != null) {
                val res = commandRunnerService!!.run(
                    call.argument<String>("command"),
                    currentEnv == EnvironmentType.root
                )
                result.success(res)
            } else {
                handleShizukuUnavailable("privileged command runner not initialized")
                result.success(null)
            }
        }
    }

    //endregion

    @Synchronized
    private fun initCommandRunnerService() {
        if (commandRunnerService != null || commandRunnerBinding) {
            return
        }
        if (currentEnv == EnvironmentType.root && commandRunnerService == null) {
            commandRunnerService = CommandRunnerService()
            return
        }
        if (!checkShizukuPermission()) {
            handleShizukuUnavailable("command runner bind skipped because Shizuku is unavailable")
            return
        }
        if (commandRunnerServiceArgs == null) {
            commandRunnerServiceArgs = UserServiceArgs(
                ComponentName(
                    config.applicationId,
                    CommandRunnerService::class.java.name
                )
            ).daemon(false).processNameSuffix("clipshare-command-service")
        }
        if (commandRunnerServiceConn == null) {
            commandRunnerServiceConn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    Log.d(TAG, "onServiceConnected ${name.className}")
                    commandRunnerBinding = false
                    try {
                        commandRunnerService = ICommandRunnerService.Stub.asInterface(binder)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    commandRunnerBinding = false
                    commandRunnerService = null
                    handleShizukuUnavailable("command runner service disconnected")
                    Log.w(TAG, "onServiceDisconnected ${name.className}")
                }
            }
        }
        try {
            commandRunnerBinding = true
            Shizuku.bindUserService(commandRunnerServiceArgs!!, commandRunnerServiceConn!!)
        } catch (e: Exception) {
            commandRunnerBinding = false
            handleShizukuUnavailable("bind command runner failed: ${e.message}")
        }
    }

    suspend fun waitCommandRunnerService(timeout: Long = 5000) {
        withTimeout(timeout) {
            while (commandRunnerService == null && commandRunnerBinding) {
                delay(50)
            }
        }
    }

    @Synchronized
    private fun destroyCommandRunnerService() {
        try {
            if (currentEnv == EnvironmentType.root || isShizukuBinderAvailable()) {
                commandRunnerService?.destroy()
            }
            if (currentEnv == EnvironmentType.shizuku && isShizukuBinderAvailable() && commandRunnerServiceArgs != null && commandRunnerServiceConn != null) {
                Shizuku.unbindUserService(
                    commandRunnerServiceArgs!!,
                    commandRunnerServiceConn!!,
                    true
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            commandRunnerService = null
            commandRunnerBinding = false
        }
    }

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun stopListening() {
        ClipboardListener.instance.removeObserver(this)
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
        ).daemon(false).processNameSuffix("clipboard-source-service")
        clipboardSourceServiceConn = object : ServiceConnection {
            private var service: ILatestWriteClipboardPkgService? = null
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (service != null) {
                    try {
                        service?.destroy()
                    } catch (e: Exception) {
                        //ignored
                    }
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
                if (!checkShizukuPermission()) {
                    handleShizukuUnavailable("clipboard source bind skipped because Shizuku is unavailable")
                    return false
                }
                if (clipboardSourceArgs != null && clipboardSourceServiceConn != null) {
                    Shizuku.bindUserService(clipboardSourceArgs!!, clipboardSourceServiceConn!!)
                } else {
                    return false
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            if (env == EnvironmentType.shizuku) {
                handleShizukuUnavailable("start clipboard source service failed: ${e.message}")
            }
            Log.e(TAG, "startClipboardSourceService failed! Error: ${e.message}")
        }
        return false
    }

    private fun stopClipboardSourceService() {
        try {
            if (currentEnv == EnvironmentType.root || isShizukuBinderAvailable()) {
                latestWriteClipboardPkgService?.destroy()
            }
            latestWriteClipboardPkgService = null
            if (clipboardSourceArgs != null && clipboardSourceServiceConn != null && currentEnv == EnvironmentType.shizuku && isShizukuBinderAvailable()) {
                Shizuku.unbindUserService(
                    clipboardSourceArgs!!,
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
        } else if (currentEnv == env) {
            currentEnv = null
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
        if (!isShizukuBinderAvailable()) {
            handleShizukuUnavailable("check Shizuku permission while binder unavailable")
            return false
        }
        try {
            if (Shizuku.isPreV11()) {
                Log.d(TAG, "Pre-v11 is unsupported")
                Toast.makeText(this.context, "Pre-v11 is unsupported", Toast.LENGTH_LONG).show()
                return false
            }
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
            handleShizukuUnavailable("check Shizuku permission failed: ${e.message}")
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
        activityClass = binding.activity.javaClass
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityClass = binding.activity.javaClass
    }

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onDetachedFromActivity() {}
    //endregion
}
