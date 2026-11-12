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
            // v1.6: Android 14 "Select photos and videos" grants only this
            // permission — the old check ignored it, so a user who had
            // granted partial access still saw the permission screen.
            val selected = if (Build.VERSION.SDK_INT >= 34) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else false
            images || videos || selected
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
    fun loadAll(context: Context, limit: Int = 10000): List<MediaItem> {
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
    fun loadAlbums(context: Context): List<Album> = loadAlbumsFrom(loadAll(context, 10000))

    /** Albums derived from an already-loaded list (one scan, not two). */
    fun loadAlbumsFrom(all: List<MediaItem>): List<Album> {
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
        // v1.3: escape LIKE wildcards so a literal % or _ in a file name is
        // searched for, not treated as a pattern.
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
        val args = arrayOf("%$escaped%")
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

    // ---------- v1.4: Trash (30-day recycle bin, Android 11+) ----------
    // Deleting a photo moves it to the system Trash instead of destroying
    // it — the system permanently deletes trashed items after 30 days.
    // On Android 10 and below there is no Trash API, so delete is permanent.

    /** True when the system Trash API is available (Android 11+). */
    fun hasTrash(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Move items to (or out of) the Trash. Returns the system confirmation
     * sender to launch, or null when it was done directly (API < 30 falls
     * back to a permanent delete for trash=true).
     */
    /** The outcome of a trash/restore request. The old nullable
     *  IntentSender was ambiguous: null meant BOTH "no Trash API (API < 30,
     *  already deleted)" AND "the trash request failed" — and the viewer
     *  treated a failure as "delete permanently", destroying the photo.
     *  v1.6: a failure is a failure, never a silent permanent delete. */
    sealed class TrashResult {
        class NeedsConfirmation(val sender: android.content.IntentSender) : TrashResult()
        object Done : TrashResult()
        object Failed : TrashResult()
    }

    fun trashItems(
        context: Context,
        items: List<MediaItem>,
        trash: Boolean
    ): TrashResult {
        if (items.isEmpty()) return TrashResult.Done
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                val sender = MediaStore.createTrashRequest(
                    context.contentResolver, items.map { it.uri }, trash
                ).intentSender
                TrashResult.NeedsConfirmation(sender)
            } catch (e: Exception) {
                ErrorLog.log(context, "Gallery", "Trash request failed", e)
                TrashResult.Failed
            }
        }
        // No Trash API below Android 11: trashing is a permanent delete.
        if (trash) items.forEach { delete(context, it) }
        return TrashResult.Done
    }

    /** Items currently in the Trash, newest first. */
    fun loadTrashed(context: Context): List<MediaItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val favorites = FavoriteStore.load(context)
        val out = ArrayList<MediaItem>()
        val bundle = android.os.Bundle().apply {
            // MATCH_ONLY = only trashed items. MATCH_INCLUDE (1) returns
            // the WHOLE library including trashed — the Trash tab would
            // list every photo and "Delete permanently" could destroy
            // photos the user never deleted.
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED,
                MediaStore.MATCH_ONLY)
        }
        // v1.6: each collection has its own try/catch — a failure in the
        // video query (e.g. partial permission covering only images) used
        // to discard the trashed images already collected and show an
        // empty Trash tab.
        for (collection in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            try {
                context.contentResolver.query(collection, PROJECTION, bundle, null)?.use { c ->
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
                        val uri = android.content.ContentUris.withAppendedId(collection, id)
                        out += MediaItem(
                            id = id, uri = uri,
                            displayName = c.getString(nameI) ?: "Untitled",
                            kind = if (mime.startsWith("video/")) MediaKind.VIDEO else MediaKind.IMAGE,
                            mimeType = mime,
                            dateModified = c.getLong(modI),
                            dateTaken = c.getLong(takenI),
                            sizeBytes = c.getLong(sizeI),
                            width = c.getInt(wI), height = c.getInt(hI),
                            durationMs = if (durI >= 0) c.getLong(durI) else 0L,
                            bucket = c.getString(bucketI) ?: "Unknown",
                            relativePath = c.getString(pathI) ?: "",
                            isFavorite = favorites.contains(uri.toString())
                        )
                    }
                }
            } catch (e: Exception) {
                ErrorLog.log(context, "Gallery", "Failed to load trash from one collection", e)
            }
        }
        return out.sortedByDescending { it.dateModified }
    }

    /** A MediaItem for a single content URI (e.g. opened from another app). */
    fun fromUri(context: Context, uri: android.net.Uri): MediaItem? {
        return try {
            var name = "Untitled"
            var mime = context.contentResolver.getType(uri) ?: ""
            var size = 0L
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val nameI = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeI = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeI = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (nameI >= 0) c.getString(nameI)?.let { name = it }
                    if (mimeI >= 0) c.getString(mimeI)?.let { mime = it }
                    if (sizeI >= 0) size = c.getLong(sizeI)
                }
            }
            MediaItem(
                id = uri.toString().hashCode().toLong(),
                uri = uri,
                displayName = name,
                kind = if (mime.startsWith("video/")) MediaKind.VIDEO else MediaKind.IMAGE,
                mimeType = mime,
                dateModified = System.currentTimeMillis() / 1000,
                dateTaken = 0L,
                sizeBytes = size,
                width = 0, height = 0,
                durationMs = 0L,
                bucket = "",
                relativePath = "",
                isFavorite = false
            )
        } catch (e: Exception) {
            ErrorLog.log(context, "Gallery", "Failed to open shared media", e)
            null
        }
    }

    /** Photos from this day in past years ("On this day"). */
    fun loadMemories(context: Context): List<MediaItem> = loadMemoriesFrom(loadAll(context, 10000))

    /** Memories derived from an already-loaded list (one scan, not two). */
    fun loadMemoriesFrom(all: List<MediaItem>): List<MediaItem> {
        if (all.isEmpty()) return emptyList()
        val cal = java.util.Calendar.getInstance()
        val month = cal.get(java.util.Calendar.MONTH)
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val year = cal.get(java.util.Calendar.YEAR)
        return all.filter { item ->
            val taken = item.dateTaken.takeIf { it > 0 } ?: (item.dateModified * 1000)
            val c = java.util.Calendar.getInstance().apply { timeInMillis = taken }
            c.get(java.util.Calendar.MONTH) == month &&
                c.get(java.util.Calendar.DAY_OF_MONTH) == day &&
                c.get(java.util.Calendar.YEAR) < year
        }.sortedByDescending { it.dateTaken.takeIf { t -> t > 0 } ?: (it.dateModified * 1000) }
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
        // v1.5: a mixed image+video selection is "*/*" — labelling it
        // "video/*" hid image-only targets from the chooser.
        val hasImages = items.any { !it.isVideo }
        val hasVideos = items.any { it.isVideo }
        val type = when {
            hasImages && hasVideos -> "*/*"
            hasVideos -> "video/*"
            else -> "image/*"
        }
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
