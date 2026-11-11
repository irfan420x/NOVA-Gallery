package com.nova.gallery.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * MediaStore access for the Gallery. Every query is guarded: no permission
 * means an empty list (never a crash), and any provider failure is logged
 * to the in-app Error log and also returns an empty list.
 */
object MediaRepo {

    fun hasMediaPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            val images = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_MEDIA_IMAGES
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val videos = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_MEDIA_VIDEO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            images || videos
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private val PROJECTION = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DURATION
    )

    private fun readItems(
        context: Context,
        collection: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int
    ): List<MediaItem> {
        if (!hasMediaPermission(context)) return emptyList()
        val favorites = FavoriteStore.load(context)
        val out = ArrayList<MediaItem>()
        try {
            // NOTE: no LIMIT here — MediaStore's provider rejects the LIMIT
            // token in the sort order ("Invalid token LIMIT"). The caller
            // caps the result with .take(limit) instead.
            val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            context.contentResolver.query(
                collection, PROJECTION, selection, selectionArgs, sortOrder
            )?.use { c ->
                val idI = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val modI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val takenI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val sizeI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val wI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val hI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val bucketI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val pathI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val durI = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(idI)
                    val mime = c.getString(mimeI) ?: ""
                    val isVideo = mime.startsWith("video/")
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    out += MediaItem(
                        id = id,
                        uri = uri,
                        displayName = c.getString(nameI) ?: "Untitled",
                        kind = if (isVideo) MediaKind.VIDEO else MediaKind.IMAGE,
                        mimeType = mime,
                        dateModified = c.getLong(modI),
                        dateTaken = c.getLong(takenI),
                        sizeBytes = c.getLong(sizeI),
                        width = c.getInt(wI),
                        height = c.getInt(hI),
                        durationMs = if (durI >= 0) c.getLong(durI) else 0L,
                        bucket = c.getString(bucketI) ?: "Unknown",
                        relativePath = c.getString(pathI) ?: "",
                        isFavorite = favorites.contains(uri.toString())
                    )
                }
            }
        } catch (e: Exception) {
            ErrorLog.log(context, "Gallery", "Failed to load media", e)
            return emptyList()
        }
        return out
    }

    /** All photos and videos, newest first. */
    fun loadAll(context: Context, limit: Int = 2000): List<MediaItem> {
        val images = readItems(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null, limit)
        val videos = readItems(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, null, limit)
        return (images + videos).sortedByDescending { it.dateModified }.take(limit)
    }

    /** Media in one album (bucket), newest first. */
    fun loadAlbum(context: Context, bucket: String, limit: Int = 2000): List<MediaItem> {
        val sel = "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ?"
        val images = readItems(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, sel, arrayOf(bucket), limit)
        val videos = readItems(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, sel, arrayOf(bucket), limit)
        return (images + videos).sortedByDescending { it.dateModified }.take(limit)
    }

    /** Albums with counts and covers, biggest first. */
    fun loadAlbums(context: Context): List<Album> {
        val all = loadAll(context, 5000)
        if (all.isEmpty()) return emptyList()
        val grouped = all.groupBy { it.bucket }
        return grouped.map { (name, items) ->
            Album(
                name = name,
                count = items.size,
                coverUri = items.firstOrNull()?.uri,
                totalSizeBytes = items.sumOf { it.sizeBytes }
            )
        }.sortedByDescending { it.count }
    }

    /** Favorites only, newest first. */
    fun loadFavorites(context: Context): List<MediaItem> {
        return loadAll(context, 5000).filter { it.isFavorite }
    }

    /** Search by file name (case-insensitive substring). */
    fun search(context: Context, query: String, limit: Int = 500): List<MediaItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$q%")
        val images = readItems(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, sel, args, limit)
        val videos = readItems(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, sel, args, limit)
        return (images + videos).sortedByDescending { it.dateModified }.take(limit)
    }

    /**
     * Delete one item. On Android 10+ the system may refuse with a
     * RecoverableSecurityException — the caller gets it back as a
     * [DeleteNeedsConfirmation] so it can launch the system confirmation.
     * Returns true when the file is actually gone.
     */
    sealed interface DeleteResult {
        data object Deleted : DeleteResult
        data class NeedsConfirmation(val exception: android.app.RecoverableSecurityException) : DeleteResult
        data class Failed(val error: Exception) : DeleteResult
    }

    fun delete(context: Context, item: MediaItem): DeleteResult {
        return try {
            val rows = context.contentResolver.delete(item.uri, null, null)
            if (rows > 0) DeleteResult.Deleted
            else DeleteResult.Failed(Exception("Nothing was deleted"))
        } catch (e: android.app.RecoverableSecurityException) {
            DeleteResult.NeedsConfirmation(e)
        } catch (e: SecurityException) {
            DeleteResult.Failed(e)
        } catch (e: Exception) {
            ErrorLog.log(context, "Gallery", "Failed to delete ${item.displayName}", e)
            DeleteResult.Failed(e)
        }
    }

    /**
     * Delete several items at once. On Android 11+ this uses one system
     * confirmation (MediaStore.createDeleteRequest) for the whole batch.
     * Returns the PendingIntentSender to launch, or null when everything
     * was deleted directly.
     */
    fun deleteMultiple(
        context: Context,
        items: List<MediaItem>,
        onResult: (deleted: Int, needsConfirmation: android.content.IntentSender?) -> Unit
    ) {
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = items.map { it.uri }
                val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
                onResult(0, request.intentSender)
                return
            } catch (e: Exception) {
                ErrorLog.log(context, "Gallery", "Batch delete request failed", e)
            }
        }
        var deleted = 0
        var sender: android.content.IntentSender? = null
        for (item in items) {
            when (val r = delete(context, item)) {
                is DeleteResult.Deleted -> deleted++
                is DeleteResult.NeedsConfirmation -> {
                    sender = r.exception.userAction.actionIntent.intentSender
                    break
                }
                is DeleteResult.Failed -> { }
            }
        }
        onResult(deleted, sender)
    }

    fun shareIntent(item: MediaItem): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType.ifEmpty { if (item.isVideo) "video/*" else "image/*" }
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareMultipleIntent(items: List<MediaItem>): Intent {
        val uris = ArrayList<Uri>(items.map { it.uri })
        val type = if (items.any { it.isVideo }) "video/*" else "image/*"
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            this.type = type
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

/** Favorites are a persisted set of content-URI strings. */
object FavoriteStore {
    private const val PREFS = "nova_gallery"
    private const val KEY = "favorites"

    fun load(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()) ?: emptySet()
    }

    fun isFavorite(context: Context, item: MediaItem): Boolean {
        return load(context).contains(item.uri.toString())
    }

    fun toggle(context: Context, item: MediaItem): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = load(context).toMutableSet()
        val uri = item.uri.toString()
        val nowFavorite = if (current.contains(uri)) {
            current.remove(uri); false
        } else {
            current.add(uri); true
        }
        prefs.edit().putStringSet(KEY, current).apply()
        return nowFavorite
    }
}
