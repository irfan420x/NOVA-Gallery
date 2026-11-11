package com.nova.gallery.ui

import android.content.Intent
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import kotlinx.coroutines.launch

/**
 * Full-screen viewer: swipe left/right to change photos (HorizontalPager),
 * pinch-zoom for photos (swiping is locked while zoomed in), VideoView
 * playback for videos, share / favorite / delete / details.
 * The system back button closes the viewer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    items: List<MediaItem>,
    initialIndex: Int,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        pageCount = { items.size }
    )
    val currentItem = items.getOrNull(pagerState.currentPage)
    var isZoomed by remember { mutableStateOf(false) }
    var isFavorite by remember(currentItem?.uri) { mutableStateOf(currentItem?.isFavorite == true) }
    var showDetails by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    // When the page changes, any zoom is gone (each page owns its own zoom).
    LaunchedEffect(pagerState.currentPage) { isZoomed = false }

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

    fun doDelete(item: MediaItem) {
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
                title = { Text(currentItem?.displayName ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (currentItem != null) {
                        IconButton(onClick = {
                            isFavorite = com.nova.gallery.data.FavoriteStore.toggle(context, currentItem)
                            onStatus(if (isFavorite) "Added to favorites" else "Removed from favorites")
                        }) {
                            Icon(
                                if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                if (isFavorite) "Remove from favorites" else "Add to favorites"
                            )
                        }
                        IconButton(onClick = {
                            val intent = Intent.createChooser(MediaRepo.shareIntent(currentItem), "Share")
                            context.startActivity(intent)
                        }) { Icon(Icons.Filled.Share, "Share") }
                        IconButton(onClick = { showDetails = true }) { Icon(Icons.Filled.Info, "Details") }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, "Delete", tint = NovaDanger)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !isZoomed,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = items[page]
                if (item.isVideo) {
                    VideoPage(item)
                } else {
                    ZoomableImagePage(
                        item = item,
                        isCurrent = page == pagerState.currentPage,
                        onZoomChanged = { zoomed -> if (page == pagerState.currentPage) isZoomed = zoomed }
                    )
                }
            }
            if (pagerState.currentPage > 0) {
                IconButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                    modifier = Modifier.align(Alignment.CenterStart)
                        .background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
                ) { Icon(Icons.Filled.ChevronLeft, "Previous", tint = Color.White) }
            }
            if (pagerState.currentPage < items.size - 1) {
                IconButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                    modifier = Modifier.align(Alignment.CenterEnd)
                        .background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
                ) { Icon(Icons.Filled.ChevronRight, "Next", tint = Color.White) }
            }
            val current = currentItem
            if (current?.isVideo == true) {
                Text(
                    formatDuration(current.durationMs),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }

    val current = currentItem
    if (showDetails && current != null) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Details") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DetailRow("Name", current.displayName)
                    DetailRow("Type", current.mimeType.ifEmpty { if (current.isVideo) "Video" else "Image" })
                    DetailRow("Date", formatDate(current.dateTaken.takeIf { it > 0 } ?: (current.dateModified * 1000)))
                    DetailRow("Size", formatBytes(current.sizeBytes))
                    DetailRow("Resolution", "${current.width} × ${current.height}")
                    if (current.isVideo) DetailRow("Duration", formatDuration(current.durationMs))
                    DetailRow("Album", current.bucket)
                    DetailRow("Location", current.relativePath.ifEmpty { "—" })
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text("Close") }
            }
        )
    }

    if (showDeleteConfirm && current != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this ${if (current.isVideo) "video" else "photo"}?") },
            text = { Text(current.displayName) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    doDelete(current)
                }) { Text("Delete", color = NovaDanger) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun VideoPage(item: MediaItem) {
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImagePage(
    item: MediaItem,
    isCurrent: Boolean,
    onZoomChanged: (Boolean) -> Unit
) {
    var scale by remember(item.uri) { mutableFloatStateOf(1f) }
    var offset by remember(item.uri) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
        if (isCurrent) onZoomChanged(scale > 1f)
    }
    // v1.3: the swipe bug — transformable() consumed every single-finger
    // horizontal drag as a "pan", so the HorizontalPager never saw the swipe
    // and photos never changed. canPan gates panning to the zoomed state:
    // at 1x the pager gets the swipe; zoomed in, the image pans instead.
    AsyncImage(
        model = item.uri,
        contentDescription = item.displayName,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .graphicsLayer(
                scaleX = scale, scaleY = scale,
                translationX = offset.x, translationY = offset.y
            )
            .transformable(transformState, canPan = { scale > 1f })
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value)
    }
}
