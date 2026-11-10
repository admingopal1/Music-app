package com.app.musicplayer.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.YouTubeSearchedFor
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun YouTubeHubScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var videoUrl by rememberSaveable { mutableStateOf("") }

    fun openYouTube(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(context, "YouTube app or browser is not available", Toast.LENGTH_LONG).show()
            }
    }

    fun searchYouTube() {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            Toast.makeText(context, "Enter something to search", Toast.LENGTH_SHORT).show()
            return
        }
        openYouTube(Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
    }

    fun openVideoLink() {
        val parsed = runCatching { Uri.parse(videoUrl.trim()) }.getOrNull()
        val host = parsed?.host?.lowercase().orEmpty()
        val isYouTube = host == "youtu.be" || host == "youtube.com" ||
            host.endsWith(".youtube.com") || host == "youtube-nocookie.com" ||
            host.endsWith(".youtube-nocookie.com")
        if (parsed == null || parsed.scheme !in listOf("http", "https") || !isYouTube) {
            Toast.makeText(context, "Paste a valid YouTube video, Shorts or channel link", Toast.LENGTH_LONG).show()
            return
        }
        openYouTube(parsed)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("YouTube", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Discover videos and watch through the official YouTube experience.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Search YouTube", fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search songs, creators, tutorials…") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                Button(onClick = ::searchYouTube, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.YouTubeSearchedFor, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Search videos")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Open a YouTube link", fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(
                    value = videoUrl,
                    onValueChange = { videoUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://youtu.be/…") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { videoUrl = clipboard.getText()?.text.orEmpty() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Paste link") }
                    Button(onClick = ::openVideoLink, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Open video")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Playback & downloads", fontWeight = FontWeight.SemiBold)
                Text(
                    "Videos play in the YouTube app or browser. This app does not extract or download other creators’ YouTube streams. For offline viewing, use YouTube’s own download options where available; your own uploads can be downloaded from YouTube Studio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
