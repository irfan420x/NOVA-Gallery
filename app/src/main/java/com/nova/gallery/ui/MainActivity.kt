package com.nova.gallery.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

private fun mediaPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
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
    var tab by remember { mutableStateOf(GalleryTab.ALBUMS) }
    var darkTheme by remember { mutableStateOf(loadDarkTheme(context)) }
    var gridColumns by remember { mutableStateOf(loadGridColumns(context)) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var photos by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var trashed by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var memories by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var openAlbum by remember { mutableStateOf<String?>(null) }
    var albumPhotos by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var viewerList by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var viewerIsTrash by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var showErrorLog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var errorCount by remember { mutableStateOf(ErrorLog.count(context)) }
    var permissionsGranted by remember {
        mutableStateOf(MediaRepo.hasMediaPermission(context))
    }
    var skipPermissions by remember { mutableStateOf(false) }

    fun onStatus(msg: String) { status = msg }

    fun reload() {
        scope.launch {
            loading = true
            // v1.5: one MediaStore scan — albums, memories and favorites are
            // derived from the same list instead of re-scanning 3 more times.
            val all = withContext(Dispatchers.IO) { MediaRepo.loadAll(context) }
            val tr = withContext(Dispatchers.IO) { MediaRepo.loadTrashed(context) }
            photos = all
            albums = MediaRepo.loadAlbumsFrom(all)
            favorites = all.filter { it.isFavorite }
            trashed = tr
            memories = MediaRepo.loadMemoriesFrom(all)
            errorCount = ErrorLog.count(context)
            loading = false
        }
    }

    fun reloadAlbum(name: String) {
        scope.launch {
            albumPhotos = withContext(Dispatchers.IO) { MediaRepo.loadAlbum(context, name) }
        }
    }

    // v1.5: cancel the previous search before starting a new one — a slow
    // earlier query used to finish after a later one and overwrite the
    // results with stale ones.
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun runSearch(q: String) {
        searchJob?.cancel()
        searchJob = scope.launch {
            searching = true
            searchResults = withContext(Dispatchers.IO) { MediaRepo.search(context, q) }
            searching = false
        }
    }

    LaunchedEffect(Unit) { if (permissionsGranted) reload() }

    // v1.5: "Open with Gallery" — an incoming VIEW intent opens the viewer
    // with that one photo/video (the sender grants URI permission).
    LaunchedEffect(viewUri) {
        viewUri?.let { uri ->
            val item = withContext(Dispatchers.IO) { MediaRepo.fromUri(context, uri) }
            if (item != null) {
                viewerList = listOf(item)
                viewerIndex = 0
                viewerIsTrash = false
            }
        }
    }

    // v1.5: refresh when the app comes back to the foreground — a photo
    // taken with the camera while Gallery was open now appears.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && permissionsGranted) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(status) {
        if (status != null) { kotlinx.coroutines.delay(3000); status = null }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = mediaPermissions().any { results[it] == true }
        if (permissionsGranted) reload()
    }

    // v1.4: deleting moves photos to the Trash (Android 11+), so they can
    // be restored for 30 days. The system confirmation covers trash,
    // restore and permanent delete alike.
    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            onStatus("Moved to Trash")
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
            reload()
        } else {
            onStatus("Cancelled")
        }
    }
    // System confirmation for permanent deletes.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            onStatus("Deleted permanently")
            selected = emptySet()
            reload()
            openAlbum?.let { reloadAlbum(it) }
        } else {
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
        val sender = MediaRepo.trashItems(context, items, true)
        if (sender != null) {
            trashLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(sender).build()
            )
        } else {
            // Below Android 11 there is no Trash API — the delete was permanent.
            onStatus("Deleted")
            selected = emptySet()
            reload()
            openAlbum?.let { reloadAlbum(it) }
        }
    }

    fun restoreSelected() {
        val items = trashed.filter { selected.contains(it.uri.toString()) }
        if (items.isEmpty()) return
        val sender = MediaRepo.trashItems(context, items, false)
        if (sender != null) {
            restoreLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(sender).build()
            )
        } else {
            onStatus("Restored")
            selected = emptySet()
            reload()
        }
    }

    fun deletePermanentlySelected() {
        val items = trashed.filter { selected.contains(it.uri.toString()) }
        if (items.isEmpty()) return
        MediaRepo.deleteMultiple(context, items) { deleted, sender ->
            if (sender != null) {
                deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                )
            } else {
                onStatus(if (deleted > 0) "Deleted $deleted item${if (deleted > 1) "s" else ""}" else "Couldn't delete")
                selected = emptySet()
                reload()
            }
        }
    }

    fun openViewer(list: List<MediaItem>, index: Int, isTrash: Boolean = false) {
        viewerList = list
        viewerIndex = index
        viewerIsTrash = isTrash
    }

    val viewerItem = viewerIndex?.let { viewerList.getOrNull(it) }

    NovaTheme(darkTheme = darkTheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                showErrorLog -> {
                    BackHandler { showErrorLog = false }
                    ErrorLogScreen(onBack = { showErrorLog = false })
                }
                viewerItem != null -> ViewerScreen(
                    items = viewerList,
                    initialIndex = viewerIndex ?: 0,
                    isTrash = viewerIsTrash,
                    onRestore = if (viewerIsTrash) {
                        {
                            val item = viewerItem
                            if (item != null) {
                                val sender = MediaRepo.trashItems(context, listOf(item), false)
                                if (sender != null) {
                                    restoreLauncher.launch(
                                        androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                                    )
                                }
                                viewerIndex = null
                                reload()
                            }
                        }
                    } else null,
                    onBack = { viewerIndex = null; reload(); openAlbum?.let { reloadAlbum(it) } },
                    onDeleted = { viewerIndex = null; reload(); openAlbum?.let { reloadAlbum(it) } },
                    onStatus = ::onStatus
                )
                !permissionsGranted && !skipPermissions -> PermissionScreen(
                    onGrant = { permissionLauncher.launch(mediaPermissions()) },
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
                        BackHandler { openAlbum = null }
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
                                                val items = (photos + albumPhotos).filter { selected.contains(it.uri.toString()) }.distinctBy { it.uri }
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
                                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                    NavigationBarItem(
                                        selected = tab == GalleryTab.ALBUMS,
                                        onClick = { tab = GalleryTab.ALBUMS },
                                        icon = { Icon(Icons.Filled.PhotoLibrary, null) },
                                        label = { Text("Albums") }
                                    )
                                    NavigationBarItem(
                                        selected = tab == GalleryTab.PHOTOS,
                                        onClick = { tab = GalleryTab.PHOTOS },
                                        icon = { Icon(Icons.Filled.Photo, null) },
                                        label = { Text("Photos") }
                                    )
                                    NavigationBarItem(
                                        selected = tab == GalleryTab.FAVORITES,
                                        onClick = { tab = GalleryTab.FAVORITES },
                                        icon = { Icon(Icons.Filled.Star, null) },
                                        label = { Text("Favorites") }
                                    )
                                    NavigationBarItem(
                                        selected = tab == GalleryTab.TRASH,
                                        onClick = { tab = GalleryTab.TRASH },
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
                                        }
                                    )
                                }
                            } else if (loading) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else if (openAlbum != null) {
                                if (albumPhotos.isEmpty()) {
                                    EmptyState("This album is empty")
                                } else {
                                    MediaGrid(
                                        items = albumPhotos,
                                        columns = gridColumns,
                                        selected = selected,
                                        onTap = { idx -> openViewer(albumPhotos, idx) },
                                        onLongPress = { item ->
                                            selected = if (selected.contains(item.uri.toString())) selected - item.uri.toString() else selected + item.uri.toString()
                                        }
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
                                            items(albums) { album ->
                                                AlbumRow(album) {
                                                    openAlbum = album.name
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
                                            }
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
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AlbumRow(album: Album, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
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
    onLongPress: (MediaItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items.size) { index ->
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
                            Text(formatDuration(item.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (item.isFavorite) {
                    Icon(
                        Icons.Filled.Star, null, tint = Color(0xFFFFC107),
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
        Button(onClick = onGrant, Modifier.fillMaxWidth()) { Text("Allow access") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSkip, Modifier.fillMaxWidth()) { Text("Continue without it") }
    }
}
