package com.app.musicplayer.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.musicplayer.ui.PlayerViewModel
import com.app.musicplayer.ui.theme.ACCENT_CHOICES

@Composable
fun SettingsDialog(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val amoled by vm.amoled.collectAsStateWithLifecycle()
    val dynamic by vm.dynamicColors.collectAsStateWithLifecycle()
    val skip by vm.skipSilence.collectAsStateWithLifecycle()
    val accentIdx by vm.accentIdx.collectAsStateWithLifecycle()
    val lyricsFolder by vm.lyricsRepo.folder.collectAsStateWithLifecycle()
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.lyricsRepo.setTree(uri)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SettingSwitch("AMOLED black theme", amoled, vm::setAmoled)
                SettingSwitch("Colours from album art", dynamic, vm::setDynamicColors)
                SettingSwitch("Skip silence", skip, vm::setSkipSilence)
                Text("Accent colour", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ACCENT_CHOICES.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(c)
                                .border(if (i == accentIdx) 3.dp else 0.dp, Color.White, CircleShape)
                                .clickable { vm.setAccentIdx(i) },
                        )
                    }
                }
                Text(
                    "Accent colour is used when album-art colours are off.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Lyrics folder (.lrc files)", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { treePicker.launch(null) }) {
                        Text(if (lyricsFolder == null) "Choose folder" else "Change folder")
                    }
                    if (lyricsFolder != null) {
                        TextButton(onClick = { vm.lyricsRepo.setTree(null) }) { Text("Clear") }
                    }
                }
                lyricsFolder?.let {
                    Text(
                        Uri.parse(it).lastPathSegment.orEmpty(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun StreamDialog(onPlay: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    val valid = url.trim().startsWith("http")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play network stream") },
        text = {
            Column {
                Text(
                    "Paste a direct audio or radio stream URL (http/https).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, singleLine = true,
                    placeholder = { Text("https://...") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onPlay(url.trim()); onDismiss() }) { Text("Play") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun QueueDialog(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var version by remember { mutableIntStateOf(0) }
    val items = remember(version, ui.song?.id) { vm.queueItems() }
    val current = vm.currentIndex()
    val accent = MaterialTheme.colorScheme.primary

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Queue (${items.size})") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            if (items.isEmpty()) {
                Text("Queue is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(items, key = { _, q -> q.index }) { pos, q ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.jumpTo(q.index); version++ }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (q.index == current) {
                                Icon(Icons.Rounded.GraphicEq, null, tint = accent, modifier = Modifier.size(20.dp))
                            } else {
                                Text("${pos + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.size(20.dp))
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(
                                    q.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (q.index == current) accent else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    q.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.moveInQueue(q.index, q.index - 1); version++ }, enabled = pos > 0) {
                                Icon(Icons.Rounded.ArrowUpward, "Move up", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { vm.moveInQueue(q.index, q.index + 1); version++ }, enabled = pos < items.lastIndex) {
                                Icon(Icons.Rounded.ArrowDownward, "Move down", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { vm.removeFromQueue(q.index); version++ }) {
                                Icon(Icons.Rounded.Close, "Remove", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
    )
}
