package com.nova.gallery.data

import android.net.Uri

enum class MediaKind { IMAGE, VIDEO }

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val kind: MediaKind,
    val mimeType: String,
    val dateModified: Long,      // seconds since epoch
    val dateTaken: Long,         // milliseconds since epoch (0 if unknown)
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,        // video only, 0 for images
    val bucket: String,          // album / folder name
    val relativePath: String,
    val isFavorite: Boolean = false
) {
    val isVideo: Boolean get() = kind == MediaKind.VIDEO
}

data class Album(
    val name: String,
    val count: Int,
    val coverUri: Uri?,
    val totalSizeBytes: Long
)

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit++ }
    return if (value >= 100) "%.0f %s".format(value, units[unit])
    else "%.1f %s".format(value, units[unit])
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

fun formatDate(millis: Long): String {
    if (millis <= 0) return "Unknown date"
    val sdf = java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}

fun formatDateShort(millis: Long): String {
    if (millis <= 0) return "Unknown"
    val sdf = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}
