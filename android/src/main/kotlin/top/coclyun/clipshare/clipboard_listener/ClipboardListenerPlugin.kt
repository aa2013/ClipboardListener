package top.coclyun.clipshare.clipboard_listener

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import top.coclyun.clipshare.clipboard_listener.service.ForegroundService
import java.io.DataOutputStream

const val CHANNEL_NAME = "top.coclyun.clipshare/clipboard_listener"
const val ON_CLIPBOARD_CHANGED = "onClipboardChanged"
const val ON_PERMISSION_STATUS_CHANGED = "onPermissionStatusChanged"
const val START_LISTENING = "startListening"
const val STOP_LISTENING = "stopListening"
const val GET_SHIZUKUVERSION = "getShizukuVersion"
const val CHECK_IS_RUNNING = "checkIsRunning"
const val CHECK_PERMISSION = "checkPermission"
const val REQUEST_PERMISSION = "requestPermission"
const val COPY = "copy"

/** ClipboardListenerPlugin */
class ClipboardListenerPlugin : FlutterPlugin, MethodCallHandler,
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
    var userServiceArgs: UserServiceArgs? = null
    var serviceConnection: ServiceConnection? = null

    companion object {
        @JvmStatic
        @SuppressLint("StaticFieldLeak")
        var instance: ClipboardListenerPlugin? = null
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, CHANNEL_NAME)
        context = flutterPluginBinding.applicationContext
        config.applicationId = context.applicationInfo.packageName
        userServiceArgs = UserServiceArgs(
            android.content.ComponentName(
                config.applicationId,
                top.coclyun.clipshare.clipboard_listener.service.LogService::class.java.name
            )
        ).daemon(false).processNameSuffix("service")
        Log.d(TAG, "applicationId: ${config.applicationId}")
        Shizuku.addRequestPermissionResultListener(this);
        currentEnv = getCurrentEnvironment()
        instance = this
        ClipboardListener.init(this, context)
        ClipboardListener.instance.addObserver(this)
        channel.setMethodCallHandler(this)
        Log.d(TAG, "currentEnv $currentEnv")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        instance = null
        Shizuku.removeRequestPermissionResultListener(this);
        ClipboardListener.instance.removeObserver(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            START_LISTENING -> {
                val envStr = call.argument<String>("env")
                var env: EnvironmentType? = null
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
                //region 通知参数赋值
                config = Config().apply {
                    applicationId = config.applicationId
                    ignoreNextCopy = config.ignoreNextCopy
                }
                val cfgClz = config::class.java
                for (field in cfgClz.declaredFields) {
                    field.isAccessible = true
                    val value = call.argument<String>(field.name)
                    value?.let { field.set(config, value) }
                }
                //endregion

                result.success(startListeningClipboard(env))
            }

            STOP_LISTENING -> stopListening()
            GET_SHIZUKUVERSION -> result.success(Shizuku.getVersion())
            CHECK_IS_RUNNING -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    result.success(true)
                }
                result.success(listening)
            }

            CHECK_PERMISSION -> {
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

            REQUEST_PERMISSION -> {
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

            COPY ->
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

    override fun onClipboardChanged(type: ClipboardContentType, content: String) {
        if (config.ignoreNextCopy) return
        channel.invokeMethod(
            ON_CLIPBOARD_CHANGED,
            mapOf("content" to content, "type" to type.name)
        )
    }

    private fun startListeningClipboard(env: EnvironmentType? = null): Boolean {
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
        return startListening(env)
    }

    private fun startListening(env: EnvironmentType?): Boolean {
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
        context.stopService(serviceIntent)
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
    }


    override fun onDetachedFromActivity() {
        mainActivity = null
        stopListening()
    }
    //endregion
}
