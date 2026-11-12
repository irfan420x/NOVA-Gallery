package com.nova.gallery.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.nova.gallery.data.Album
import com.nova.gallery.data.ErrorLog
import com.nova.gallery.data.FavoriteStore
import com.nova.gallery.data.MediaItem
import com.nova.gallery.data.MediaRepo
import com.nova.gallery.data.formatBytes
import com.nova.gallery.data.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GalleryTab { ALBUMS, PHOTOS, FAVORITES, TRASH }

// v1.7: one page of the photo grid / album view. The old code loaded the
// whole library (capped at 10,000) and hid everything older.
private const val PAGE_SIZE = 500

// What a permanent-delete confirmation was for, so the result message can
// be honest about how many items actually went away.
private sealed interface PendingDelete {
    data class Batch(val total: Int) : PendingDelete
    data class Fallback(
        val deletedUris: List<String>,
        val failed: Int,
        val remaining: Int,
        val confirmUri: String
    ) : PendingDelete
}

private fun mediaPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= 33) {
        // v1.6: include READ_MEDIA_VISUAL_USER_SELECTED on API 34+ so the
        // system offers "Select photos and videos" and the app recognizes it.
        if (Build.VERSION.SDK_INT >= 34) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        }
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                ErrorLog.log(applicationContext, "CRASH", "Uncaught exception on ${thread.name}", e)
            } catch (_: Exception) { }
            defaultHandler?.uncaughtException(thread, e)
        }
        setContent { NovaGalleryApp(viewUri = intent?.data) }
    }
}

private fun loadDarkTheme(context: Context): Boolean =
    context.getSharedPreferences("nova_gallery", Context.MODE_PRIVATE).getBoolean("ui_dark_theme", true)

private fun saveDarkTheme(context: Context, dark: Boolean) {
    context.getSharedPreferences("nova_gallery", Context.MODE_PRIVATE)
        .edit().putBoolean("ui_dark_theme", dark).apply()
}

private fun loadGridColumns(context: Context): Int =
    context.getSharedPreferences("nova_gallery", Context.MODE_PRIVATE).getInt("grid_columns", 3)

