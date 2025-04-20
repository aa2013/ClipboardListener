package top.coclyun.clipshare.clipboard_listener

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


fun copyAssetToExternalPrivateDir(
    context: Context,
    assetFileName: String,
    destinationFileName: String?
): Boolean {

    var inputStream: InputStream? = null
    var outputStream: OutputStream? = null

    try {
        // �� assets �ļ�������
        inputStream = context.resources.assets.open(assetFileName)

        // ����Ŀ���ļ�
        val outFile = File(context.getExternalFilesDir(null), assetFileName)

        // ���ļ������
        outputStream = FileOutputStream(outFile)

        // �����ļ�
        val buffer = ByteArray(1024)
        var length: Int
        while ((inputStream.read(buffer).also { length = it }) > 0) {
            outputStream.write(buffer, 0, length)
        }

        // ˢ�²��ر���
        outputStream.flush()
        return true
    } catch (e: IOException) {
        Log.e("AssetCopyHelper", "Failed to copy asset file: $assetFileName", e)
        return false
    } finally {
        try {
            inputStream?.close()
            outputStream?.close()
        } catch (e: IOException) {
            Log.e("AssetCopyHelper", "Failed to close streams", e)
        }
    }
}
fun isFileExistsInPrivateDir(context: Context, fileName: String?): Boolean {
    val file = File(context.getExternalFilesDir(null), fileName)
    return file.exists()
}