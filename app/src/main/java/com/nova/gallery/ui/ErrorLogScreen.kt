package com.nova.gallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nova.gallery.data.ErrorLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorLogScreen(onBack: () -> Unit, onCleared: () -> Unit = {}) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf(ErrorLog.readAll(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Error log") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("NOVA Gallery error log", logText))
                        status = "Copied"
                    }) { Icon(Icons.Filled.ContentCopy, "Copy log") }
                    IconButton(onClick = {
                        ErrorLog.clear(context)
                        logText = ""
                        status = "Cleared"
                        // v1.7: tell the parent so the error badge updates
                        // immediately — it used to stay stale until the next
                        // reload.
                        onCleared()
                    }) { Icon(Icons.Filled.Clear, "Clear log") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (logText.isBlank()) {
                Text("No errors recorded. If something goes wrong, it will appear here with the exact error.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    logText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
