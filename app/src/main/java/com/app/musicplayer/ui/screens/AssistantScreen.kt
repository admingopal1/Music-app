package com.app.musicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.musicplayer.data.asClock
import com.app.musicplayer.ui.PlayerViewModel

/** Private offline assistant: ranks local library metadata and listening stats without uploading user data. */
@Composable
fun AssistantScreen(vm: PlayerViewModel, onOpenPlayer: () -> Unit) {
    val songs by vm.songs.collectAsState()
    val stats by vm.stats.stats.collectAsState()
    val playlists by vm.playlists.lists.collectAsState()
    var prompt by rememberSaveable { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf("Tell me what you want to hear. I can search your library, build a mix, or save a playlist.") }
    var resultIds by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    val selected = songs.filter { it.id in resultIds }

    fun runPrompt(raw: String) {
        val q = raw.trim().lowercase()
        if (q.isBlank()) return
        val words = q.split(Regex("[^a-z0-9]+")).filter { it.length > 1 }
        val favorites = listOf("favorite", "favourite", "liked").any(q::contains)
        val recent = listOf("recent", "latest", "last played").any(q::contains)
        val popular = listOf("popular", "most played", "top").any(q::contains)
        val moodWords = when {
            listOf("relax", "calm", "sleep", "peace", "chill").any(q::contains) -> listOf("acoustic", "slow", "soft", "calm", "piano", "sleep", "lofi", "relax")
            listOf("workout", "gym", "energy", "energetic", "run").any(q::contains) -> listOf("dance", "rock", "edm", "party", "energy", "workout", "fast")
            listOf("sad", "emotional", "heartbreak").any(q::contains) -> listOf("sad", "love", "heart", "alone", "pain", "broken")
            listOf("happy", "feel good", "party", "dance").any(q::contains) -> listOf("happy", "dance", "party", "summer", "fun")
            else -> emptyList()
        }
        val ranked = songs.mapNotNull { song ->
            val stat = stats[song.id]
            var score = 0
            if (favorites && stat?.favorite == true) score += 100
            if (recent) score += ((stat?.lastPlayed ?: 0L) / 100000000L).toInt().coerceAtMost(50)
            if (popular) score += (stat?.plays ?: 0).coerceAtMost(100)
            moodWords.forEach { word ->
                if (song.title.contains(word, true) || song.artist.contains(word, true) || song.album.contains(word, true)) score += 12
            }
            words.filter { it !in listOf("play", "make", "create", "playlist", "songs", "song", "music", "for", "me", "my", "some", "mix", "the") }
                .forEach { word ->
                    if (song.title.contains(word, true)) score += 24
                    if (song.artist.contains(word, true)) score += 20
                    if (song.album.contains(word, true)) score += 10
                }
            if (score > 0) song to score else null
        }.sortedByDescending { it.second }.map { it.first }

        val chosen = if (ranked.isNotEmpty()) ranked.take(40)
            else songs.sortedByDescending { stats[it.id]?.plays ?: 0 }.take(25)
        resultIds = chosen.map { it.id }
        answer = when {
            chosen.isEmpty() -> "I couldn't find tracks yet. Scan your device library and try again."
            favorites -> "I found " + chosen.size + " tracks using your favourites and matching music."
            moodWords.isNotEmpty() -> "I made an offline " + moodWords.first() + "-style mix using matching titles, artists and albums."
            recent -> "Here are tracks ranked using your recent listening history."
            popular -> "Here are your most-played tracks from local listening stats."
            else -> "I found " + chosen.size + " tracks matching your request in your device library."
        }
        if ((q.contains("playlist") || q.contains("save this") || q.contains("save mix")) && chosen.isNotEmpty()) {
            val baseName = when {
                moodWords.isNotEmpty() -> moodWords.first().replaceFirstChar { it.uppercase() } + " Mix"
                favorites -> "My Favorites Mix"
                else -> "Assistant Mix"
            }
            var name = baseName
            var suffix = 2
            while (playlists.containsKey(name)) { name = baseName + " " + suffix; suffix++ }
            vm.playlists.create(name)
            chosen.forEach { vm.playlists.addSong(name, it.id) }
            answer += " Saved as '" + name + "'."
        }
    }

    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF21133E), Color(0xFF17152F), MaterialTheme.colorScheme.background))
        ).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Color(0xFFE3A7FF), modifier = Modifier.size(28.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Text("Music Assistant", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Private · On-device · No account required", color = Color.White.copy(0.65f), style = MaterialTheme.typography.labelSmall)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.10f)), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("What should we play?", color = Color.White, fontWeight = FontWeight.Bold)
                Text(answer, color = Color.White.copy(0.82f), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Make a relaxing playlist") }, singleLine = false,
                    shape = RoundedCornerShape(16.dp),
                )
                Button(onClick = { runPrompt(prompt) }, modifier = Modifier.fillMaxWidth(), enabled = prompt.isNotBlank()) {
                    Icon(Icons.Rounded.Send, null)
                    Text("  Ask Music Assistant")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("Play my favourites", "Relaxing mix", "Workout songs").forEach { chip ->
                        TextButton(onClick = { prompt = chip; runPrompt(chip) }) {
                            Text(chip, color = Color(0xFFE3A7FF), maxLines = 1)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your recommended tracks", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (selected.isNotEmpty()) TextButton(onClick = { vm.playSongs(selected, 0); onOpenPlayer() }) {
                Icon(Icons.Rounded.PlayArrow, null)
                Text("Play mix")
            }
        }
        if (selected.isEmpty()) {
            Text("Try a prompt above to build a mix from your own music.", color = Color.White.copy(0.65f))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(selected, key = { it.id }) { song ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(0.09f))
                            .clickable { vm.playSongs(selected, selected.indexOf(song)); onOpenPlayer() }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.MusicNote, null, tint = Color(0xFFE3A7FF))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(song.artist + " · " + song.durationMs.asClock(), color = Color.White.copy(0.65f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White)
                    }
                }
            }
        }
    }
}
