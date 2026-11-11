package com.nova.gallery.ui

import android.content.Intent
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.nova.gallery.data.MediaItem
import com.nova.gallery.data.formatBytes
import com.nova.gallery.data.formatDate
import com.nova.gallery.data.formatDuration
import com.nova.gallery.data.MediaRepo

/**
 * Full-screen viewer: pinch-zoom for photos, VideoView playback for videos,
 * swipe handled by the caller (prev/next buttons here), share / favorite /
 * delete / details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    item: MediaItem,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    var isFavorite by remember(item.uri) { mutableStateOf(item.isFavorite) }
    var showDetails by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var scale by remember(item.uri) { mutableFloatStateOf(1f) }
    var offset by remember(item.uri) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }

    val deleteLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            onStatus("Deleted")
            onDeleted()
        } else {
            onStatus("Delete cancelled")
        }
    }

    fun doDelete() {
        when (val result = MediaRepo.delete(context, item)) {
            is MediaRepo.DeleteResult.Deleted -> {
                onStatus("Deleted")
                onDeleted()
            }
            is MediaRepo.DeleteResult.NeedsConfirmation -> {
                val sender = result.exception.userAction.actionIntent.intentSender
                deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                )
            }
            is MediaRepo.DeleteResult.Failed -> onStatus("Couldn't delete: ${result.error.message}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.displayName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        isFavorite = com.nova.gallery.data.FavoriteStore.toggle(context, item)
                        onStatus(if (isFavorite) "Added to favorites" else "Removed from favorites")
                    }) {
                        Icon(
                            if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            if (isFavorite) "Remove from favorites" else "Add to favorites"
                        )
                    }
                    IconButton(onClick = {
                        val intent = Intent.createChooser(MediaRepo.shareIntent(item), "Share")
                        context.startActivity(intent)
                    }) { Icon(Icons.Filled.Share, "Share") }
                    IconButton(onClick = { showDetails = true }) { Icon(Icons.Filled.Info, "Details") }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, "Delete", tint = NovaDanger)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (item.isVideo) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(item.uri)
                            setMediaController(MediaController(ctx))
                            setOnPreparedListener { it.isLooping = false; start() }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Text(
                    formatDuration(item.durationMs),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            } else {
                AsyncImage(
                    model = item.uri,
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale, scaleY = scale,
                            translationX = offset.x, translationY = offset.y
                        )
                        .transformable(transformState)
                )
            }
            if (hasPrev) {
                IconButton(
                    onClick = onPrev,
                    modifier = Modifier.align(Alignment.CenterStart)
                        .background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
                ) { Icon(Icons.Filled.ChevronLeft, "Previous", tint = Color.White) }
            }
            if (hasNext) {
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.align(Alignment.CenterEnd)
                        .background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
                ) { Icon(Icons.Filled.ChevronRight, "Next", tint = Color.White) }
            }
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Details") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DetailRow("Name", item.displayName)
                    DetailRow("Type", item.mimeType.ifEmpty { if (item.isVideo) "Video" else "Image" })
                    DetailRow("Date", formatDate(item.dateTaken.takeIf { it > 0 } ?: (item.dateModified * 1000)))
                    DetailRow("Size", formatBytes(item.sizeBytes))
                    DetailRow("Resolution", "${item.width} × ${item.height}")
                    if (item.isVideo) DetailRow("Duration", formatDuration(item.durationMs))
                    DetailRow("Album", item.bucket)
                    DetailRow("Location", item.relativePath.ifEmpty { "—" })
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text("Close") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this ${if (item.isVideo) "video" else "photo"}?") },
            text = { Text(item.displayName) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    doDelete()
                }) { Text("Delete", color = NovaDanger) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value)
    }
}
