package com.app.musicplayer.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.musicplayer.data.Song
import com.app.musicplayer.data.asClock
import com.app.musicplayer.ui.PlayerViewModel

private val SORT_LABELS = listOf("Title", "Artist", "Album", "Duration (longest first)")
private val MODES = listOf("Songs", "Albums", "Artists", "Folders", "Favorites", "Recent", "Most played", "New")

private data class Group(val key: String, val title: String, val subtitle: String, val songs: List<Song>)

@Composable
fun LibraryScreen(vm: PlayerViewModel, onOpenPlayer: () -> Unit) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val playlists by vm.playlists.lists.collectAsStateWithLifecycle()
    val stats by vm.stats.stats.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sortIdx by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showStream by remember { mutableStateOf(false) }

    val sorted = remember(songs, query, sortIdx) {
        val base = if (query.isBlank()) songs
        else songs.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
        }
        when (sortIdx) {
            1 -> base.sortedBy { it.artist.lowercase() }
            2 -> base.sortedBy { it.album.lowercase() }
            3 -> base.sortedByDescending { it.durationMs }
            else -> base.sortedBy { it.title.lowercase() }
        }
    }
    // Smart lists (modes 4..7)
    val smart: List<Song>? = remember(sorted, stats, mode) {
        when (mode) {
            4 -> sorted.filter { stats[it.id]?.favorite == true }
            5 -> sorted.filter { (stats[it.id]?.lastPlayed ?: 0L) > 0L }
                .sortedByDescending { stats[it.id]?.lastPlayed ?: 0L }.take(100)
            6 -> sorted.filter { (stats[it.id]?.plays ?: 0) > 0 }
                .sortedByDescending { stats[it.id]?.plays ?: 0 }.take(100)
            7 -> sorted.sortedByDescending { it.dateAdded }.take(100)
            else -> null
        }
    }
    // Grouped views (modes 1..3)
    val groups = remember(sorted, mode) {
        when (mode) {
            1 -> sorted.groupBy { it.album }.map { (k, v) -> Group(k, k, v.first().artist, v) }
            2 -> sorted.groupBy { it.artist }.map { (k, v) ->
                Group(k, k, "${v.map { it.album }.distinct().size} albums", v)
            }
            3 -> sorted.groupBy { it.folder }.map { (k, v) ->
                Group(k, k.substringAfterLast('/').ifBlank { "Unknown folder" }, k, v)
            }
            else -> emptyList()
        }.sortedBy { it.title.lowercase() }
    }
    val inGroupMode = mode in 1..3

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF21133E), Color(0xFF17152F), MaterialTheme.colorScheme.background)))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { showStream = true }) { Icon(Icons.Rounded.Link, "Play stream URL") }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.SortByAlpha, "Sort") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SORT_LABELS.forEachIndexed { i, label ->
                        DropdownMenuItem(
                            text = {
                                Text(label, color = if (i == sortIdx) MaterialTheme.colorScheme.primary else Color.Unspecified)
                            },
                            onClick = { sortIdx = i; sortMenu = false },
                        )
                    }
                }
            }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Settings, "Settings") }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LibraryMetric(Modifier.weight(1f), songs.size.toString(), "TRACKS", Icons.Rounded.MusicNote)
            LibraryMetric(Modifier.weight(1f), songs.count { stats[it.id]?.favorite == true }.toString(), "FAVORITES", Icons.Rounded.Favorite)
            LibraryMetric(Modifier.weight(1f), songs.map { it.artist }.distinct().size.toString(), "ARTISTS", Icons.Rounded.GraphicEq)
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Search songs, artists, albums") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MODES.forEachIndexed { i, label ->
                ModePill(label, mode == i) { mode = i; openGroup = null }
            }
        }

        if (songs.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No music found. Grant audio permission, then scan again.",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = vm::loadLibrary, modifier = Modifier.padding(top = 16.dp)) { Text("Scan again") }
            }
        } else if (inGroupMode && openGroup == null) {
            Text(
                "${groups.size} ${if (mode == 1) "albums" else if (mode == 2) "artists" else "folders"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(groups, key = { _, g -> g.key }) { _, g ->
                    GroupRow(g.title, g.subtitle, g.songs.size) { openGroup = g.key }
                }
            }
        } else {
            val shown = when {
                inGroupMode -> groups.firstOrNull { it.key == openGroup }?.songs.orEmpty()
                smart != null -> smart
                else -> sorted
            }
            if (inGroupMode) {
                Row(
                    Modifier.fillMaxWidth().clickable { openGroup = null }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        groups.firstOrNull { it.key == openGroup }?.title.orEmpty(),
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (shown.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                    }
                    Text(
                        when {
                            query.isNotBlank() -> "No matching tracks"
                            mode == 4 -> "No favorites yet"
                            mode == 5 -> "Nothing played yet"
                            mode == 6 -> "No listening history yet"
                            else -> "Nothing here yet"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        when {
                            query.isNotBlank() -> "Try another title, artist or album name."
                            mode == 4 -> "Open a song menu and add tracks to your favorites."
                            mode == 5 || mode == 6 -> "Play a few songs and your listening activity will appear here."
                            else -> "Your music will appear here when it is available on this device."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    if (query.isNotBlank()) {
                        Text(
                            "Clear search",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp).clickable { query = "" },
                        )
                    }
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                itemsIndexed(shown, key = { _, s -> s.id }) { index, song ->
                    SongRow(
                        song = song,
                        current = ui.song?.id == song.id,
                        favorite = stats[song.id]?.favorite == true,
                        playlistNames = playlists.keys.toList(),
                        onClick = { vm.playSongs(shown, index); onOpenPlayer() },
                        onFavorite = { vm.stats.toggleFavorite(song.id) },
                        onPlayNext = { vm.playNext(song) },
                        onAddToQueue = { vm.addToQueue(song) },
                        onAddToPlaylist = { vm.playlists.addSong(it, song.id) },
                    )
                }
            }
        }
    }

    if (showSettings) SettingsDialog(vm) { showSettings = false }
    if (showStream) StreamDialog(onPlay = { vm.playUrl(it); onOpenPlayer() }) { showStream = false }
}

@Composable
private fun LibraryMetric(
    modifier: Modifier,
    value: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.075f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun ModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val containerColor by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = spring(), label = "library-filter-color",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = spring(), label = "library-filter-content",
    )
    Box(
        Modifier.animateContentSize(animationSpec = spring())
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = contentColor, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GroupRow(title: String, subtitle: String, count: Int, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp)
            .animateContentSize(animationSpec = spring())
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, Color.White.copy(alpha = 0.055f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(
                Brush.linearGradient(listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.10f)))
            ),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Folder, null, tint = accent) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SongRow(
    song: Song, current: Boolean, favorite: Boolean, playlistNames: List<String>,
    onClick: () -> Unit, onFavorite: () -> Unit, onPlayNext: () -> Unit, onAddToQueue: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
            .animateContentSize(animationSpec = spring())
            .clip(RoundedCornerShape(18.dp))
            .background(if (current) accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.055f))
            .border(1.dp, if (current) accent.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.055f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(accent.copy(0.55f), accent.copy(0.15f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (current) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote, null, tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold, color = if (current) accent else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (favorite) Icon(Icons.Rounded.Favorite, "Favorite", tint = accent, modifier = Modifier.size(16.dp).padding(end = 0.dp))
        Text(
            song.durationMs.asClock(),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (favorite) "Remove from favorites" else "Favorite") },
                    onClick = { onFavorite(); menu = false },
                )
                DropdownMenuItem(text = { Text("Play next") }, onClick = { onPlayNext(); menu = false })
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { onAddToQueue(); menu = false })
                if (playlistNames.isEmpty()) {
                    DropdownMenuItem(text = { Text("Create a playlist first") }, onClick = { menu = false }, enabled = false)
                } else {
                    playlistNames.forEach { name ->
                        DropdownMenuItem(
                            text = { Text("Add to $name") },
                            onClick = { onAddToPlaylist(name); menu = false },
                        )
                    }
                }
            }
        }
    }
}
