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

    // Visible for unit tests (same module when compiled together).
    internal fun queryArgs(
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int
    ): android.os.Bundle {
        return android.os.Bundle().apply {
            if (selection != null) {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                if (selectionArgs != null) {
                    putStringArray(
                        android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        selectionArgs
                    )
                }
            }
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )
            // v1.7: the limit is real now — the old code passed a limit
            // param that readItems ignored and loaded the whole table.
            if (limit > 0) putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
        }
    }

    /** One row of a MediaStore cursor, as a MediaItem. */
    private fun cursorToItem(
        c: android.database.Cursor,
        itemUri: Uri,
        favorites: Set<String>
    ): MediaItem {
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
        val mime = c.getString(mimeI) ?: ""
        return MediaItem(
            id = c.getLong(idI),
            uri = itemUri,
            displayName = c.getString(nameI) ?: "Untitled",
            kind = if (mime.startsWith("video/")) MediaKind.VIDEO else MediaKind.IMAGE,
            mimeType = mime,
            dateModified = c.getLong(modI),
            dateTaken = c.getLong(takenI),
            sizeBytes = c.getLong(sizeI),
            width = c.getInt(wI),
            height = c.getInt(hI),
            durationMs = if (durI >= 0) c.getLong(durI) else 0L,
            bucket = c.getString(bucketI) ?: "Unknown",
            relativePath = c.getString(pathI) ?: "",
            isFavorite = favorites.contains(itemUri.toString())
        )
    }

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
            // v1.7: Bundle query args — the LIMIT is applied by the
            // provider now (the old string sort order could not carry one:
            // MediaStore rejects the LIMIT token in a sort string).
            context.contentResolver.query(
                collection, PROJECTION, queryArgs(selection, selectionArgs, limit), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    out += cursorToItem(c, uri, favorites)
                }
            }
        } catch (e: Exception) {
            ErrorLog.log(context, "Gallery", "Failed to load media", e)
            return emptyList()
        }
        return out
    }

    /**
     * Merge the per-table pages into one global page. Images and Videos
     * are separate tables, so each is queried with limit (offset + limit)
     * and the merged result is dropped/taken: a global rank below
     * offset + limit implies a per-table rank below offset + limit, so
     * no item in the page can be missed. Pure function — unit-tested.
     * limit <= 0 means "no limit" (full list).
     */
    fun mergePages(
        images: List<MediaItem>,
        videos: List<MediaItem>,
        limit: Int,
        offset: Int
    ): List<MediaItem> {
        val merged = (images + videos).sortedByDescending { it.dateModified }
        return if (limit > 0) merged.drop(offset).take(limit) else merged
    }

    /**
     * All photos and videos, newest first — one page of a merged list.
     * limit <= 0 means "no limit" (full scan, used by Memories).
     */
    fun loadAll(context: Context, limit: Int = 500, offset: Int = 0): List<MediaItem> {
        val perTable = if (limit > 0) offset + limit else 0
        val images = readItems(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null, perTable)
        val videos = readItems(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, null, perTable)
        return mergePages(images, videos, limit, offset)
    }

    /** Total number of photos + videos (non-trashed), for honest counts. */
    fun countAll(context: Context): Int {
        if (!hasMediaPermission(context)) return 0
        var total = 0
        for (collection in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            try {
                context.contentResolver.query(
                    collection, arrayOf(MediaStore.MediaColumns._ID), null, null, null
                )?.use { c -> total += c.count }
            } catch (e: Exception) {
                ErrorLog.log(context, "Gallery", "Failed to count media", e)
            }
        }
        return total
    }

    /**
     * The selection for one album. "Unknown" is the label for a null
     * BUCKET_DISPLAY_NAME — querying `= 'Unknown'` matches nothing, so
     * the album used to open empty. IS NULL matches the real rows.
     */
    fun albumSelection(bucket: String): Pair<String, Array<String>?> {
        return if (bucket == "Unknown") {
            "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} IS NULL" to null
        } else {
            "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ?" to arrayOf(bucket)
        }
    }

    /** Media in one album (bucket), newest first — one page. */
    fun loadAlbum(context: Context, bucket: String, limit: Int = 500, offset: Int = 0): List<MediaItem> {
        val (sel, args) = albumSelection(bucket)
        val perTable = if (limit > 0) offset + limit else 0
        val images = readItems(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, sel, args, perTable)
        val videos = readItems(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, sel, args, perTable)
        return mergePages(images, videos, limit, offset)
    }

    /** Number of items in one album. */
    fun countAlbum(context: Context, bucket: String): Int {
        if (!hasMediaPermission(context)) return 0
        val (sel, args) = albumSelection(bucket)
        var total = 0
        for (collection in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            try {
                context.contentResolver.query(
                    collection, arrayOf(MediaStore.MediaColumns._ID), sel, args, null
                )?.use { c -> total += c.count }
            } catch (e: Exception) {
                ErrorLog.log(context, "Gallery", "Failed to count album", e)
            }
        }
        return total
    }

    /**
     * Albums with exact counts and covers, biggest first — from one
     * lightweight scan (id + bucket + date + size only), NOT from the
     * photo list. The old derivation capped album counts at the photo
     * list's 10,000 cap and hid albums whose photos were all older.
     */
    fun loadAlbums(context: Context): List<Album> {
        if (!hasMediaPermission(context)) return emptyList()
        val rows = ArrayList<AlbumScanRow>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE
        )
        for (collection in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            try {
                context.contentResolver.query(
                    collection, projection, queryArgs(null, null, 0), null
                )?.use { c ->
                    val idI = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val bucketI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    val modI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val sizeI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (c.moveToNext()) {
                        val id = c.getLong(idI)
                        rows += AlbumScanRow(
                            uri = android.content.ContentUris.withAppendedId(collection, id),
                            bucket = c.getString(bucketI) ?: "Unknown",
                            dateModified = c.getLong(modI),
                            sizeBytes = c.getLong(sizeI)
                        )
                    }
                }
            } catch (e: Exception) {
                ErrorLog.log(context, "Gallery", "Failed to load albums", e)
            }
        }
        return albumsFromRows(rows)
    }

    /** One lightweight album-scan row (no full MediaItem allocated). */
    data class AlbumScanRow(
        val uri: Uri,
        val bucket: String,
        val dateModified: Long,
        val sizeBytes: Long
    )

    /** Group album-scan rows into Albums: exact count, total size, cover
     *  = the newest item in the bucket. Pure function — unit-tested. */
    fun albumsFromRows(rows: List<AlbumScanRow>): List<Album> {
        if (rows.isEmpty()) return emptyList()
        return rows.groupBy { it.bucket }.map { (name, items) ->
            Album(
                name = name,
                count = items.size,
                coverUri = items.maxByOrNull { it.dateModified }?.uri,
                totalSizeBytes = items.sumOf { it.sizeBytes }
            )
        }.sortedByDescending { it.count }
    }

    /**
     * Favorites only, newest first — looked up one row at a time from
     * the stored URIs, so a favorite is found even when it is older than
     * any list cap. A URI that no longer resolves is pruned from the
     * store (the photo was deleted elsewhere); a query failure is
     * transient, so that favorite is kept.
     */
    fun loadFavorites(context: Context): List<MediaItem> {
        if (!hasMediaPermission(context)) return emptyList()
        val stored = FavoriteStore.load(context)
        if (stored.isEmpty()) return emptyList()
        val favorites = stored
        val out = ArrayList<MediaItem>()
        val stale = ArrayList<String>()
        for (uriStr in stored) {
            val uri = Uri.parse(uriStr)
            val cursor = try {
                context.contentResolver.query(uri, PROJECTION, null, null, null)
            } catch (e: Exception) {
                ErrorLog.log(context, "Gallery", "Failed to load favorite $uriStr", e)
                null
            }
            if (cursor == null) continue // transient failure — keep it
            cursor.use { c ->
                if (c.moveToFirst()) out += cursorToItem(c, uri, favorites)
                else stale += uriStr // row is gone — the photo was deleted
            }
        }
        if (stale.isNotEmpty()) FavoriteStore.prune(context, stale)
        return out.sortedByDescending { it.dateModified }
    }

    /** Search by file name (case-insensitive substring) — one page. */
    fun search(context: Context, query: String, limit: Int = 500, offset: Int = 0): List<MediaItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val escaped = escapeLikeQuery(q)
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
        val args = arrayOf("%$escaped%")
        val perTable = if (limit > 0) offset + limit else 0
        val images = readItems(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, sel, args, perTable)
        val videos = readItems(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, sel, args, perTable)
        return mergePages(images, videos, limit, offset)
    }

    /**
     * Escape a LIKE pattern so a literal %, _ or \ in a file name is
     * searched for, not treated as a wildcard. Pure function —
     * unit-tested.
     */
    fun escapeLikeQuery(q: String): String {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
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
            // v1.7: 0 rows does not always mean "failed" — the file may
            // already be gone (deleted by another app, stale Trash entry).
            // Deleting an already-deleted file used to report "Couldn't
            // delete" even though the goal state was already true.
            else if (!mediaExists(context, item.uri)) DeleteResult.Deleted
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

    /** True when the MediaStore row for this URI still exists. */
    private fun mediaExists(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null
            )?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Delete several items at once. On Android 11+ this uses one system
     * confirmation (MediaStore.createDeleteRequest) for the whole batch.
     * The result reports exactly what happened: which items were deleted,
     * which failed, which were never attempted because an earlier item
     * needed confirmation (they stay for a second pass), and the first
     * item that needs confirmation, if any.
     * v1.7: the old fallback broke out of the loop at the first
     * NeedsConfirmation, skipped the rest, and the caller still reported
     * "Deleted permanently".
     */
    fun deleteMultiple(
        context: Context,
        items: List<MediaItem>,
        onResult: (
            deleted: List<MediaItem>,
            failed: List<MediaItem>,
            remaining: List<MediaItem>,
            confirmItem: MediaItem?,
            needsConfirmation: android.content.IntentSender?
        ) -> Unit
    ) {
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = items.map { it.uri }
                val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
                onResult(emptyList(), emptyList(), emptyList(), null, request.intentSender)
                return
            } catch (e: Exception) {
                ErrorLog.log(context, "Gallery", "Batch delete request failed", e)
            }
        }
        val deleted = ArrayList<MediaItem>()
        val failed = ArrayList<MediaItem>()
        val remaining = ArrayList<MediaItem>()
        var confirmItem: MediaItem? = null
        var sender: android.content.IntentSender? = null
        for (item in items) {
            when (val r = delete(context, item)) {
                is DeleteResult.Deleted -> deleted += item
                is DeleteResult.NeedsConfirmation -> {
                    if (confirmItem == null) {
                        confirmItem = item
                        sender = r.exception.userAction.actionIntent.intentSender
                    } else {
                        // One system dialog confirms one item; the rest
                        // wait for the next pass instead of being dropped.
                        remaining += item
                    }
                }
                is DeleteResult.Failed -> failed += item
            }
        }
        onResult(deleted, failed, remaining, confirmItem, sender)
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
        if (trash) {
            var firstSender: android.content.IntentSender? = null
            for (item in items) {
                when (val r = delete(context, item)) {
                    is DeleteResult.NeedsConfirmation -> {
                        // v1.7: a delete that needs confirmation is not a
                        // completed delete — report it instead of claiming
                        // success for a photo that is still there.
                        if (firstSender == null) {
                            firstSender = r.exception.userAction.actionIntent.intentSender
                        }
                    }
                    else -> { }
                }
            }
            if (firstSender != null) return TrashResult.NeedsConfirmation(firstSender)
        }
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
                    while (c.moveToNext()) {
                        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val uri = android.content.ContentUris.withAppendedId(collection, id)
                        out += cursorToItem(c, uri, favorites)
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
            var width = 0
            var height = 0
            var dateTaken = 0L
            var dateModified = 0L
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.WIDTH,
                    MediaStore.MediaColumns.HEIGHT,
                    MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.DATE_MODIFIED
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val nameI = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeI = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeI = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val wI = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                    val hI = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                    val takenI = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    val modI = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    if (nameI >= 0) c.getString(nameI)?.let { name = it }
                    if (mimeI >= 0) c.getString(mimeI)?.let { mime = it }
                    if (sizeI >= 0) size = c.getLong(sizeI)
                    if (wI >= 0) width = c.getInt(wI)
                    if (hI >= 0) height = c.getInt(hI)
                    if (takenI >= 0) dateTaken = c.getLong(takenI)
                    // DATE_MODIFIED is seconds; DATE_TAKEN is milliseconds.
                    if (modI >= 0) dateModified = c.getLong(modI)
                }
            }
            MediaItem(
                id = uri.toString().hashCode().toLong(),
                uri = uri,
                displayName = name,
                kind = if (mime.startsWith("video/")) MediaKind.VIDEO else MediaKind.IMAGE,
                mimeType = mime,
                // v1.7: unknown dates stay 0 ("Unknown date") — the old code
                // stamped "now", so a shared photo showed today's date and
                // a fake "0 × 0" resolution.
                dateModified = dateModified,
                dateTaken = dateTaken,
                sizeBytes = size,
                width = width, height = height,
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
    fun loadMemories(context: Context): List<MediaItem> {
        // Full scan (no limit): memories are old by definition, and the
        // old 10,000 cap hid every memory older than the newest 10,000.
        return loadMemoriesFrom(loadAll(context, limit = 0))
    }

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
            // ClipData carries the URI-permission grant to the target app —
            // the flag alone does not reach it reliably on every device.
            clipData = android.content.ClipData.newUri(
                null, item.displayName, item.uri
            )
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
            // v1.7: ClipData carries the URI-permission grant for every
            // URI in the batch — without it some targets receive the
            // chooser but cannot read the files.
            clipData = android.content.ClipData.newUri(null, "Shared media", uris[0])
                .apply {
                    for (i in 1 until uris.size) addItem(android.content.ClipData.Item(uris[i]))
                }
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

    /** Drop URIs that no longer resolve (their photos were deleted). */
    fun prune(context: Context, staleUris: List<String>) {
        if (staleUris.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = load(context).toMutableSet()
        if (current.removeAll(staleUris.toSet())) {
            prefs.edit().putStringSet(KEY, current).apply()
        }
    }
}
