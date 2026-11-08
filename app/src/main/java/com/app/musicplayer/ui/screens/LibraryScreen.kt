package com.app.musicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
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

@Composable
fun LibraryScreen(vm: PlayerViewModel, onOpenPlayer: () -> Unit) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val playlists by vm.playlists.lists.collectAsStateWithLifecycle()
    val dynamic by vm.dynamicColors.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sortIdx by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableIntStateOf(0) }            // 0 = Songs, 1 = Folders
    var openFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }

    val filtered = remember(songs, query, sortIdx) {
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
    val folders = remember(filtered) {
        filtered.groupBy { it.folder }.toList().sortedBy { it.first.lowercase() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.setDynamicColors(!dynamic) }) {
                Icon(
                    Icons.Rounded.Palette, "Album colours",
                    tint = if (dynamic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        }
        Text(
            "${songs.size} songs on this device", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Search songs, artists, albums") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModePill("Songs", mode == 0) { mode = 0; openFolder = null }
            ModePill("Folders", mode == 1) { mode = 1 }
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
        } else if (mode == 1 && openFolder == null) {
            LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
                itemsIndexed(folders, key = { _, f -> f.first }) { _, (path, list) ->
                    FolderRow(
                        name = path.substringAfterLast('/').ifBlank { "Unknown folder" },
                        path = path, count = list.size,
                        onClick = { openFolder = path },
                    )
                }
            }
        } else {
            val shown = if (mode == 1) filtered.filter { it.folder == openFolder } else filtered
            if (mode == 1) {
                Row(
                    Modifier.fillMaxWidth().clickable { openFolder = null }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        openFolder.orEmpty().substringAfterLast('/').ifBlank { "Unknown folder" },
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
                itemsIndexed(shown, key = { _, s -> s.id }) { index, song ->
                    SongRow(
                        song = song,
                        current = ui.song?.id == song.id,
                        playlistNames = playlists.keys.toList(),
                        onClick = { vm.playSongs(shown, index); onOpenPlayer() },
                        onAddToPlaylist = { vm.playlists.addSong(it, song.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(0.25f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FolderRow(name: String, path: String, count: Int, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(0.18f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Folder, null, tint = accent) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                path, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SongRow(
    song: Song, current: Boolean, playlistNames: List<String>,
    onClick: () -> Unit, onAddToPlaylist: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
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
        Text(song.durationMs.asClock(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
