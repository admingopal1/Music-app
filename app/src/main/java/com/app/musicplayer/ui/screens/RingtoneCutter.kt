package com.app.musicplayer.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.app.musicplayer.data.Song
import com.app.musicplayer.data.asClock
import com.app.musicplayer.ringtone.Hook
import com.app.musicplayer.ringtone.HookFinder
import com.app.musicplayer.ringtone.RingtoneEngine
import com.app.musicplayer.ringtone.SongAnalysis
import com.app.musicplayer.ringtone.ToneType
import com.app.musicplayer.ui.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val CLIP_LENGTHS = listOf(15, 20, 30, 40)

/** Smart AI ringtone maker: auto-detects the hook, lets you trim it, previews and saves as ringtone/notification/alarm. */
@Composable
fun RingtoneCutterDialog(vm: PlayerViewModel, song: Song, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary
    val duration = song.durationMs.coerceAtLeast(1L)

    var analysis by remember { mutableStateOf<SongAnalysis?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var hook by remember { mutableStateOf<Hook?>(null) }
    var clipSec by remember { mutableStateOf(30) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(minOf(30_000L, duration)) }
    var fadeIn by remember { mutableStateOf(true) }
    var fadeOut by remember { mutableStateOf(true) }
    var type by remember { mutableStateOf(ToneType.RINGTONE) }
    var saving by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var playPos by remember { mutableLongStateOf(-1L) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }

    var pausedMain by remember { mutableStateOf(false) }

    fun stopPreview(resumeMusic: Boolean = false) {
        player.value?.let { preview ->
            runCatching { preview.stop() }
            runCatching { preview.release() }
        }
        player.value = null; playing = false; playPos = -1L
        if (resumeMusic && pausedMain) {
            pausedMain = false
            if (!vm.ui.value.isPlaying) vm.togglePlay()
        }
    }

    fun aiPick(a: SongAnalysis, sec: Int) {
        val h = HookFinder.find(a.rms, a.bands, a.frameMs, sec * 1000L)
        hook = h
        startMs = h.startMs.coerceIn(0, duration)
        endMs = h.endMs.coerceIn(startMs + 1000, duration)
    }

    LaunchedEffect(song.id) {
        try {
            val a = withContext(Dispatchers.Default) {
                RingtoneEngine.analyse(ctx, song.uri, keepGoing = { isActive }) { p -> progress = p }
            }
            analysis = a
            aiPick(a, clipSec)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Couldn't analyse this song (${e.message ?: e.javaClass.simpleName}). You can still trim it manually."
        }
    }
    DisposableEffect(Unit) { onDispose { stopPreview(resumeMusic = true) } }

    fun startPreview() {
        stopPreview()
        if (vm.ui.value.isPlaying) { vm.togglePlay(); pausedMain = true }
        val mp = MediaPlayer()
        if (runCatching { mp.setDataSource(ctx, song.uri); mp.prepare() }.isFailure) {
            mp.release()
            Toast.makeText(ctx, "Can't preview this song", Toast.LENGTH_SHORT).show()
            return
        }
        player.value = mp
        mp.seekTo(startMs.toInt())
        mp.start()
        playing = true
        scope.launch {
            while (isActive && player.value === mp) {
                val pos = runCatching { mp.currentPosition.toLong() }.getOrDefault(endMs)
                playPos = pos
                val v = when {
                    fadeIn && pos - startMs < 1500 -> ((pos - startMs) / 1500f).coerceIn(0f, 1f)
                    fadeOut && endMs - pos < 2000 -> ((endMs - pos) / 2000f).coerceIn(0f, 1f)
                    else -> 1f
                }
                runCatching { mp.setVolume(v, v) }
                val finished = runCatching { !mp.isPlaying }.getOrDefault(true)
                if (pos >= endMs || finished) { stopPreview(resumeMusic = true); break }
                delay(40)
            }
        }
    }

    fun doSave(setDefault: Boolean) {
        stopPreview()
        saving = true
        scope.launch {
            val title = "${song.title} (${type.label})"
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    RingtoneEngine.export(
                        ctx, song.uri, startMs, endMs, if (fadeIn) 1500 else 0, if (fadeOut) 2000 else 0,
                        title, song.artist, type,
                    )
                }
            }
            saving = false
            result.onSuccess { uri ->
                if (setDefault) {
                    runCatching { RingtoneEngine.setDefault(ctx, uri, type) }
                        .onSuccess { Toast.makeText(ctx, "Saved and set as default ${type.label.lowercase()}", Toast.LENGTH_LONG).show() }
                        .onFailure { Toast.makeText(ctx, "Saved, but couldn't set it as default", Toast.LENGTH_LONG).show() }
                } else {
                    Toast.makeText(ctx, "Saved to ${type.label}s: $title", Toast.LENGTH_LONG).show()
                }
            }.onFailure { Toast.makeText(ctx, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    // Android 9 and older need storage permission to write into the shared Ringtones folder.
    var pendingDefault by remember { mutableStateOf<Boolean?>(null) }
    val storagePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val d = pendingDefault
        pendingDefault = null
        if (ok && d != null) doSave(d) else Toast.makeText(ctx, "Storage permission is needed to save ringtones", Toast.LENGTH_LONG).show()
    }
    fun save(setDefault: Boolean) {
        if (setDefault && !RingtoneEngine.canSetDefault(ctx)) {
            stopPreview()
            Toast.makeText(ctx, "Allow \"Modify system settings\", then tap Save & set default again.", Toast.LENGTH_LONG).show()
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDefault = setDefault
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else doSave(setDefault)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = accent)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text("AI Ringtone Maker", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "${song.title} · ${song.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
                }
                Spacer(Modifier.height(16.dp))

                val a = analysis
                when {
                    a == null && error == null -> {
                        Text("AI is listening to the song to find the best part…", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    else -> hook?.let { h ->
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(accent.copy(0.15f)).padding(12.dp)
                        ) {
                            Text(
                                "${h.reason} at ${h.startMs.asClock()} · confidence ${(h.confidence * 100).toInt()}%",
                                color = accent, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Waveform(
                    rms = a?.rms, durationMs = duration, startMs = startMs, endMs = endMs, playPos = playPos,
                    onChange = { s, e -> startMs = s; endMs = e; stopPreview() },
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Start ${startMs.asClock()}", style = MaterialTheme.typography.labelMedium)
                    Text("${"%.1f".format((endMs - startMs) / 1000f)} s", style = MaterialTheme.typography.labelMedium, color = accent)
                    Text("End ${endMs.asClock()}", style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NudgeButton("Start −½s") { startMs = (startMs - 500).coerceAtLeast(0) }
                    NudgeButton("Start +½s") { startMs = (startMs + 500).coerceAtMost(endMs - 1000) }
                    NudgeButton("End −½s") { endMs = (endMs - 500).coerceAtLeast(startMs + 1000) }
                    NudgeButton("End +½s") { endMs = (endMs + 500).coerceAtMost(duration) }
                }

                Spacer(Modifier.height(16.dp))
                Text("Length", fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CLIP_LENGTHS.forEach { sec ->
                        Chip("${sec}s", clipSec == sec) {
                            clipSec = sec
                            stopPreview()
                            val an = analysis
                            if (an != null) aiPick(an, sec) else endMs = (startMs + sec * 1000L).coerceAtMost(duration)
                        }
                    }
                }
                if (analysis != null) {
                    FilledTonalButton(onClick = { stopPreview(); aiPick(analysis!!, clipSec) }, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, null)
                        Spacer(Modifier.width(6.dp))
                        Text("AI pick best part")
                    }
                }

                Spacer(Modifier.height(12.dp))
                SettingSwitch("Fade in (1.5 s)", fadeIn) { fadeIn = it }
                SettingSwitch("Fade out (2 s)", fadeOut) { fadeOut = it }

                Spacer(Modifier.height(8.dp))
                Text("Save as", fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToneType.entries.forEach { t -> Chip(t.label, type == t) { type = t } }
                }

                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = { if (playing) stopPreview(resumeMusic = true) else startPreview() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (playing) "Stop preview" else "Preview")
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !saving, onClick = { save(false) }, modifier = Modifier.weight(1f)) {
                        Text(if (saving) "Saving…" else "Save")
                    }
                    Button(enabled = !saving, onClick = { save(true) }, modifier = Modifier.weight(1f)) {
                        Text("Save & set default")
                    }
                }
            }
        }
    }
}

@Composable
private fun Waveform(
    rms: FloatArray?, durationMs: Long, startMs: Long, endMs: Long, playPos: Long,
    onChange: (Long, Long) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.35f)
    val bg = MaterialTheme.colorScheme.surfaceVariant
    var dragging by remember { mutableStateOf(0) } // 1 = start handle, 2 = end handle, 3 = whole selection
    val cur by androidx.compose.runtime.rememberUpdatedState(Pair(startMs, endMs))

    Canvas(
        Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .pointerInput(durationMs) {
                fun toMs(x: Float) = (x / size.width * durationMs).toLong().coerceIn(0, durationMs)
                detectDragGestures(
                    onDragStart = { o ->
                        val (s, e) = cur
                        val t = toMs(o.x)
                        val tol = durationMs * 0.04f
                        dragging = when {
                            abs(t - s) < tol && abs(t - s) <= abs(t - e) -> 1
                            abs(t - e) < tol -> 2
                            t in s..e -> 3
                            else -> 0
                        }
                    },
                    onDragEnd = { dragging = 0 },
                ) { change, drag ->
                    change.consume()
                    val (s, e) = cur
                    val d = (drag.x / size.width * durationMs).toLong()
                    when (dragging) {
                        1 -> onChange((s + d).coerceIn(0, e - 1000), e)
                        2 -> onChange(s, (e + d).coerceIn(s + 1000, durationMs))
                        3 -> { val len = e - s; val ns = (s + d).coerceIn(0, durationMs - len); onChange(ns, ns + len) }
                    }
                }
            }
            .pointerInput(durationMs) {
                detectTapGestures { o ->
                    val (s, e) = cur
                    val len = e - s
                    val ns = ((o.x / size.width * durationMs).toLong() - len / 2).coerceIn(0, (durationMs - len).coerceAtLeast(0))
                    onChange(ns, ns + len)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val sx = startMs.toFloat() / durationMs * w
        val ex = endMs.toFloat() / durationMs * w
        drawRect(accent.copy(0.18f), topLeft = Offset(sx, 0f), size = Size(ex - sx, h))
        if (rms != null && rms.isNotEmpty()) {
            val bars = (w / 6f).toInt().coerceAtLeast(1)
            val peak = rms.max().coerceAtLeast(1e-4f)
            for (i in 0 until bars) {
                val from = i * rms.size / bars
                val to = ((i + 1) * rms.size / bars).coerceAtLeast(from + 1).coerceAtMost(rms.size)
                var m = 0f
                for (k in from until to) if (rms[k] > m) m = rms[k]
                val bh = (m / peak).coerceIn(0.03f, 1f) * h * 0.9f
                val x = i * w / bars
                drawRect(if (x in sx..ex) accent else dim, Offset(x, (h - bh) / 2), Size(w / bars * 0.6f, bh))
            }
        }
        drawRect(accent, Offset(sx - 2f, 0f), Size(4f, h))
        drawRect(accent, Offset(ex - 2f, 0f), Size(4f, h))
        if (playPos >= 0) {
            val px = playPos.toFloat() / durationMs * w
            drawRect(androidx.compose.ui.graphics.Color.White, Offset(px - 1f, 0f), Size(2f, h))
        }
    }
}

@Composable
private fun NudgeButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp)
    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(0.25f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    }
}
