package top.coclyun.clipshare.clipboard_listener

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min


open class ClipboardListener(
    private var plugin: ClipboardListenerPlugin,
    private var context: Context
) {
    interface ClipboardObserver {
        fun onClipboardChanged(type: ClipboardContentType, content: String, packageName: String?)
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var _instance: ClipboardListener? = null
        fun init(plugin: ClipboardListenerPlugin, context: Context): ClipboardListener {
            _instance = ClipboardListener(plugin, context)
            return _instance!!
        }

        @JvmStatic
        val instance: ClipboardListener
            get() {
                if (_instance == null) {
                    throw Exception("You must first call the init() method")
                }
                return _instance!!
            }
    }

    private val TAG: String = "ClipboardListener";
    private val mediaImagesUri = "content://media/external/images/"
    private val observers = HashSet<ClipboardObserver>()
    private var cm: ClipboardManager? = null;

    init {
        Handler(Looper.getMainLooper()).post {
            cm = ContextCompat.getSystemService(
                context,
                ClipboardManager::class.java
            )
            cm!!.addPrimaryClipChangedListener {
                onClipboardChanged(null)
            }
        }
    }

    fun onClipboardChanged(packageName: String?) {
        val env = plugin.currentEnv;
        if (!plugin.listening && (env == EnvironmentType.shizuku || env == EnvironmentType.root)) {
            return
        }
        try {
            val item = cm!!.primaryClip!!.getItemAt(0)
            val description = cm!!.primaryClipDescription!!
            val label = description.label;
            var type = ClipboardContentType.Text;
            var content = (item.text ?: item.coerceToText(context)).toString()
            Log.d(TAG, "label:$label, uri:${item.uri}, content: ${content.substring(0, min(content.length,20))}")
            if (item.uri != null) {
                val contentResolver = context.contentResolver
                val mimeType = contentResolver.getType(item.uri);
                Log.d(TAG, "mimeType:$mimeType")
                if (mimeType != null && mimeType.startsWith("image")) {
                    type = ClipboardContentType.Image;
                    val currentTimeMillis = System.currentTimeMillis()
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-S", Locale.CHINA)
                    val fileName = dateFormat.format(Date(currentTimeMillis))
                    val cachePath =
                        context.externalCacheDir?.absolutePath + "/" + fileName + ".png";
                    Log.d(TAG, "cachePath $cachePath")
                    try {
                        val inputStream = contentResolver.openInputStream(item.uri)
                        if (inputStream == null) {
                            Log.e(TAG, "Failed to open input stream for URI: ${item.uri}")
                            return;
                        }
                        val destFile = File(cachePath)
                        val outputStream: OutputStream = FileOutputStream(destFile)
                        val buffer = ByteArray(10240)
                        var length: Int
                        while (inputStream.read(buffer).also { length = it } > 0) {
                            outputStream.write(buffer, 0, length)
                        }
                        inputStream.close()
                        outputStream.close()
                        Log.d(TAG, "File copied successfully to: $cachePath")
                    } catch (e: IOException) {
                        Log.e(TAG, "Error copying file: " + e.message)
                    }
                    content = cachePath;
                }
            }
            for (observer in observers) {
                observer.onClipboardChanged(type, content, packageName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.d(TAG, "onClipboardChanged error: ${e.message}")
        }
    }

    fun addObserver(observer: ClipboardObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ClipboardObserver) {
        observers.remove(observer)
    }


}