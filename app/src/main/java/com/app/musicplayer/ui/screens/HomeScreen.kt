package com.app.musicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.musicplayer.data.Song
import com.app.musicplayer.data.asClock
import com.app.musicplayer.ui.PlayerViewModel

private val HomeFilters = listOf("All", "New Release", "Trending", "Most played", "Favorites")

@Composable
fun HomeScreen(
    vm: PlayerViewModel,
    onOpenPlayer: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenVideos: () -> Unit,
) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val stats by vm.stats.stats.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf("All") }

    val visibleSongs = remember(songs, query, selectedFilter, stats) {
        val searched = songs.filter {
            query.isBlank() || it.title.contains(query, true) ||
                it.artist.contains(query, true) || it.album.contains(query, true)
        }
        when (selectedFilter) {
            "Most played" -> searched.sortedByDescending { stats[it.id]?.plays ?: 0 }
            "Favorites" -> searched.filter { stats[it.id]?.favorite == true }
            "New Release" -> searched.sortedByDescending { it.dateAdded }
            "Trending" -> searched.sortedByDescending { stats[it.id]?.lastPlayed ?: 0L }
            else -> searched
        }.take(30)
    }
    val smartMix = remember(songs, stats) {
        songs.sortedWith(
            compareByDescending<Song> { stats[it.id]?.favorite == true }
                .thenByDescending { stats[it.id]?.plays ?: 0 }
                .thenByDescending { stats[it.id]?.lastPlayed ?: 0L }
        ).take(30)
    }
    val accent = MaterialTheme.colorScheme.primary

    Box(Modifier.fillMaxSize().background(
        Brush.verticalGradient(
            listOf(Color(0xFF21133E), Color(0xFF17152F), MaterialTheme.colorScheme.background)
        )
    )) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hello 👋", color = Color.White.copy(0.72f), style = MaterialTheme.typography.labelLarge)
                        Text("Your Music Universe", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onOpenLibrary, modifier = Modifier.clip(CircleShape).background(Color.White.copy(0.12f))) {
                        Icon(Icons.Rounded.LibraryMusic, "Open library", tint = Color.White)
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = onOpenVideos, modifier = Modifier.clip(CircleShape).background(Color.White.copy(0.12f))) {
                        Icon(Icons.Rounded.VideoLibrary, "Videos and downloads", tint = Color.White)
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search songs, artists, albums") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                )
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HomeFilters.forEach { label ->
                        val active = selectedFilter == label
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (active) accent else Color.White.copy(0.10f))
                                .clickable { selectedFilter = label }
                                .padding(horizontal = 17.dp, vertical = 10.dp),
                        ) {
                            Text(label, color = if (active) Color.White else Color.White.copy(0.82f), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF8B3DCC), Color(0xFF3E4CB8), Color(0xFFCB4D9B))))
                        .clickable {
                            if (smartMix.isNotEmpty()) {
                                vm.playSongs(smartMix, 0)
                                onOpenPlayer()
                            }
                        }.padding(20.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White)
                        Text("  SMART MIX", color = Color.White.copy(0.85f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Discover your next favourite", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("A personal mix built from your favourites and listening history", color = Color.White.copy(0.82f), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, "Play Smart Mix", tint = Color(0xFF6D35B5), modifier = Modifier.size(28.dp))
                        }
                        Text("  Play your mix", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("${smartMix.size} tracks", color = Color.White.copy(0.85f))
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Made for your mood", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Quick ways to explore your music", color = Color.White.copy(0.65f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeatureTile("Your Library", "Songs & albums", Icons.Rounded.LibraryMusic, Modifier.weight(1f), onOpenLibrary)
                    FeatureTile("Video Hub", "YouTube & downloads", Icons.Rounded.Download, Modifier.weight(1f), onOpenVideos)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Top daily tracks", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("See all", color = accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onOpenLibrary))
                }
            }
            if (visibleSongs.isEmpty()) {
                item {
                    Text(
                        if (songs.isEmpty()) "Your music will appear here after scanning your device." else "No tracks match this filter.",
                        color = Color.White.copy(0.7f), modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            } else {
                itemsIndexed(visibleSongs.take(8), key = { _, song -> song.id }) { index, song ->
                    HomeSongRow(
                        song = song,
                        current = ui.song?.id == song.id,
                        favorite = stats[song.id]?.favorite == true,
                        onClick = { vm.playSongs(visibleSongs, index); onOpenPlayer() },
                        onFavorite = { vm.stats.toggleFavorite(song.id) },
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AI-ready listening", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Smart Mix uses on-device favourites and play history today. Cloud AI DJ, natural-language music search and mood-based generation can be added as a connected AI layer without changing your music library.",
                    color = Color.White.copy(0.68f), style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun FeatureTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color.White.copy(0.16f), Color.White.copy(0.07f))))
            .clickable(onClick = onClick).padding(15.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color.White.copy(0.66f), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HomeSongRow(
    song: Song,
    current: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(0.085f))
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(13.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFB54FCB), Color(0xFF4568C7)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${song.artist} · ${song.durationMs.asClock()}", color = Color.White.copy(0.65f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onFavorite) {
            Icon(Icons.Rounded.Favorite, "Toggle favourite", tint = if (favorite) Color(0xFFFF7BB8) else Color.White.copy(0.45f))
        }
        Icon(Icons.Rounded.PlayCircle, "Play", tint = Color.White.copy(0.85f), modifier = Modifier.size(27.dp))
    }
}
