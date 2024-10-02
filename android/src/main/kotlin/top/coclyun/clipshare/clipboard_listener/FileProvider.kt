package top.coclyun.clipshare.clipboard_listener

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

class FileProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        return true;
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return null
    }

    override fun getType(uri: Uri): String {
        return "image/*"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        return 0
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        var imode = 0
        if (mode.contains("w")) imode = imode or ParcelFileDescriptor.MODE_WRITE_ONLY
        if (mode.contains("r")) imode = imode or ParcelFileDescriptor.MODE_READ_ONLY
        if (mode.contains("+")) imode = imode or ParcelFileDescriptor.MODE_APPEND
        val filePath = uri.pathSegments.joinToString("/")
        Log.d("fileProvider", filePath)
        try {
            return ParcelFileDescriptor.open(File(filePath), imode)
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }
        Log.d("fileProvider", "can not open")
        return null
    }
}