package com.nova.gallery.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app error log (same pattern as NOVA Files / Dialer / Contacts).
 * Crashes and failed operations are appended to filesDir/error-log.txt
 * (newest first, max 100 entries) so a problem can be reported with the
 * exact error instead of "it crashed".
 */
object ErrorLog {
    private const val FILE_NAME = "error-log.txt"
    private const val MAX_ENTRIES = 100

    // v1.7: two threads logging at once could interleave readText/writeText
    // and lose entries — all file access goes through one lock.
    private val lock = Any()

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun log(context: Context, tag: String, message: String, error: Throwable? = null) {
        synchronized(lock) {
            try {
                val f = file(context)
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val sb = StringBuilder()
                sb.append("[$time] $tag: $message\n")
                error?.let {
                    sb.append("  ${it.javaClass.simpleName}: ${it.message}\n")
                    it.stackTrace.take(12).forEach { frame -> sb.append("    at $frame\n") }
                }
                sb.append("---\n")
                val existing = if (f.exists()) f.readText() else ""
                val entries = (sb.toString() + existing).split("---\n")
                val kept = entries.take(MAX_ENTRIES).joinToString("---\n")
                f.writeText(kept)
            } catch (_: Exception) { }
        }
    }

    fun readAll(context: Context): String {
        return synchronized(lock) {
            try {
                val f = file(context)
                if (f.exists()) f.readText() else ""
            } catch (_: Exception) { "" }
        }
    }

    fun count(context: Context): Int {
        val text = readAll(context)
        if (text.isBlank()) return 0
        return text.split("---\n").count { it.isNotBlank() }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try { file(context).delete() } catch (_: Exception) { }
        }
    }
}
