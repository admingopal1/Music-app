package com.app.musicplayer.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import com.app.musicplayer.download.DirectMediaUrlResult
import com.app.musicplayer.download.DirectMediaUrlValidator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.rounded.ContentCopy
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URLDecoder

private data class DownloadRow(
    val id: Long,
    val title: String,
    val status: String,
    val downloaded: Long,
    val total: Long,
    val sourceUri: String,
    val failureReason: String,
)

private data class DownloadSample(val bytes: Long, val timeMillis: Long)
private data class DownloadSpeed(val bytesPerSecond: Long, val etaSeconds: Long?)

@Composable
fun DownloadsHubScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<DownloadRow>()) }
    var loading by remember { mutableStateOf(false) }
    var platformHint by remember { mutableStateOf<String?>(null) }
    var selectedFilter by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }
    var downloadSamples by remember { mutableStateOf(emptyMap<Long, DownloadSample>()) }
    var downloadSpeeds by remember { mutableStateOf(emptyMap<Long, DownloadSpeed>()) }

    suspend fun refreshRows() {
        val now = System.currentTimeMillis()
        val freshRows = withContext(Dispatchers.IO) { queryDownloads(context) }
        val nextSamples = mutableMapOf<Long, DownloadSample>()
        val nextSpeeds = mutableMapOf<Long, DownloadSpeed>()
        freshRows.filter { it.status == "Downloading" }.forEach { item ->
            val previous = downloadSamples[item.id]
            if (previous != null && now > previous.timeMillis && item.downloaded >= previous.bytes) {
                val elapsed = now - previous.timeMillis
                val speed = ((item.downloaded - previous.bytes) * 1000L / elapsed).coerceAtLeast(0L)
                if (speed > 0L) {
                    val remaining = (item.total - item.downloaded).takeIf { item.total > 0L && it > 0L }
                    nextSpeeds[item.id] = DownloadSpeed(speed, remaining?.let { (it / speed).coerceAtLeast(1L) })
                }
            }
            nextSamples[item.id] = DownloadSample(item.downloaded, now)
        }
        downloadSamples = nextSamples
        downloadSpeeds = nextSpeeds
        rows = freshRows
    }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                refreshRows()
            } finally {
                loading = false
            }
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
        val validation = DirectMediaUrlValidator.validate(url)
        when (validation) {
            is DirectMediaUrlResult.Invalid -> {
                Toast.makeText(context, validation.message, Toast.LENGTH_LONG).show()
                return
            }
            is DirectMediaUrlResult.Social -> {
                platformHint = "${validation.platform} link detected. This is a webpage, not a direct media file. Open it in the official app/site. If the creator or platform provides an authorized direct download, paste that media-file link here. Private, login-protected, DRM-protected or otherwise restricted videos cannot be downloaded by this app."
                Toast.makeText(context, "Social post link detected — see the note below", Toast.LENGTH_LONG).show()
                return
            }
            is DirectMediaUrlResult.Valid -> Unit
        }
        val validUrl = validation as DirectMediaUrlResult.Valid
        val parsed = Uri.parse(validUrl.url)
        if (hasActiveDownload(context, parsed.toString())) {
            Toast.makeText(context, "This media URL is already queued or downloading", Toast.LENGTH_LONG).show()
            return
        }
        val rawName = runCatching { URLDecoder.decode(validUrl.fileName, "UTF-8") }.getOrNull().orEmpty()
        val requestedName = rawName.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trim('.').take(100)
            .ifBlank { "media_${System.currentTimeMillis()}.mp4" }
        val safeName = uniqueDownloadFileName(context, requestedName)
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
    val activeCount = rows.count { it.status == "Waiting" || it.status == "Downloading" || it.status == "Paused" }
    val completedCount = rows.count { it.status == "Completed" }
    val failedCount = rows.count { it.status == "Failed" }
    val filteredRows = when (selectedFilter) {
        "Active" -> rows.filter { it.status == "Waiting" || it.status == "Downloading" || it.status == "Paused" }
        "Completed" -> rows.filter { it.status == "Completed" }
        "Failed" -> rows.filter { it.status == "Failed" }
        else -> rows
    }
    val visibleRows = filteredRows.filter { row ->
        val query = searchQuery.trim()
        query.isBlank() || row.title.contains(query, ignoreCase = true) || row.sourceUri.contains(query, ignoreCase = true)
    }
    val hasActiveDownloads = activeCount > 0
    androidx.compose.runtime.LaunchedEffect(hasActiveDownloads) {
        while (hasActiveDownloads) {
            delay(1_500)
            refreshRows()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Downloads", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Direct media downloads + social link detection", color = Color.White.copy(alpha = 0.68f))
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF29203F).copy(alpha = 0.94f)),
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
                        "Direct MP4, WebM, MKV, MOV, M4V and audio files are supported. Instagram, Facebook, YouTube, X, Telegram, ShareChat, Moj, Josh and other recognized social links are identified and can be opened in their official app. Social post pages are not downloaded unless an authorized direct media URL is available. Saved files go to Downloads/MusicPlayer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (platformHint != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(platformHint!!, style = MaterialTheme.typography.bodyMedium)
                        if (url.startsWith("https://", true) || url.startsWith("http://", true)) {
                            OutlinedButton(onClick = { openLink(url) }) {
                                Icon(Icons.Rounded.OpenInBrowser, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Open link")
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
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search by file name or source link") },
                    shape = RoundedCornerShape(14.dp),
                )
                Text("$activeCount active  •  $completedCount completed  •  $failedCount failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("All", "Active", "Completed", "Failed").forEach { filter ->
                        TextButton(onClick = { selectedFilter = filter }) {
                            Text(
                                filter,
                                fontWeight = if (selectedFilter == filter) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedFilter == filter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (loading) {
            item { CircularProgressIndicator() }
        } else if (visibleRows.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp)
                        .clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.055f))
                        .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(22.dp)).padding(20.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.VideoLibrary, contentDescription = null, modifier = Modifier.padding(4.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("No downloads yet", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            searchQuery.isNotBlank() -> "No downloads match \"$searchQuery\"."
                            selectedFilter == "All" -> "Your completed and active downloads will appear here."
                            else -> "No $selectedFilter downloads."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(visibleRows, key = { it.id }) { item ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.09f)),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp)),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title, maxLines = 2, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(item.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if ((item.status == "Failed" || item.status == "Paused") && item.failureReason.isNotBlank()) {
                            Text(
                                item.failureReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.status == "Failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (item.total > 0) {
                            val progress = (item.downloaded.toFloat() / item.total.toFloat()).coerceIn(0f, 1f)
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${(progress * 100).toInt()}%  •  ${formatBytes(item.downloaded)} / ${formatBytes(item.total)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            downloadSpeeds[item.id]?.let { speed ->
                                val eta = speed.etaSeconds?.let(::formatDuration)
                                Text(
                                    if (eta != null) "${formatBytes(speed.bytesPerSecond)}/s  •  ETA $eta"
                                    else "${formatBytes(speed.bytesPerSecond)}/s",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item.sourceUri.isNotBlank()) {
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(item.sourceUri))
                                    Toast.makeText(context, "Source link copied", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                                    Text("Copy link")
                                }
                            }
                            if (item.status == "Failed" && item.sourceUri.isNotBlank()) {
                                TextButton(onClick = {
                                    val source = Uri.parse(item.sourceUri)
                                    if (source.scheme !in setOf("http", "https") || source.host.isNullOrBlank()) {
                                        Toast.makeText(context, "Retry unavailable: original media URL is invalid", Toast.LENGTH_LONG).show()
                                    } else if (hasActiveDownload(context, source.toString())) {
                                        Toast.makeText(context, "This media URL is already queued or downloading", Toast.LENGTH_LONG).show()
                                    } else {
                                        runCatching {
                                            val retryName = uniqueRetryFileName(item.title)
                                            val request = DownloadManager.Request(source)
                                                .setTitle(item.title)
                                                .setDescription("Retrying download to Downloads/MusicPlayer")
                                                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                                .setAllowedOverMetered(true)
                                                .setAllowedOverRoaming(false)
                                                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MusicPlayer/$retryName")
                                            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                            manager.enqueue(request)
                                            refresh()
                                        }.onFailure {
                                            Toast.makeText(context, "Retry failed: ${it.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                    Text("Retry")
                                }
                            }
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

/** Avoid duplicate active URLs and filename collisions in the shared Downloads/MusicPlayer folder. */
private fun hasActiveDownload(context: Context, sourceUri: String): Boolean {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val cursor = manager.query(DownloadManager.Query()) ?: return false
    return cursor.use { c ->
        val uriCol = c.getColumnIndex(DownloadManager.COLUMN_URI)
        val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
        if (uriCol < 0 || statusCol < 0) return@use false
        var found = false
        while (c.moveToNext() && !found) {
            val status = c.getInt(statusCol)
            val active = status == DownloadManager.STATUS_PENDING ||
                status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED
            found = active && c.getString(uriCol) == sourceUri
        }
        found
    }
}

private fun uniqueDownloadFileName(context: Context, requestedName: String): String {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val cursor = manager.query(DownloadManager.Query()) ?: return requestedName
    val alreadyUsed = cursor.use { c ->
        val titleCol = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
        if (titleCol < 0) false else {
            var found = false
            while (c.moveToNext() && !found) found = c.getString(titleCol).equals(requestedName, ignoreCase = true)
            found
        }
    }
    if (!alreadyUsed) return requestedName
    val dot = requestedName.lastIndexOf('.')
    val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
    val extension = if (dot > 0) requestedName.substring(dot) else ""
    return "${base.take(70)}-${System.currentTimeMillis()}$extension"
}

private fun uniqueRetryFileName(title: String): String {
    val decoded = runCatching { URLDecoder.decode(title, "UTF-8") }.getOrDefault(title)
    val safe = decoded.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim().trim('.').take(90).ifBlank { "media" }
    val dot = safe.lastIndexOf('.')
    val base = if (dot > 0) safe.substring(0, dot) else safe
    val extension = if (dot > 0) safe.substring(dot) else ""
    return "${base.take(70)}-retry-${System.currentTimeMillis()}$extension"
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
        val uriCol = c.getColumnIndex(DownloadManager.COLUMN_URI)
        val reasonCol = c.getColumnIndex(DownloadManager.COLUMN_REASON)
        if (idCol < 0 || titleCol < 0 || statusCol < 0) return@use emptyList()
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
            val failureReason = if ((status == "Failed" || status == "Paused") && reasonCol >= 0) {
                when (c.getInt(reasonCol)) {
                    DownloadManager.ERROR_FILE_ERROR -> "Storage or file error"
                    DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Server returned an unsupported HTTP response"
                    DownloadManager.ERROR_HTTP_DATA_ERROR -> "Network data error"
                    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects"
                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage space"
                    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Download storage is unavailable"
                    DownloadManager.ERROR_CANNOT_RESUME -> "Download cannot resume; try again"
                    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "A file with this name already exists"
                    DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection interrupted — Android will retry automatically"
                    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a network connection"
                    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
                    DownloadManager.PAUSED_UNKNOWN -> "Download paused by Android"
                    else -> if (status == "Paused") "Download temporarily paused" else "Download failed (code " + c.getInt(reasonCol) + ")"
                }
            } else ""
            val downloaded = if (bytesCol >= 0) c.getLong(bytesCol).coerceAtLeast(0L) else 0L
            val total = if (totalCol >= 0) c.getLong(totalCol).coerceAtLeast(0L) else 0L
            out += DownloadRow(c.getLong(idCol), c.getString(titleCol) ?: "Media file", status, downloaded, total, if (uriCol >= 0) c.getString(uriCol).orEmpty() else "", failureReason)
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

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hours = safe / 3600L
    val minutes = (safe % 3600L) / 60L
    val remainingSeconds = safe % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${remainingSeconds}s"
        else -> "${remainingSeconds}s"
    }
}
