package com.app.musicplayer.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.musicplayer.lyrics.Lyrics
import com.app.musicplayer.ui.PlayerViewModel
import kotlin.math.abs

/** Full-screen lyrics: synced (.lrc) lines follow the song, tap a line to seek. */
@Composable
fun LyricsDialog(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val song = ui.song
    val accent = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var offsetMs by remember { mutableLongStateOf(0L) }

    val lyrics by produceState<Lyrics?>(null, song?.id, reload) {
        loading = true
        value = if (song != null) vm.lyricsRepo.load(song) else null
        loading = false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && song != null) vm.importLyrics(song.id, uri) { imported ->
            if (imported) reload++
            else Toast.makeText(context, "Choose a valid .lrc/.txt text file under 2 MB.", Toast.LENGTH_LONG).show()
        }
    }

    // Positive manual offset delays the displayed lyrics relative to playback.
    val pos = (ui.positionMs - offsetMs).coerceAtLeast(0L)
    val current = remember(lyrics, pos) {
        val l = lyrics
        if (l != null && l.synced) l.lines.indexOfLast { it.timeMs <= pos } else -1
    }
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF21133E), Color(0xFF17152F), MaterialTheme.colorScheme.background))), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        .background(Color.White.copy(alpha = 0.055f), RoundedCornerShape(18.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(song?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(
                            song?.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Load lyrics") }
                }

                val l = lyrics
                when {
                    loading -> Text(
                        "Looking for lyrics…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = TextAlign.Center,
                    )
                    l == null -> Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "No lyrics found for this song.\n\nLoad an .lrc/.txt file for it, or choose a folder with your .lrc files in Settings (names should match the songs).",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.055f), RoundedCornerShape(22.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(22.dp)).padding(22.dp),
                        )
                        Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 20.dp)) {
                            Text("Load .lrc/.txt for this song")
                        }
                    }
                    else -> {
                        if (l.synced) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedButton(onClick = { offsetMs -= 500 }) { Text("-0.5s") }
                                TextButton(onClick = { offsetMs = 0 }) {
                                    Text(
                                        "Sync " + (if (offsetMs >= 0) "+" else "-") + "%.1fs".format(abs(offsetMs) / 1000f),
                                        color = if (offsetMs == 0L) MaterialTheme.colorScheme.onSurfaceVariant else accent,
                                    )
                                }
                                OutlinedButton(onClick = { offsetMs += 500 }) { Text("+0.5s") }
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            itemsIndexed(l.lines) { i, line ->
                                val isCurrent = i == current
                                Text(
                                    line.text.ifBlank { "♪" },
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = l.synced) {
                                        vm.seekTo((line.timeMs + offsetMs).coerceAtLeast(0L))
                                    },
                                    textAlign = if (l.synced) TextAlign.Center else TextAlign.Start,
                                    fontSize = if (isCurrent) 24.sp else 19.sp,
                                    lineHeight = if (isCurrent) 30.sp else 25.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = when {
                                        !l.synced -> MaterialTheme.colorScheme.onSurface
                                        isCurrent -> accent
                                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
