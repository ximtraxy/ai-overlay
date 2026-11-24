package dev.aioverlay

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Serves the 📋 cut-outs so the notes app can paste them from the clipboard (read-only, grant per paste). */
class ShotProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "dev.aioverlay.shots"
        const val DIR = "clip"
    }

    private fun file(uri: Uri): File {
        val name = safeName(uri.lastPathSegment) ?: throw FileNotFoundException("bad name")
        return File(File(context!!.cacheDir, DIR), name).takeIf { it.exists() } ?: throw FileNotFoundException(name)
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val f = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/** Plain file name only: blocks "../" tricks in the URI. */
fun safeName(name: String?): String? = name?.takeIf { it.matches(Regex("[\\w-]+(\\.[\\w-]+)*")) }
