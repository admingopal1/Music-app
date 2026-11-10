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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Slider
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
import com.app.musicplayer.audio.EnhancerSettings
import com.app.musicplayer.ui.PlayerViewModel
import com.app.musicplayer.ui.theme.ACCENT_CHOICES

@Composable
fun SettingsDialog(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val amoled by vm.amoled.collectAsStateWithLifecycle()
    val dynamic by vm.dynamicColors.collectAsStateWithLifecycle()
    val skip by vm.skipSilence.collectAsStateWithLifecycle()
    val aiDj by vm.aiDj.collectAsStateWithLifecycle()
    val accentIdx by vm.accentIdx.collectAsStateWithLifecycle()
    val lyricsFolder by vm.lyricsRepo.folder.collectAsStateWithLifecycle()
    val rgMode by vm.rgMode.collectAsStateWithLifecycle()
    val rgPreamp by vm.rgPreamp.collectAsStateWithLifecycle()
    val rgFallback by vm.rgFallback.collectAsStateWithLifecycle()
    val rgLimit by vm.rgLimit.collectAsStateWithLifecycle()
    val fadeSecs by vm.fadeSecs.collectAsStateWithLifecycle()
    val backupMsg by vm.backupMessage.collectAsStateWithLifecycle()
    var showEnhancer by remember { mutableStateOf(false) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }
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
                SettingSwitch("AI DJ (keeps the music going with similar songs)", aiDj, vm::setAiDj)
                Text("ReplayGain (even volume across songs)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Off", "Track", "Album").forEachIndexed { i, label ->
                        RgPill(label, rgMode == i) { vm.setRgMode(i) }
                    }
                }
                if (rgMode != 0) {
                    StepRow("Pre-amp (tagged songs)", rgPreamp) { vm.setRgPreamp(it) }
                    StepRow("Songs without tags", rgFallback) { vm.setRgFallback(it) }
                    SettingSwitch("Prevent clipping", rgLimit, vm::setRgLimit)
                    Text(
                        "Reads ReplayGain tags (MP3, FLAC, Ogg, Opus, M4A). Tags are picked up when a song first plays.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Fade between songs", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.setFadeSecs(fadeSecs - 1f) }) { Text("\u2212") }
                    Text(if (fadeSecs <= 0f) "Off" else "${fadeSecs.toInt()} s", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { vm.setFadeSecs(fadeSecs + 1f) }) { Text("+") }
                }
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
                Text("Sound", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { showEnhancer = true }) { Text("Sound enhancer (balance, mono, limiter) · full Hi-Fi engine is in EQ & DSP") }
                Text("Backup & restore", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { exportPicker.launch("music-player-backup.json") }) { Text("Backup") }
                    TextButton(onClick = { importPicker.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Restore") }
                }
                backupMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
    if (showEnhancer) EnhancerDialog(vm) { showEnhancer = false }
}

@Composable
private fun EnhancerDialog(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val s by vm.enhancer.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sound enhancer") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { vm.setEnhancer(EnhancerSettings()) }) { Text("Reset") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EnhSlider("Clarity (treble detail)", "%.0f%%".format(s.clarity * 100), s.clarity, 0f..1f) { vm.tweakEnhancer(s.copy(clarity = it)) }
                EnhSlider("Headphone crossfeed", "%.0f%%".format(s.crossfeed * 100), s.crossfeed, 0f..1f) { vm.tweakEnhancer(s.copy(crossfeed = it)) }
                EnhSlider("Stereo width", if (s.width == 1f) "Normal" else "%.0f%%".format(s.width * 100), s.width, 0f..2f) { vm.tweakEnhancer(s.copy(width = it)) }
                EnhSlider(
                    "Balance",
                    when { s.balance < -0.02f -> "L %.0f%%".format(-s.balance * 100); s.balance > 0.02f -> "R %.0f%%".format(s.balance * 100); else -> "Centre" },
                    s.balance, -1f..1f,
                ) { vm.setEnhancer(s.copy(balance = it)) }
                SettingSwitch("Mono output", s.mono) { vm.setEnhancer(s.copy(mono = it)) }
                SettingSwitch("Look-ahead limiter always on (prevents clipping)", s.limiter) { vm.setEnhancer(s.copy(limiter = it)) }
                Text(
                    "Works with the equalizer and ReplayGain on 16-bit and float stereo playback.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
internal fun EnhSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun RgPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(0.25f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StepRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onChange(value - 0.5f) }) { Text("\u2212") }
        Text("%+.1f dB".format(value), style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { onChange(value + 0.5f) }) { Text("+") }
    }
}

@Composable
internal fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
