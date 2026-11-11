package com.nova.gallery.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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

enum class GalleryTab { ALBUMS, PHOTOS, FAVORITES }

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
        setContent { NovaGalleryApp() }
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
fun NovaGalleryApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(GalleryTab.ALBUMS) }
    var darkTheme by remember { mutableStateOf(loadDarkTheme(context)) }
    var gridColumns by remember { mutableStateOf(loadGridColumns(context)) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var photos by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var openAlbum by remember { mutableStateOf<String?>(null) }
    var albumPhotos by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var viewerList by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var showErrorLog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
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
            val all = withContext(Dispatchers.IO) { MediaRepo.loadAll(context) }
            val alb = withContext(Dispatchers.IO) { MediaRepo.loadAlbums(context) }
            photos = all
            albums = alb
            favorites = all.filter { it.isFavorite }
            errorCount = ErrorLog.count(context)
            loading = false
        }
    }

    fun reloadAlbum(name: String) {
        scope.launch {
            albumPhotos = withContext(Dispatchers.IO) { MediaRepo.loadAlbum(context, name) }
        }
    }

    fun runSearch(q: String) {
        scope.launch {
            searching = true
            searchResults = withContext(Dispatchers.IO) { MediaRepo.search(context, q) }
            searching = false
        }
    }

    LaunchedEffect(Unit) { if (permissionsGranted) reload() }
    LaunchedEffect(status) {
        if (status != null) { kotlinx.coroutines.delay(3000); status = null }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = mediaPermissions().any { results[it] == true }
        if (permissionsGranted) reload()
    }

    // System confirmation for deletes (single + batch).
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            onStatus("Deleted")
            selected = emptySet()
            reload()
            openAlbum?.let { reloadAlbum(it) }
        } else {
            onStatus("Delete cancelled")
        }
    }

    fun deleteSelected() {
        val items = photos.filter { selected.contains(it.id) } +
            albumPhotos.filter { selected.contains(it.id) }
        val unique = items.distinctBy { it.id }
        if (unique.isEmpty()) return
        MediaRepo.deleteMultiple(context, unique) { deleted, sender ->
            if (sender != null) {
                deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                )
            } else {
                onStatus(if (deleted > 0) "Deleted $deleted item${if (deleted > 1) "s" else ""}" else "Couldn't delete")
                selected = emptySet()
                reload()
                openAlbum?.let { reloadAlbum(it) }
            }
        }
    }

    fun openViewer(list: List<MediaItem>, index: Int) {
        viewerList = list
        viewerIndex = index
    }

    val viewerItem = viewerIndex?.let { viewerList.getOrNull(it) }

    NovaTheme(darkTheme = darkTheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                showErrorLog -> ErrorLogScreen(onBack = { showErrorLog = false })
                viewerItem != null -> ViewerScreen(
                    item = viewerItem,
                    hasPrev = (viewerIndex ?: 0) > 0,
                    hasNext = (viewerIndex ?: 0) < viewerList.size - 1,
                    onPrev = { viewerIndex = (viewerIndex ?: 0) - 1 },
                    onNext = { viewerIndex = (viewerIndex ?: 0) + 1 },
                    onBack = { viewerIndex = null; reload() },
                    onDeleted = { viewerIndex = null; reload(); openAlbum?.let { reloadAlbum(it) } },
                    onStatus = ::onStatus
                )
                !permissionsGranted && !skipPermissions -> PermissionScreen(
                    onGrant = { permissionLauncher.launch(mediaPermissions()) },
                    onSkip = { skipPermissions = true }
                )
                else -> {
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
                                        IconButton(onClick = {
                                            val items = (photos + albumPhotos).filter { selected.contains(it.id) }.distinctBy { it.id }
                                            if (items.isNotEmpty()) {
                                                val intent = Intent.createChooser(MediaRepo.shareMultipleIntent(items), "Share")
                                                context.startActivity(intent)
                                            }
                                        }) { Icon(Icons.Filled.Share, "Share selected") }
                                        IconButton(onClick = { showDeleteConfirm = true }) {
                                            Icon(Icons.Filled.Delete, "Delete selected", tint = NovaDanger)
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
                                            selected = if (selected.contains(item.id)) selected - item.id else selected + item.id
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
                                            selected = if (selected.contains(item.id)) selected - item.id else selected + item.id
                                        }
                                    )
                                }
                            } else when (tab) {
                                GalleryTab.ALBUMS -> {
                                    if (albums.isEmpty()) {
                                        EmptyState("No albums yet — allow access to see your photos")
                                    } else {
                                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                                selected = if (selected.contains(item.id)) selected - item.id else selected + item.id
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
                                                selected = if (selected.contains(item.id)) selected - item.id else selected + item.id
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

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${selected.size} item${if (selected.size > 1) "s" else ""}?") },
            text = { Text("The selected photos and videos will be deleted from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    deleteSelected()
                }) { Text("Delete", color = NovaDanger) }
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
    selected: Set<Long>,
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
            val isSelected = selected.contains(item.id)
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
