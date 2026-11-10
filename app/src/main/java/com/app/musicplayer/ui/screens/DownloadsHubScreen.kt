package com.app.musicplayer.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.Locale

private data class DownloadRow(
    val id: Long,
    val title: String,
    val status: String,
    val downloaded: Long,
    val total: Long,
)

@Composable
fun DownloadsHubScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<DownloadRow>()) }
    var loading by remember { mutableStateOf(false) }
    var platformHint by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            rows = withContext(Dispatchers.IO) { queryDownloads(context) }
            loading = false
        }
    }

    fun openLink(link: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(context, "No browser available to open this link", Toast.LENGTH_LONG).show()
        }
    }

    fun startDownload() {
        val parsed = runCatching { Uri.parse(url.trim()) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase(Locale.ROOT)
        if (parsed == null || scheme !in listOf("http", "https") || parsed.host.isNullOrBlank()) {
            Toast.makeText(context, "Enter a valid http/https media URL", Toast.LENGTH_LONG).show()
            return
        }
        val host = parsed.host.orEmpty().lowercase(Locale.ROOT)
        if (host.contains("instagram.com") || host.contains("facebook.com") || host.contains("fb.watch")) {
            platformHint = when {
                host.contains("instagram.com") -> "Instagram post link detected."
                else -> "Facebook video link detected."
            } + " This is a post page, not a direct media file. Open it in the official site/app. Downloads work when the creator provides an accessible direct media URL or you own and export the file. Private/restricted posts are not bypassed."
            Toast.makeText(context, "Social post link detected — see the note below", Toast.LENGTH_LONG).show()
            return
        }
        val path = parsed.lastPathSegment.orEmpty().lowercase(Locale.ROOT)
        val allowed = listOf(".mp4", ".webm", ".mkv", ".mov", ".m4v", ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac")
        if (allowed.none { path.endsWith(it) }) {
            Toast.makeText(context, "Use a direct MP4, WebM, MOV or audio-file URL. Web pages and streaming playlists are not direct files.", Toast.LENGTH_LONG).show()
            return
        }
        val rawName = parsed.lastPathSegment?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty()
        val safeName = rawName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100).ifBlank { "media_${System.currentTimeMillis()}.mp4" }
        runCatching {
            val request = DownloadManager.Request(parsed)
                .setTitle(safeName)
                .setDescription("Downloading to Downloads/MusicPlayer")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MusicPlayer/$safeName")
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            platformHint = "Download added. Android will show progress and a notification."
            url = ""
            refresh()
        }.onFailure {
            Toast.makeText(context, "Could not start download: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Downloads", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Save direct video and audio files to your phone", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Paste a media link", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; platformHint = null },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://example.com/video.mp4") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                url = clipboard.getText()?.text.orEmpty()
                                platformHint = null
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Paste")
                        }
                        Button(onClick = ::startDownload, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Download")
                        }
                    }
                    Text(
                        "Supported direct files: MP4, WebM, MKV, MOV, M4V and common audio formats. Downloads are saved in Downloads/MusicPlayer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (platformHint != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(platformHint!!, style = MaterialTheme.typography.bodyMedium)
                        if (url.contains("instagram.com", true) || url.contains("facebook.com", true) || url.contains("fb.watch", true)) {
                            OutlinedButton(onClick = { openLink(url) }) {
                                Icon(Icons.Rounded.OpenInBrowser, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Open social post")
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column {
                    Text("Recent downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Managed by Android Download Manager", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = ::refresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    Text("Refresh")
                }
            }
        }
        if (loading) {
            item { CircularProgressIndicator() }
        } else if (rows.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.VideoLibrary, contentDescription = null, modifier = Modifier.padding(4.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("No downloads yet", fontWeight = FontWeight.SemiBold)
                    Text("Your completed and active downloads will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(rows, key = { it.id }) { item ->
                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title, maxLines = 2, fontWeight = FontWeight.SemiBold)
                        Text(item.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (item.total > 0) {
                            val progress = (item.downloaded.toFloat() / item.total.toFloat()).coerceIn(0f, 1f)
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${formatBytes(item.downloaded)} / ${formatBytes(item.total)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item.status == "Completed") {
                                TextButton(onClick = {
                                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                    val uri = manager.getUriForDownloadedFile(item.id)
                                    if (uri != null) {
                                        val mime = context.contentResolver.getType(uri) ?: "*/*"
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }.onFailure {
                                            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_LONG).show()
                                        }
                                    } else Toast.makeText(context, "Downloaded file is unavailable", Toast.LENGTH_LONG).show()
                                }) {
                                    Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                                    Text("Open")
                                }
                            }
                            TextButton(onClick = {
                                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                manager.remove(item.id)
                                refresh()
                            }) {
                                Icon(Icons.Rounded.Delete, contentDescription = null)
                                Text(if (item.status == "Completed" || item.status == "Failed") "Remove" else "Cancel")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun queryDownloads(context: Context): List<DownloadRow> {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val cursor = manager.query(DownloadManager.Query().setFilterByStatus(
        DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED or
            DownloadManager.STATUS_SUCCESSFUL or DownloadManager.STATUS_FAILED
    )) ?: return emptyList()
    return cursor.use { c ->
        val idCol = c.getColumnIndex(DownloadManager.COLUMN_ID)
        val titleCol = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
        val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val bytesCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val out = mutableListOf<DownloadRow>()
        while (c.moveToNext()) {
            val status = when (c.getInt(statusCol)) {
                DownloadManager.STATUS_PENDING -> "Waiting"
                DownloadManager.STATUS_RUNNING -> "Downloading"
                DownloadManager.STATUS_PAUSED -> "Paused"
                DownloadManager.STATUS_SUCCESSFUL -> "Completed"
                DownloadManager.STATUS_FAILED -> "Failed"
                else -> "Unknown"
            }
            out += DownloadRow(c.getLong(idCol), c.getString(titleCol) ?: "Media file", status, c.getLong(bytesCol), c.getLong(totalCol))
        }
        out.sortedByDescending { it.id }.take(30)
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> "%.1f GB".format(value / (1024.0 * 1024 * 1024))
    value >= 1024L * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
    value >= 1024L -> "%.0f KB".format(value / 1024.0)
    else -> "${value} B"
}
