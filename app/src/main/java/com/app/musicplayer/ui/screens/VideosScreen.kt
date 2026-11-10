package com.app.musicplayer.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.musicplayer.data.Video
import com.app.musicplayer.data.VideoThumbs
import com.app.musicplayer.data.asClock
import com.app.musicplayer.ui.PlayerViewModel
import com.app.musicplayer.video.VideoPlayerActivity

@Composable
fun VideosScreen(vm: PlayerViewModel) {
    val ctx = LocalContext.current
    val videos by vm.videos.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    var showYouTube by rememberSaveable { mutableStateOf(false) }

    val filtered = remember(videos, query) {
        if (query.isBlank()) videos
        else videos.filter { it.title.contains(query, true) || it.folder.contains(query, true) }
    }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF21133E), Color(0xFF17152F), MaterialTheme.colorScheme.background)))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!showDownloads && !showYouTube) Button(onClick = { showDownloads = false; showYouTube = false }) { Text("My videos") }
            else TextButton(onClick = { showDownloads = false; showYouTube = false }) { Text("My videos") }
            if (showDownloads) Button(onClick = { showDownloads = true; showYouTube = false }) { Text("Downloads") }
            else TextButton(onClick = { showDownloads = true; showYouTube = false }) { Text("Downloads") }
            if (showYouTube) Button(onClick = { showYouTube = true; showDownloads = false }) { Text("YouTube") }
            else TextButton(onClick = { showYouTube = true; showDownloads = false }) { Text("YouTube") }
        }
        if (showDownloads) {
            DownloadsHubScreen()
            return@Column
        }
        if (showYouTube) {
            YouTubeHubScreen()
            return@Column
        }
        Text(
            "Video Universe", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp),
        )
        Text(
            "${videos.size} videos on this device", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Search videos or folders") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        if (videos.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No videos found. Grant video permission, then scan again.",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = vm::loadVideos, modifier = Modifier.padding(top = 16.dp)) { Text("Scan again") }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
                itemsIndexed(filtered, key = { _, v -> v.id }) { index, video ->
                    VideoRow(video) {
                        VideoPlayerActivity.start(
                            ctx, filtered.map { it.uri.toString() }, filtered.map { it.title }, index,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoRow(video: Video, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, video.id) {
        value = VideoThumbs.load(ctx, video)?.asImageBitmap()
    }
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 120.dp, height = 68.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Rounded.PlayCircle, null, tint = accent, modifier = Modifier.size(32.dp))
            Text(
                video.durationMs.asClock(),
                style = MaterialTheme.typography.labelSmall, color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(0.7f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                video.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            val res = if (video.height > 0) "${video.width}x${video.height} · " else ""
            Text(
                res + video.sizeBytes.asSize() + " · " + video.folder.substringAfterLast('/'),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Long.asSize(): String = when {
    this >= 1L shl 30 -> "%.1f GB".format(this / (1024.0 * 1024 * 1024))
    this >= 1L shl 20 -> "%.0f MB".format(this / (1024.0 * 1024))
    else -> "%d KB".format(this / 1024)
}
