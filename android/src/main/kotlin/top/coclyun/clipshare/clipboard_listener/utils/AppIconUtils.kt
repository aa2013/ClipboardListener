package top.coclyun.clipshare.clipboard_listener.utils

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import java.io.ByteArrayOutputStream
import androidx.core.graphics.createBitmap

/**
 * 根据包名获取应用名称
 * @param packageName 目标应用的包名（如 "com.tencent.mm"）
 * @return 应用名称（若包名不存在则返回 null）
 */
fun getAppNameByPackageName(context: Context, packageName: String): String? {
    return try {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, PackageManager.MATCH_ALL)
        pm.getApplicationLabel(appInfo).toString() // 转为 String
    } catch (e: PackageManager.NameNotFoundException) {
        e.printStackTrace()
        null // 包名不存在时返回 null
    }
}
/**
 * 根据包名获取应用图标的 Base64 字符串
 * @param packageName 目标应用的包名（如 "com.example.app"）
 * @return Base64 字符串（可能为 null）
 */
fun getAppIconAsBase64(context: Context, packageName: String): String? {
    return try {
        // 1. 获取 Drawable 图标
        val drawable = context.packageManager.getApplicationIcon(packageName)

        // 2. 将 Drawable 转为 Bitmap
        val bitmap = if (drawable is BitmapDrawable) {
            drawable.bitmap
        } else {
            // 处理 VectorDrawable 等非 Bitmap 类型
            val newBitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
            val canvas = android.graphics.Canvas(newBitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            newBitmap
        }

        // 3. 将 Bitmap 转为 Base64
        bitmapToBase64(bitmap)
    } catch (e: PackageManager.NameNotFoundException) {
        e.printStackTrace()
        null
    }
}

/**
 * 将 Bitmap 转换为 Base64 字符串
 */
private fun bitmapToBase64(bitmap: Bitmap): String {
    val byteArrayOutputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream) // PNG 无损压缩
    val byteArray = byteArrayOutputStream.toByteArray()
    return Base64.encodeToString(byteArray, Base64.NO_WRAP) // 不要换行，否则会每76字符插入一个换行符
}