package dev.aioverlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import java.io.File

/** Invisible trampoline: the overlay service can't open a file picker itself. */
class PickActivity : Activity() {
    companion object { var callback: ((Array<Uri>?) -> Unit)? = null }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state == null) startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // Copy to cache: the read grant on the picked URI dies with this activity.
        val picked = data?.data?.takeIf { resultCode == RESULT_OK }?.let { uri ->
            runCatching {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(uri)) ?: "bin"
                val f = File(cacheDir, "pick_${System.currentTimeMillis()}.$ext")
                contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                arrayOf(Uri.fromFile(f))
            }.getOrNull()
        }
        callback?.invoke(picked) // must always answer, or the web page's 📎 stops working
        callback = null
        finish()
    }
}
