package com.app.musicplayer.ui.screens

import com.app.musicplayer.ui.theme.GlassDialogShape
import com.app.musicplayer.ui.theme.GlassDialogColor
import com.app.musicplayer.ui.theme.glass
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.musicplayer.ui.PlayerViewModel

@Composable
fun PlaylistsScreen(vm: PlayerViewModel, onOpenPlayer: () -> Unit) {
    val lists by vm.playlists.lists.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your Collections", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                Text("${lists.size} playlists · Your personal soundtrack", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f))
            }
            FilledTonalButton(onClick = { showCreate = true }) {
                Icon(Icons.Rounded.Add, null)
                Text("New")
            }
        }

        if (lists.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp).glass(RoundedCornerShape(24.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFFE3B5FF))
                Text("Your soundtrack starts here", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("Create a playlist, then add songs from the Library using a song's menu.", color = Color.White.copy(alpha = 0.68f), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                FilledTonalButton(onClick = { showCreate = true }, shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.Add, null)
                    Text("  Create playlist")
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(lists.keys.toList(), key = { it }) { name ->
                val ids = lists[name].orEmpty()
                val isOpen = expanded == name
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded = if (isOpen) null else name },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${ids.size} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.playPlaylist(name); onOpenPlayer() }, enabled = ids.isNotEmpty()) {
                            Icon(Icons.Rounded.PlayArrow, "Play playlist")
                        }
                        IconButton(onClick = { vm.playlists.delete(name) }) { Icon(Icons.Rounded.Delete, "Delete playlist") }
                    }
                    AnimatedVisibility(isOpen) {
                        Column(Modifier.padding(top = 8.dp)) {
                            val songs = ids.mapNotNull { vm.songById(it) }
                            if (songs.isEmpty()) {
                                Text("Empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            songs.forEachIndexed { i, song ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { vm.playSongs(songs, i); onOpenPlayer() },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    IconButton(onClick = { vm.playlists.removeSong(name, song.id) }) {
                                        Icon(Icons.Rounded.Close, "Remove")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
        containerColor = GlassDialogColor,
        shape = GlassDialogShape,
            onDismissRequest = { showCreate = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.playlists.create(name); showCreate = false },
                    enabled = name.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } },
        )
    }
}
