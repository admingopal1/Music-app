package com.app.musicplayer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.app.musicplayer.video.YouTubeEmbedActivity

@Composable
fun YouTubeHubScreen() {
    val context = LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }
    var videoUrl by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("YouTube Hub", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Discover videos and watch them with YouTube's official embedded player.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            label = { Text("Search YouTube") },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Rounded.Search, null) }
        )
        Button(
            onClick = {
                val q = Uri.encode(search.trim())
                if (q.isNotBlank()) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q"))
                    context.startActivity(intent)
                }
            },
            enabled = search.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            androidx.compose.material3.Icon(Icons.Rounded.OpenInNew, null)
            Text("  Search on YouTube")
        }

        Spacer(Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Play a YouTube video", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Paste a YouTube video link. Playback uses the official YouTube embedded player; availability and ads are controlled by YouTube.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = videoUrl,
                    onValueChange = { videoUrl = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("YouTube video URL") },
                    placeholder = { Text("https://www.youtube.com/watch?v=...") }
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        val id = extractYouTubeId(videoUrl)
                        if (id == null) error = "Valid YouTube video URL paste karein."
                        else {
                            error = null
                            context.startActivity(YouTubeEmbedActivity.intent(context, id))
                        }
                    },
                    enabled = videoUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.Icon(Icons.Rounded.PlayArrow, null)
                    Text("  Play video")
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Included in this first integration", style = MaterialTheme.typography.titleMedium)
                Text("• YouTube search hand-off to the official app or website")
                Text("• In-app embedded playback for a pasted video link")
                Text("• Supports standard watch, youtu.be and Shorts links")
                Text("• No unofficial stream extraction or downloader")
            }
        }
    }
}

private fun extractYouTubeId(raw: String): String? {
    val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val id = when {
        host == "youtu.be" || host == "www.youtu.be" -> uri.lastPathSegment
        host.endsWith("youtube.com") || host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") -> when {
            uri.path == "/watch" -> uri.getQueryParameter("v")
            uri.pathSegments.firstOrNull() == "shorts" -> uri.pathSegments.getOrNull(1)
            uri.pathSegments.firstOrNull() == "embed" -> uri.pathSegments.getOrNull(1)
            uri.pathSegments.firstOrNull() == "live" -> uri.pathSegments.getOrNull(1)
            else -> null
        }
        else -> null
    }
    return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
}
