package com.app.musicplayer.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.app.musicplayer.video.YouTubeEmbedActivity

private const val PREFS_NAME = "youtube_hub"
private const val HISTORY_KEY = "search_history"

@Composable
fun YouTubeHubScreen() {
    val context = LocalContext.current
    val prefs = androidx.compose.runtime.remember { context.getSharedPreferences(PREFS_NAME, 0) }
    val history = rememberSaveable {
        mutableStateListOf<String>().apply {
            addAll(prefs.getStringSet(HISTORY_KEY, emptySet()).orEmpty().toList().take(8))
        }
    }
    var search by rememberSaveable { mutableStateOf("") }
    var videoUrl by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var launchError by rememberSaveable { mutableStateOf<String?>(null) }

    fun searchYouTube(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        val updated = (listOf(clean) + history.filterNot { it.equals(clean, true) }).take(8)
        history.clear()
        history.addAll(updated)
        prefs.edit().putStringSet(HISTORY_KEY, updated.toSet()).apply()
        search = clean
        launchError = null
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(clean)}")))
        } catch (_: ActivityNotFoundException) {
            launchError = "Browser ya YouTube app available nahi hai."
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("YouTube Hub", style = MaterialTheme.typography.headlineMedium)
        Text(
            "YouTube ko bina API key ke use karein. Search official YouTube app/browser mein khulega.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            label = { Text("Search songs, artists or videos") },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Rounded.Search, null) }
        )
        Button(
            onClick = { searchYouTube(search) },
            enabled = search.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            androidx.compose.material3.Icon(Icons.Rounded.OpenInNew, null)
            Text("  Search YouTube")
        }
        launchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Explore", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Hindi songs", "English hits", "Lo-fi music", "Live music", "Music videos").forEach { term ->
                AssistChip(onClick = { searchYouTube(term) }, label = { Text(term) })
            }
        }

        if (history.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Recent searches", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    history.clear()
                    prefs.edit().remove(HISTORY_KEY).apply()
                }) {
                    androidx.compose.material3.Icon(Icons.Rounded.Delete, null)
                    Text(" Clear")
                }
            }
            history.forEach { term ->
                Row(
                    Modifier.fillMaxWidth().clickable { searchYouTube(term) }.padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(term, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Search", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(Modifier.height(2.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Play a YouTube video", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Video link paste karein. Playback YouTube ke official embedded player se hoga; ads aur availability YouTube control karta hai.",
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
                            runCatching { context.startActivity(YouTubeEmbedActivity.intent(context, id)) }
                                .onFailure { error = "Video open nahi ho paya. YouTube app/browser mein try karein." }
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
                Text("Available without an API key", style = MaterialTheme.typography.titleMedium)
                Text("• Official YouTube search via app/browser")
                Text("• Recent searches saved on this device")
                Text("• In-app official embedded playback for pasted links")
                Text("• Standard watch, youtu.be, Shorts, embed and live links")
                Text("• No unofficial downloading, stream extraction or ad bypass")
            }
        }
    }
}

private fun extractYouTubeId(raw: String): String? {
    val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val id = when {
        host == "youtu.be" || host == "www.youtu.be" -> uri.lastPathSegment
        host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") -> when {
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