private fun saveGridColumns(context: Context, columns: Int) {
    context.getSharedPreferences("nova_gallery", Context.MODE_PRIVATE)
        .edit().putInt("grid_columns", columns).apply()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NovaGalleryApp(viewUri: android.net.Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v1.7: the UI state survives rotation — the old plain remember() lost
    // the tab, the search, the open album, the selection and the viewer on
    // every rotate. (The photo lists themselves are reloaded, not saved.)
    var tab by rememberSaveable { mutableStateOf(GalleryTab.ALBUMS) }
    var darkTheme by rememberSaveable { mutableStateOf(loadDarkTheme(context)) }
    var gridColumns by rememberSaveable { mutableStateOf(loadGridColumns(context)) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var photos by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var totalPhotos by remember { mutableStateOf(0) }
    var photosHasMore by remember { mutableStateOf(false) }
    var photosLoadingMore by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var trashed by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var memories by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var openAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    var albumPhotos by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var albumTotal by remember { mutableStateOf(0) }
    var albumHasMore by remember { mutableStateOf(false) }
    var albumLoading by remember { mutableStateOf(false) }
    var albumLoadingMore by remember { mutableStateOf(false) }
    var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var viewerList by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var viewerIsTrash by rememberSaveable { mutableStateOf(false) }
    var viewerIsForeign by rememberSaveable { mutableStateOf(false) }
    var savedViewerUri by rememberSaveable { mutableStateOf<String?>(null) }
    var viewUriHandled by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var hasLoadedOnce by remember { mutableStateOf(false) }
    var reloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusKey by remember { mutableStateOf(0) }
    var showErrorLog by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(
        stateSaver = Saver<Set<String>, List<String>>(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var errorCount by remember { mutableStateOf(ErrorLog.count(context)) }
    var permissionsGranted by remember {
        mutableStateOf(MediaRepo.hasMediaPermission(context))
    }
    var skipPermissions by rememberSaveable { mutableStateOf(false) }
    var viewerRestorePending by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    fun onStatus(msg: String) {
        status = msg
        // v1.7: bump the key on every message — the old LaunchedEffect on
        // the message itself never restarted when the same text was shown
        // twice, so the second message could vanish instantly.
        statusKey++
    }

    // v1.7: paged reload. The Photos tab used to load the whole library
    // capped at 10,000 — everything older was invisible. Now it loads one
    // page (500), with exact totals from count queries, and the albums,
    // favorites and memories come from their own exact queries instead of
    // being derived from the capped list. A reload keeps the depth the
    // user had already scrolled to instead of jumping back to page 1.
    fun reload() {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            if (!hasLoadedOnce) loading = true
            val depth = photos.size.coerceAtLeast(PAGE_SIZE)
            val page = withContext(Dispatchers.IO) { MediaRepo.loadAll(context, depth, 0) }
            val total = withContext(Dispatchers.IO) { MediaRepo.countAll(context) }
            val alb = withContext(Dispatchers.IO) { MediaRepo.loadAlbums(context) }
            val fav = withContext(Dispatchers.IO) { MediaRepo.loadFavorites(context) }
            val mem = withContext(Dispatchers.IO) { MediaRepo.loadMemories(context) }
            val tr = withContext(Dispatchers.IO) { MediaRepo.loadTrashed(context) }
            photos = page
            totalPhotos = total
            photosHasMore = page.size < total
            albums = alb
            favorites = fav
            memories = mem
            trashed = tr
            // v1.6: search results go stale after a delete in the viewer —
            // re-run the current search so deleted items disappear.
            if (query.isNotBlank()) {
                searchResults = withContext(Dispatchers.IO) { MediaRepo.search(context, query) }
            }
            errorCount = ErrorLog.count(context)
            hasLoadedOnce = true
            loading = false
        }
    }

    fun reloadAlbum(name: String) {
        scope.launch {
            // v1.7: the album view used to flash "This album is empty"
            // while the new album was still loading — it has its own
            // loading state now, and the old photos are cleared first.
            albumLoading = true
            albumPhotos = emptyList()
            albumHasMore = false
            val page = withContext(Dispatchers.IO) { MediaRepo.loadAlbum(context, name, PAGE_SIZE, 0) }
            val total = withContext(Dispatchers.IO) { MediaRepo.countAlbum(context, name) }
            albumPhotos = page
            albumTotal = total
            albumHasMore = page.size < total
            albumLoading = false
        }
    }

    fun loadMorePhotos() {
        if (photosLoadingMore || !photosHasMore) return
        photosLoadingMore = true
        scope.launch {
            val next = withContext(Dispatchers.IO) { MediaRepo.loadAll(context, PAGE_SIZE, photos.size) }
            // Dedup by URI: a photo taken between page loads can shift
            // the window and appear twice.
            photos = (photos + next).distinctBy { it.uri }
            photosHasMore = next.size >= PAGE_SIZE
            photosLoadingMore = false
        }
    }

    fun loadMoreAlbum(name: String) {
        if (albumLoadingMore || !albumHasMore) return
        albumLoadingMore = true
        scope.launch {
            val next = withContext(Dispatchers.IO) { MediaRepo.loadAlbum(context, name, PAGE_SIZE, albumPhotos.size) }
            albumPhotos = (albumPhotos + next).distinctBy { it.uri }
            albumHasMore = next.size >= PAGE_SIZE
            albumLoadingMore = false
        }
    }

    // v1.5: cancel the previous search before starting a new one — a slow
    // earlier query used to finish after a later one and overwrite the
    // results with stale ones.
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun runSearch(q: String) {
        searchJob?.cancel()
        searchJob = scope.launch {
            // v1.7: try/finally — a cancelled search used to leave the
            // spinner flag set and the results stuck on a spinner.
            try {
                searching = true
                searchResults = withContext(Dispatchers.IO) { MediaRepo.search(context, q) }
            } finally {
                searching = false
            }
        }
    }

    // v1.6: always reload on launch — even without permission, so the
    // empty states render instead of an infinite spinner (the old code
    // only reloaded when permission was already granted, so "Continue
    // without it" left loading=true forever).
    LaunchedEffect(Unit) { reload() }

    // v1.5: "Open with Gallery" — an incoming VIEW intent opens the viewer
    // with that one photo/video (the sender grants URI permission).
    // v1.7: viewUriHandled — without it, rotating the phone after closing
    // the viewer reopened it, because the intent never changes.
    LaunchedEffect(viewUri) {
        if (!viewUriHandled) {
            viewUriHandled = true
            viewUri?.let { uri ->
                val item = withContext(Dispatchers.IO) { MediaRepo.fromUri(context, uri) }
                if (item != null) {
                    viewerList = listOf(item)
                    viewerIndex = 0
                    viewerIsTrash = false
                    viewerIsForeign = true
                    savedViewerUri = item.uri.toString()
                }
            }
        }
    }

    // v1.7: reopen the viewer after a rotation — the photo list itself is
    // reloaded, so the viewer item is found again by URI in whichever
    // list holds it. If it is gone (deleted elsewhere), give up quietly.
    // The viewer "needs restore" when a URI was saved but the current list
    // cannot resolve it — after a rotation the saved index survives but
    // the list does not, so checking viewerIndex alone is not enough.
    LaunchedEffect(loading, photos, trashed, favorites, albumPhotos, searchResults) {
        val needsRestore = savedViewerUri != null &&
            viewerList.getOrNull(viewerIndex ?: -1) == null
        if (!loading && needsRestore) {
            val uri = savedViewerUri
            val candidates = listOf(
                photos to false, albumPhotos to false, favorites to false,
                trashed to true, searchResults to false
            )
            var restored = false
            for ((list, isTrash) in candidates) {
                val idx = list.indexOfFirst { it.uri.toString() == uri }
                if (idx >= 0) {
                    viewerList = list
                    viewerIndex = idx
                    viewerIsTrash = isTrash
                    restored = true
                    break
                }
            }
            // A photo opened from another app is in no library list —
            // rebuild it from its URI so it survives a rotation too.
            if (!restored && viewerIsForeign && uri != null) {
                val item = withContext(Dispatchers.IO) {
                    MediaRepo.fromUri(context, Uri.parse(uri))
                }
                if (item != null) {
                    viewerList = listOf(item)
                    viewerIndex = 0
                    viewerIsTrash = false
                    restored = true
                }
            }
            if (!restored) savedViewerUri = null
        }
    }

    // v1.5: refresh when the app comes back to the foreground — a photo
    // taken with the camera while Gallery was open now appears.
    // v1.6: re-check the permission first (granting it in system Settings
    // used to go unnoticed until a restart), and refresh the open album
    // too — it used to stay stale until the user left and re-entered it.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permissionsGranted = MediaRepo.hasMediaPermission(context)
                reload()
                openAlbum?.let { reloadAlbum(it) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(statusKey) {
        if (status != null) { kotlinx.coroutines.delay(3000); status = null }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = mediaPermissions().any { results[it] == true }
        if (permissionsGranted) reload()
    }

    // v1.7: one place to ask for access. If the user denied permanently
    // ("Don't ask again"), the system dialog never appears, so go to the
    // app's Settings page instead of showing a dead button.
    fun isPermanentlyDenied(): Boolean {
        val activity = context as? android.app.Activity ?: return false
        if (MediaRepo.hasMediaPermission(context)) return false
        val asked = context.getSharedPreferences("nova_gallery", Context.MODE_PRIVATE)
            .getBoolean("permission_asked", false)
        if (!asked) return false
        return mediaPermissions().none { perm ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
        }
    }

    fun grantAccess() {
        if (isPermanentlyDenied()) {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } else {
            context.getSharedPreferences("nova_gallery", Context.MODE_PRIVATE)
                .edit().putBoolean("permission_asked", true).apply()
            permissionLauncher.launch(mediaPermissions())
        }
    }

    // v1.4: deleting moves photos to the Trash (Android 11+), so they can
    // be restored for 30 days. The system confirmation covers trash,
    // restore and permanent delete alike.
    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // v1.7: below Android 11 there is no Trash — the delete was
            // permanent, so say so instead of "Moved to Trash".
            onStatus(if (MediaRepo.hasTrash(context)) "Moved to Trash" else "Deleted")
            selected = emptySet()
            reload()
            openAlbum?.let { reloadAlbum(it) }
        } else {
            onStatus("Cancelled")
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            onStatus("Restored")
            selected = emptySet()
            // v1.7: the viewer only closes when the restore came from the
            // viewer — the old code closed it before the user had even
            // confirmed, and a cancel left the viewer gone.
            if (viewerRestorePending) {
                viewerRestorePending = false
                viewerIndex = null
                savedViewerUri = null
            }
            reload()
        } else {
            viewerRestorePending = false
            onStatus("Cancelled")
        }
    }
    // System confirmation for permanent deletes.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            when (val p = pendingDelete) {
                is PendingDelete.Batch -> {
                    onStatus("Deleted ${p.total} item${if (p.total > 1) "s" else ""} permanently")
                    selected = emptySet()
                }
                is PendingDelete.Fallback -> {
                    val total = p.deletedUris.size + 1
                    selected = selected - (p.deletedUris + p.confirmUri).toSet()
                    val parts = StringBuilder("Deleted $total item${if (total > 1) "s" else ""} permanently")
                    if (p.failed > 0) parts.append(" — ${p.failed} couldn't be deleted")
                    if (p.remaining > 0) parts.append(" — ${p.remaining} still selected, delete again to continue")
                    onStatus(parts.toString())
                }
                null -> onStatus("Deleted permanently")
            }
            pendingDelete = null
            reload()
            openAlbum?.let { reloadAlbum(it) }
        } else {
            pendingDelete = null
            onStatus("Delete cancelled")
        }
    }

    // v1.4: delete now means "move to Trash" (restorable for 30 days).
    fun deleteSelected() {
        // v1.3: selection is keyed by content URI, not the MediaStore row id —
        // Images and Videos are separate tables, so an image and a video can
        // share the same _ID and the old Set<Long> treated them as one item.
        val items = (photos + albumPhotos + searchResults + trashed)
            .filter { selected.contains(it.uri.toString()) }
            .distinctBy { it.uri }
        if (items.isEmpty()) return
        when (val result = MediaRepo.trashItems(context, items, true)) {
            is MediaRepo.TrashResult.NeedsConfirmation -> {
                trashLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(result.sender).build()
                )
            }
            MediaRepo.TrashResult.Done -> {
                // Below Android 11 there is no Trash API — the delete was permanent.
                onStatus("Deleted")
                selected = emptySet()
                reload()
                openAlbum?.let { reloadAlbum(it) }
            }
            MediaRepo.TrashResult.Failed -> {
                // v1.6: a failed trash request must NOT fall back to a
                // permanent delete — the photo stays, with an honest error.
                onStatus("Couldn't move to Trash — try again")
            }
        }
    }

    fun restoreSelected() {
        val items = trashed.filter { selected.contains(it.uri.toString()) }
        if (items.isEmpty()) return
        when (val result = MediaRepo.trashItems(context, items, false)) {
            is MediaRepo.TrashResult.NeedsConfirmation -> {
                restoreLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(result.sender).build()
                )
            }
            MediaRepo.TrashResult.Done -> {
                onStatus("Restored")
                selected = emptySet()
                reload()
            }
            MediaRepo.TrashResult.Failed -> onStatus("Couldn't restore — try again")
        }
    }

    fun deletePermanentlySelected() {
        val items = trashed.filter { selected.contains(it.uri.toString()) }
        if (items.isEmpty()) return
        MediaRepo.deleteMultiple(context, items) { deleted, failed, remaining, confirmItem, sender ->
            if (sender != null) {
                pendingDelete = if (confirmItem != null) {
                    PendingDelete.Fallback(
                        deletedUris = deleted.map { it.uri.toString() },
                        failed = failed.size,
                        remaining = remaining.size,
                        confirmUri = confirmItem.uri.toString()
                    )
                } else {
                    // One system dialog covers the whole batch.
                    PendingDelete.Batch(items.size)
                }
                deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                )
            } else {
                // No confirmation needed: report exactly what happened
                // and keep the ones that failed selected.
                if (deleted.isNotEmpty()) {
                    selected = selected - deleted.map { it.uri.toString() }.toSet()
                }
                val total = deleted.size
                onStatus(
                    when {
                        total == 0 && failed.isNotEmpty() -> "Couldn't delete"
                        failed.isEmpty() -> "Deleted $total item${if (total > 1) "s" else ""}"
                        else -> "Deleted $total of ${total + failed.size} items — ${failed.size} couldn't be deleted"
                    }
                )
                reload()
            }
        }
    }

    fun openViewer(list: List<MediaItem>, index: Int, isTrash: Boolean = false) {
        viewerList = list
        viewerIndex = index
        viewerIsTrash = isTrash
        viewerIsForeign = false
        savedViewerUri = list.getOrNull(index)?.uri?.toString()
    }

    val viewerItem = viewerIndex?.let { viewerList.getOrNull(it) }

    NovaTheme(darkTheme = darkTheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                showErrorLog -> {
                    BackHandler { showErrorLog = false; errorCount = ErrorLog.count(context) }
                    ErrorLogScreen(
                        onBack = { showErrorLog = false; errorCount = ErrorLog.count(context) },
                        onCleared = { errorCount = 0 }
                    )
                }
                viewerItem != null -> ViewerScreen(
                    items = viewerList,
                    initialIndex = viewerIndex ?: 0,
                    isTrash = viewerIsTrash,
                    isForeign = viewerIsForeign,
                    onRestore = if (viewerIsTrash) {
                        {
                            val item = viewerItem
                            if (item != null) {
                                when (val result = MediaRepo.trashItems(context, listOf(item), false)) {
                                    is MediaRepo.TrashResult.NeedsConfirmation -> {
                                        // v1.7: wait for the confirmation —
                                        // the old code closed the viewer and
                                        // reloaded before the user answered,
                                        // so a cancel left the viewer gone.
                                        viewerRestorePending = true
                                        restoreLauncher.launch(
                                            androidx.activity.result.IntentSenderRequest.Builder(result.sender).build()
                                        )
                                    }
                                    MediaRepo.TrashResult.Done -> {
                                        viewerIndex = null
                                        savedViewerUri = null
                                        reload()
                                    }
                                    // v1.6: on failure keep the viewer open
                                    // with an honest message — the old code
                                    // closed the viewer and said nothing.
                                    MediaRepo.TrashResult.Failed -> onStatus("Couldn't restore — try again")
                                }
                            }
                        }
                    } else null,
                    onBack = { viewerIndex = null; savedViewerUri = null; reload(); openAlbum?.let { reloadAlbum(it) } },
                    onDeleted = { viewerIndex = null; savedViewerUri = null; reload(); openAlbum?.let { reloadAlbum(it) } },
                    onStatus = ::onStatus
                )
                !permissionsGranted && !skipPermissions -> PermissionScreen(
                    onGrant = { grantAccess() },
                    onSkip = { skipPermissions = true }
                )
                else -> {
                    // System back button: step back through the layers instead of
                    // exiting the app — selection, then search, then album.
                    if (selected.isNotEmpty()) {
                        BackHandler { selected = emptySet() }
                    } else if (query.isNotBlank()) {
                        BackHandler { query = ""; searchResults = emptyList() }
                    } else if (openAlbum != null) {
                        BackHandler { openAlbum = null; selected = emptySet() }
                    }
                    val selectionMode = selected.isNotEmpty()
                    Scaffold(
                        topBar = {
                            if (selectionMode) {
                                TopAppBar(
                                    title = { Text("${selected.size} selected") },
                                    navigationIcon = {
                                        IconButton(onClick = { selected = emptySet() }) {
                                            Icon(Icons.Filled.Close, "Clear selection")
                                        }
                                    },
                                    actions = {
                                        if (tab == GalleryTab.TRASH) {
                                            IconButton(onClick = { restoreSelected() }) {
                                                Icon(Icons.Filled.Restore, "Restore selected")
                                            }
                                            IconButton(onClick = { showDeleteConfirm = true }) {
                                                Icon(Icons.Filled.Delete, "Delete permanently", tint = NovaDanger)
                                            }
                                        } else {
                                            IconButton(onClick = {
                                                // v1.6: include searchResults — sharing a
                                                // selection from search did nothing (the
                                                // list was built from photos+albumPhotos
                                                // only, so search items were silently dropped).
                                                val items = (photos + albumPhotos + searchResults).filter { selected.contains(it.uri.toString()) }.distinctBy { it.uri }
                                                if (items.isNotEmpty()) {
                                                    val intent = Intent.createChooser(MediaRepo.shareMultipleIntent(items), "Share")
                                                    context.startActivity(intent)
                                                }
                                            }) { Icon(Icons.Filled.Share, "Share selected") }
                                            IconButton(onClick = { showDeleteConfirm = true }) {
                                                Icon(Icons.Filled.Delete, "Move to Trash", tint = NovaDanger)
                                            }
                                        }
                                    }
                                )
                            } else {
                                TopAppBar(
                                    title = { Text(if (openAlbum != null) openAlbum!! else "Gallery", fontWeight = FontWeight.Bold) },
                                    navigationIcon = {
                                        if (openAlbum != null) {
                                            IconButton(onClick = { openAlbum = null }) { Icon(Icons.Filled.ArrowBack, "Back to albums") }
                                        }
                                    },
                                    actions = {
                                        IconButton(onClick = {
                                            gridColumns = if (gridColumns >= 4) 2 else gridColumns + 1
                                            saveGridColumns(context, gridColumns)
                                        }) { Icon(Icons.Filled.GridView, "Change grid size") }
                                        IconButton(onClick = { showErrorLog = true }) {
                                            BadgedBox(badge = {
                                                if (errorCount > 0) Badge { Text(errorCount.toString()) }
                                            }) { Icon(Icons.Filled.BugReport, "Error log") }
                                        }
                                        IconButton(onClick = {
                                            darkTheme = !darkTheme
                                            saveDarkTheme(context, darkTheme)
                                        }) {
                                            Icon(
                                                if (darkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                                if (darkTheme) "Light theme" else "Dark theme"
                                            )
                                        }
                                    }
                                )
                            }
                        },
                        bottomBar = {
                            if (openAlbum == null && !selectionMode) {
                                // v1.8 Nebula: selected tab gets a violet
                                // pill; unselected tabs are muted lavender.
                                val navColors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NovaAccent,
                                    selectedTextColor = NovaAccent,
                                    unselectedIconColor = NovaMuted,
                                    unselectedTextColor = NovaMuted,
                                    indicatorColor = NovaAccent.copy(alpha = 0.18f)
                                )
                                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                    NavigationBarItem(
                                        selected = tab == GalleryTab.ALBUMS,
                                        colors = navColors,
                                        onClick = { tab = GalleryTab.ALBUMS; selected = emptySet() },
                                        icon = { Icon(Icons.Filled.PhotoLibrary, null) },
                                        label = { Text("Albums") }
                                    )
                                    NavigationBarItem(
                                        selected = tab == GalleryTab.PHOTOS,
                                        colors = navColors,
                                        onClick = { tab = GalleryTab.PHOTOS; selected = emptySet() },
                                        icon = { Icon(Icons.Filled.Photo, null) },
                                        label = { Text("Photos") }
                                    )
                                    NavigationBarItem(
                                        selected = tab == GalleryTab.FAVORITES,
                                        colors = navColors,
                                        onClick = { tab = GalleryTab.FAVORITES; selected = emptySet() },
                                        icon = { Icon(Icons.Filled.Star, null) },
                                        label = { Text("Favorites") }
                                    )
                                    NavigationBarItem(
                                        selected = tab == GalleryTab.TRASH,
                                        colors = navColors,
                                        onClick = { tab = GalleryTab.TRASH; selected = emptySet() },
                                        icon = { Icon(Icons.Filled.Delete, null) },
                                        label = { Text("Trash") }
                                    )
                                }
                            }
                        }
                    ) { padding ->
                        Column(Modifier.padding(padding).fillMaxSize()) {
                            status?.let {
                                Text(
                                    it, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                            // v1.7: the way back to permissions — after
                            // "Continue without it" there was no in-app way
                            // to grant access again (only system Settings).
                            if (!permissionsGranted) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "No media access — showing nothing yet.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { grantAccess() }) { Text("Allow access") }
                                }
                            }
                            OutlinedTextField(
                                value = query,
                                onValueChange = { q ->
                                    query = q
                                    if (q.isBlank()) searchResults = emptyList()
                                    else runSearch(q)
                                },
                                placeholder = { Text("Search photos by name") },
                                leadingIcon = { Icon(Icons.Filled.Search, null) },
                                trailingIcon = if (query.isNotEmpty()) {
                                    { IconButton(onClick = { query = ""; searchResults = emptyList() }) { Icon(Icons.Filled.Clear, "Clear search") } }
                                } else null,
                                singleLine = true,
                                // v1.8 Nebula: search sits on a surface card
                                // with a violet focus ring and a muted
                                // unfocused border (the scheme outline is a
                                // hairline 8% white — too faint for a field).
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    focusedBorderColor = NovaAccent,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    cursorColor = NovaAccent
                                ),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            if (query.isNotBlank()) {
                                if (searching) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                } else if (searchResults.isEmpty()) {
                                    EmptyState("No photos match \"$query\"")
                                } else {
                                    MediaGrid(
                                        items = searchResults,
                                        columns = gridColumns,
                                        selected = selected,
                                        onTap = { idx -> openViewer(searchResults, idx) },
                                        onLongPress = { item ->
                                            selected = if (selected.contains(item.uri.toString())) selected - item.uri.toString() else selected + item.uri.toString()
                                        },
                                        footer = if (searchResults.size >= PAGE_SIZE) "Showing the first $PAGE_SIZE matches — refine your search to see more" else null
                                    )
                                }
                            } else if (loading) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else if (openAlbum != null) {
                                if (albumLoading) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                } else if (albumPhotos.isEmpty()) {
                                    EmptyState("This album is empty")
                                } else {
                                    MediaGrid(
                                        items = albumPhotos,
                                        columns = gridColumns,
                                        selected = selected,
                                        onTap = { idx -> openViewer(albumPhotos, idx) },
                                        onLongPress = { item ->
                                            selected = if (selected.contains(item.uri.toString())) selected - item.uri.toString() else selected + item.uri.toString()
                                        },
                                        footer = if (albumTotal > 0) "Showing ${albumPhotos.size} of $albumTotal" else null,
                                        onLoadMore = if (albumHasMore) {
                                            { openAlbum?.let { loadMoreAlbum(it) } }
                                        } else null
                                    )
                                }
                            } else when (tab) {
                                GalleryTab.ALBUMS -> {
                                    if (albums.isEmpty()) {
                                        EmptyState("No albums yet — allow access to see your photos")
                                    } else {
                                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            if (memories.isNotEmpty()) {
                                                item {
                                                    Column {
                                                        Text(
                                                            "Memories — On this day",
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(bottom = 8.dp)
                                                        )
                                                        Row(
                                                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            memories.take(10).forEach { memory ->
                                                                AsyncImage(
                                                                    model = memory.uri,
                                                                    contentDescription = memory.displayName,
                                                                    contentScale = ContentScale.Crop,
                                                                    modifier = Modifier.size(120.dp).clip(MaterialTheme.shapes.medium)
                                                                        .clickable { openViewer(memories, memories.indexOf(memory)) }
                                                                )
                                                            }
                                                        }
                                                        Spacer(Modifier.height(8.dp))
                                                    }
                                                }
                                            }
                                            items(albums, key = { it.name }) { album ->
                                                AlbumRow(album) {
                                                    openAlbum = album.name
                                                    selected = emptySet()
                                                    reloadAlbum(album.name)
                                                }
                                            }
                                        }
                                    }
                                }
                                GalleryTab.PHOTOS -> {
                                    if (photos.isEmpty()) {
                                        EmptyState("No photos yet")
                                    } else {
                                        MediaGrid(
                                            items = photos,
                                            columns = gridColumns,
                                            selected = selected,
                                            onTap = { idx -> openViewer(photos, idx) },
                                            onLongPress = { item ->
                                                selected = if (selected.contains(item.uri.toString())) selected - item.uri.toString() else selected + item.uri.toString()
                                            },
                                            footer = if (totalPhotos > 0) "Showing ${photos.size} of $totalPhotos" else null,
                                            onLoadMore = if (photosHasMore) { ::loadMorePhotos } else null
                                        )
                                    }
                                }
                                GalleryTab.FAVORITES -> {
                                    if (favorites.isEmpty()) {
                                        EmptyState("No favorites yet — star a photo to pin it here")
                                    } else {
                                        MediaGrid(
                                            items = favorites,
                                            columns = gridColumns,
                                            selected = selected,
                                            onTap = { idx -> openViewer(favorites, idx) },
                                            onLongPress = { item ->
                                                selected = if (selected.contains(item.uri.toString())) selected - item.uri.toString() else selected + item.uri.toString()
                                            }
                                        )
                                    }
                                }
                                GalleryTab.TRASH -> {
                                    if (trashed.isEmpty()) {
                                        EmptyState("Trash is empty — deleted photos can be restored here for 30 days")
                                    } else {
                                        Column(Modifier.fillMaxSize()) {
                                            Text(
                                                "${trashed.size} item${if (trashed.size > 1) "s" else ""} — deleted items are permanently removed after 30 days",
                                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            MediaGrid(
                                                items = trashed,
                                                columns = gridColumns,
                                                selected = selected,
                                                onTap = { idx -> openViewer(trashed, idx, isTrash = true) },
                                                onLongPress = { item ->
                                                    selected = if (selected.contains(item.uri.toString())) selected - item.uri.toString() else selected + item.uri.toString()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        val inTrash = tab == GalleryTab.TRASH
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    if (inTrash) "Delete ${selected.size} item${if (selected.size > 1) "s" else ""} permanently?"
                    else "Move ${selected.size} item${if (selected.size > 1) "s" else ""} to Trash?"
                )
            },
            text = {
                Text(
                    if (inTrash) "This cannot be undone."
                    else "They can be restored from Trash for 30 days."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    if (inTrash) deletePermanentlySelected() else deleteSelected()
                }) { Text(if (inTrash) "Delete" else "Move to Trash", color = NovaDanger) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EmptyState(text: String) {
    // v1.8 Nebula: centered icon in a gradient-tinted circle + muted message.
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(72.dp)
                    .background(NovaGradientBrush, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.PhotoLibrary, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.height(16.dp))
            Text(
                text, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AlbumRow(album: Album, onClick: () -> Unit) {
    // v1.8 Nebula: cards are 20dp with a 1dp surface-outline border.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, NovaOutline, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (album.coverUri != null) {
            AsyncImage(
                model = album.coverUri,
                contentDescription = album.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(NovaAccent),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.PhotoLibrary, null, tint = Color.White) }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(album.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${album.count} item${if (album.count != 1) "s" else ""} · ${formatBytes(album.totalSizeBytes)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    items: List<MediaItem>,
    columns: Int,
    selected: Set<String>,
    onTap: (Int) -> Unit,
    onLongPress: (MediaItem) -> Unit,
    footer: String? = null,
    onLoadMore: (() -> Unit)? = null
) {
    val gridState = rememberLazyGridState()
    // v1.7: load the next page when the user scrolls near the end.
    val loadMoreState by rememberUpdatedState(onLoadMore)
    LaunchedEffect(gridState, items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIndex ->
                if (lastIndex != null && lastIndex >= items.size - 12) {
                    loadMoreState?.invoke()
                }
            }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // v1.7: stable keys — the old index-based items lost selection
        // highlights and flickered when the list changed under it.
        items(items.size, key = { items[it].uri.toString() }) { index ->
            val item = items[index]
            val isSelected = selected.contains(item.uri.toString())
            Box(
                Modifier.aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(
                        onClick = {
                            if (selected.isNotEmpty()) onLongPress(item) else onTap(index)
                        },
                        onLongClick = { onLongPress(item) }
                    )
            ) {
                AsyncImage(
                    model = item.uri,
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (item.isVideo) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                            // v1.7: a video with unknown duration (0) showed
                            // a fake "0:00" — show only the play icon.
                            if (item.durationMs > 0) {
                                Text(formatDuration(item.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (item.isFavorite) {
                    // v1.8 Nebula: favorites are pink (tertiary), sparingly.
                    Icon(
                        Icons.Filled.Star, null, tint = NovaPink,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp)
                    )
                }
                if (isSelected) {
                    Box(
                        Modifier.fillMaxSize().background(NovaAccent.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.CheckCircle, "Selected", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
        if (footer != null) {
            item(span = { GridItemSpan(columns) }) {
                Text(
                    footer,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("NOVA Gallery", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "To show your photos and videos, NOVA Gallery needs access to your media. Your photos stay on your phone — nothing is uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        // v1.8 Nebula: primary buttons use the violet→blue gradient.
        Button(
            onClick = onGrant,
            modifier = Modifier.fillMaxWidth().background(NovaGradientBrush, RoundedCornerShape(14.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Allow access") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onSkip,
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Continue without it") }
    }
}
