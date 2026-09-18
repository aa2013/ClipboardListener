package top.coclyun.clipshare.clipboard_listener

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Activity
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
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import top.coclyun.clipshare.clipboard_listener.service.ListenerLifecycle
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
const val ARG_ENV = "env"
const val ARG_AUTO_GRANT = "autoGrant"
const val SETTINGS_NULL_VALUE = "null"
const val ACCESSIBILITY_SERVICE_SEPARATOR = ":"

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
    internal val listenerLifecycle = ListenerLifecycle()
    val listening: Boolean get() = listenerLifecycle.running
    var listeningServiceArgs: UserServiceArgs? = null
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
        stopListening()
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
     * Registers Shizuku binder lifecycle listeners.
     *
     * Binder availability only means the Shizuku service is reachable, not that
     * this app has Shizuku permission. The sticky listener emits the current
     * binder state immediately after Dart subscribes.
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
     * Removes Shizuku binder listeners when the Flutter engine is detached.
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
     * Notifies Dart about Shizuku binder availability.
     *
     * This event only reports service connectivity; permission results still use
     * onPermissionStatusChanged.
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
     * Checks whether the Shizuku binder is still reachable.
     *
     * This is an internal defensive check and is not exposed as an active Dart
     * query API.
     */
    private fun isShizukuBinderAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Clears local state after Shizuku disconnects or permission is revoked.
     *
     * Remote user services may already be invalid, so this path avoids calling
     * APIs that require a live binder and only notifies Dart about availability.
     */
    fun handleShizukuUnavailable(reason: String) {
        Log.w(TAG, "Shizuku unavailable: $reason")
        val session = listenerLifecycle.current
        if (session?.environment == EnvironmentType.shizuku) {
            listenerLifecycle.stop(session)
            ForegroundService.shizukuUnavailable(this, reason)
        }
        if (currentEnv == EnvironmentType.shizuku) {
            commandRunnerBinding = false
            commandRunnerService = null
            latestWriteClipboardPkgService = null
            currentEnv = null
        }
        notifyShizukuBinderStatusChanged(false)
    }

    private fun onStartListeningCalled(call: MethodCall, result: Result) {
        val detectedEnv = getCurrentEnvironment()
        Log.d(TAG, "detectedEnv $detectedEnv")
        ClipboardListener.instance.addObserver(this)
        val envStr = call.argument<String>(ARG_ENV)
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

        val targetEnv = env ?: detectedEnv
        switchPrivilegedEnvironmentIfNeeded(targetEnv)
        currentEnv = targetEnv
        Log.d(TAG, "targetEnv $targetEnv")

        val res = startListeningClipboard(targetEnv, way)
        startClipboardSourceService(targetEnv)
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
            return
        }
        result.success(listening)
    }

    private fun onCheckPermissionCalled(call: MethodCall, result: Result) {
        val envName = call.argument<String>(ARG_ENV)
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
        val envName = call.argument<String>(ARG_ENV)
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
        return isAccessibilityServiceEnabledInSecureSettings(context, service) ||
                isAccessibilityServiceEnabledInManager(context, service)
    }

    /**
     * Checks secure settings with ComponentName parsing instead of raw string matching.
     *
     * Some ROMs store enabled accessibility services with shortened component
     * names, so parsing avoids false negatives caused by formatting differences.
     */
    private fun isAccessibilityServiceEnabledInSecureSettings(
        context: Context,
        service: Class<out AccessibilityService?>
    ): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(context, service)
        return parseEnabledAccessibilityComponents(enabledServices).any { it == expected }
    }

    /**
     * Checks the AccessibilityManager enabled service list as a runtime fallback.
     *
     * This reflects the services Android actually considers enabled, which helps
     * when secure settings are temporarily stale or formatted unexpectedly.
     */
    private fun isAccessibilityServiceEnabledInManager(
        context: Context,
        service: Class<out AccessibilityService?>
    ): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val expected = ComponentName(context, service)
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo.serviceInfo
                    serviceInfo.packageName == expected.packageName &&
                            serviceInfo.name == expected.className
                }
        } catch (e: Exception) {
            Log.w(TAG, "check accessibility manager failed", e)
            false
        }
    }

    private fun requestAccessibility(call: MethodCall, result: Result) {
        val autoGrant = call.argument<Boolean>(ARG_AUTO_GRANT) == true
        if (!autoGrant) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            result.success(false)
            return
        }
        requestAccessibilityByPrivilegedCommand(result)
    }

    /**
     * Enables the plugin accessibility service with Shizuku or root.
     *
     * Android stores enabled accessibility services as a colon-separated secure
     * setting. This method preserves the existing list and appends the plugin
     * service only when it is missing.
     */
    private fun requestAccessibilityByPrivilegedCommand(result: Result) {
        if (!ensureClipboardCommandEnvironment()) {
            result.success(false)
            return
        }
        commandRunnerScope.launch {
            try {
                if (commandRunnerService == null) {
                    initCommandRunnerService()
                    waitCommandRunnerService()
                }
                val service = commandRunnerService
                if (service == null) {
                    markCommandRunnerUnavailable("accessibility command runner not initialized")
                    result.success(false)
                    return@launch
                }

                val serviceName = getAccessibilityServiceName(ActivityChangedService::class.java)
                val currentServices = service.run(
                    "settings get secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES}",
                    currentEnv == EnvironmentType.root
                ).trim()
                val enabledServices = parseEnabledAccessibilityServices(currentServices)
                val mergedServices = if (enabledServices.contains(serviceName)) {
                    enabledServices
                } else {
                    enabledServices + serviceName
                }
                val mergedSetting = mergedServices.joinToString(ACCESSIBILITY_SERVICE_SEPARATOR)
                service.run(
                    "settings put secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} ${shellQuote(mergedSetting)}",
                    currentEnv == EnvironmentType.root
                )
                service.run(
                    "settings put secure ${Settings.Secure.ACCESSIBILITY_ENABLED} 1",
                    currentEnv == EnvironmentType.root
                )
                result.success(isAccessibilityServiceEnabled(context, ActivityChangedService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "auto grant accessibility failed", e)
                result.success(false)
            }
        }
    }

    /**
     * Builds the same component name Android stores in enabled_accessibility_services.
     */
    private fun getAccessibilityServiceName(service: Class<out AccessibilityService?>): String {
        return "${context.packageName}/${service.name}"
    }

    /**
     * Parses the secure setting value while ignoring empty and literal null values.
     */
    private fun parseEnabledAccessibilityServices(value: String): List<String> {
        if (value.isBlank() || value == SETTINGS_NULL_VALUE) {
            return emptyList()
        }
        return value.split(ACCESSIBILITY_SERVICE_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != SETTINGS_NULL_VALUE }
            .distinct()
    }

    /**
     * Parses enabled accessibility service entries into component names.
     */
    private fun parseEnabledAccessibilityComponents(value: String): List<ComponentName> {
        return parseEnabledAccessibilityServices(value)
            .mapNotNull { ComponentName.unflattenFromString(it) }
    }

    /**
     * Quotes a value for the shell command sent through Shizuku or root.
     */
    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }

    private fun checkClipboardPermission(call: MethodCall, result: Result) {
        // Android versions before 10 grant clipboard access by default.
        if (currentEnv == EnvironmentType.androidPre10) {
            result.success(true)
            return;
        }
        if (!ensureClipboardCommandEnvironment()) {
            result.success(false)
            return
        }
        // Environment detection may have just resolved Android pre-10, which does not need AppOps grants.
        if (currentEnv == EnvironmentType.androidPre10) {
            result.success(true)
            return;
        }
        commandRunnerScope.launch {
            try {
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
                    markCommandRunnerUnavailable("clipboard permission check command runner not initialized")
                    result.success(false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "check clipboard permission failed", e)
                result.success(false)
            }
        }
    }

    private fun requestClipboardPermission(call: MethodCall, result: Result) {
        // Android versions before 10 grant clipboard access by default.
        if (currentEnv == EnvironmentType.androidPre10) {
            result.success(null)
            return;
        }
        if (!ensureClipboardCommandEnvironment()) {
            result.success(null)
            return
        }
        // Environment detection may have just resolved Android pre-10, which does not need AppOps commands.
        if (currentEnv == EnvironmentType.androidPre10) {
            result.success(null)
            return;
        }
        commandRunnerScope.launch {
            try {
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
                } else {
                    markCommandRunnerUnavailable("clipboard permission command runner not initialized")
                }
                result.success(null)
            } catch (e: Exception) {
                Log.w(TAG, "request clipboard permission failed", e)
                result.success(null)
            }
        }
    }

    /**
     * Resolves the privileged environment before clipboard AppOps commands run.
     *
     * Settings pages may check permissions before startListening initializes
     * currentEnv, so this falls back to environment detection only when the
     * clipboard listener has not selected a backend yet.
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

    /**
     * Clears command runner state without assuming every failure belongs to Shizuku.
     */
    private fun markCommandRunnerUnavailable(reason: String) {
        if (currentEnv == EnvironmentType.shizuku) {
            handleShizukuUnavailable(reason)
            return
        }
        Log.w(TAG, "Command runner unavailable: $reason, env=$currentEnv")
        commandRunnerBinding = false
        commandRunnerService = null
    }

    private fun executePrivilegedCommand(call: MethodCall, result: Result) {
        if (!ensureClipboardCommandEnvironment()) {
            result.success(null)
            return
        }
        commandRunnerScope.launch {
            try {
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
                    markCommandRunnerUnavailable("privileged command runner not initialized")
                    result.success(null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "execute privileged command failed", e)
                result.success(null)
            }
        }
    }

    //endregion

    /**
     * Rebuilds privileged helpers when the app switches between Shizuku/root modes.
     *
     * Both command runner and clipboard source helpers follow currentEnv, which
     * is selected by startListening. Switching the listener backend therefore
     * tears down any helper created for the previous backend.
     */
    private fun switchPrivilegedEnvironmentIfNeeded(targetEnv: EnvironmentType?) {
        if (targetEnv == null || targetEnv == currentEnv) {
            return
        }
        if (commandRunnerService != null || commandRunnerBinding) {
            destroyCommandRunnerService()
        }
        if (latestWriteClipboardPkgService != null) {
            stopClipboardSourceService()
        }
    }

    @Synchronized
    private fun initCommandRunnerService() {
        if (commandRunnerService != null || commandRunnerBinding) {
            return
        }
        if (currentEnv == EnvironmentType.root && commandRunnerService == null) {
            commandRunnerService = CommandRunnerService()
            return
        }
        if (currentEnv != EnvironmentType.shizuku) {
            markCommandRunnerUnavailable("command runner requires Shizuku or root, env=$currentEnv")
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
        val previous = listenerLifecycle.current
        val session = listenerLifecycle.start(env ?: currentEnv, way)
        if (session === previous) {
            Log.d(TAG, "reuse listener session=${session.id}, state=${session.state}")
            return true
        }
        try {
            Log.i(TAG, "request listener session=${session.id}, env=${session.environment}, way=$way")
            val serviceIntent = Intent(context, ForegroundService::class.java)
            serviceIntent.putExtra(ForegroundService.EXTRA_SESSION_ID, session.id)
            serviceIntent.putExtra("useRoot", session.environment == EnvironmentType.root)
            serviceIntent.putExtra("way", way.name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            return true
        } catch (e: Exception) {
            listenerLifecycle.finish(session)
            ForegroundService.requestStop(this, "start service failed")
            Log.e(TAG, "start listener session=${session.id} failed", e)
            return false
        }
    }

    private fun stopListening() {
        ClipboardListener.instance.removeObserver(this)
        listenerLifecycle.stop()
        ForegroundService.requestStop(this, "stopListening requested")
        stopClipboardSourceService()
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
                if (currentEnv != EnvironmentType.shizuku) {
                    try {
                        ILatestWriteClipboardPkgService.Stub.asInterface(binder)?.destroy()
                    } catch (_: Exception) {
                    }
                    return
                }
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
