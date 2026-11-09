package com.app.musicplayer.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.app.musicplayer.data.SongStats
import com.app.musicplayer.data.asClock
import com.app.musicplayer.ui.PlayerUi
import com.app.musicplayer.ui.PlayerViewModel
import kotlin.math.sin

/** Frosted panel: translucent gradient + hairline border, sitting on top of the blurred album-art backdrop. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(0.14f), Color.White.copy(0.05f))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(0.35f), Color.White.copy(0.05f))), shape)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun MainPlayerScreen(vm: PlayerViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val art by vm.art.collectAsStateWithLifecycle()
    val accent = MaterialTheme.colorScheme.primary
    val artImage = remember(art) { art?.asImageBitmap() }
    val song = ui.song

    Box(Modifier.fillMaxSize()) {
        // Backdrop: heavily blurred cover (Modifier.blur is a no-op below Android 12; the gradient still works).
        if (artImage != null) {
            Image(
                artImage, null,
                Modifier.fillMaxSize().blur(70.dp).alpha(0.45f),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.18f), MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
                )
            )
        )

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val vinylSize = minOf(maxWidth * 0.78f, 300.dp)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(12.dp))
                Vinyl(artImage, ui.isPlaying, vinylSize, accent)
                Spacer(Modifier.height(24.dp))

                Text(
                    song?.title ?: "Nothing playing",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                Text(
                    song?.let { "${it.artist} · ${it.album}" } ?: "Pick a track from the Library",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))

                WaveSpectrum(ui.isPlaying, accent, Modifier.fillMaxWidth().height(48.dp))
                Spacer(Modifier.height(16.dp))

                GlassCard(Modifier.fillMaxWidth()) {
                    ProgressBar(ui.positionMs, ui.durationMs, accent, vm::seekTo)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PressIcon(
                            Icons.Rounded.Shuffle, "Shuffle", vm::toggleShuffle,
                            tint = if (ui.shuffle) accent else Color.White.copy(0.7f), box = 44.dp, iconSize = 22.dp,
                        )
                        PressIcon(Icons.Rounded.SkipPrevious, "Previous", vm::previous, iconSize = 34.dp)
                        PlayPauseButton(ui.isPlaying, accent, vm::togglePlay)
                        PressIcon(Icons.Rounded.SkipNext, "Next", vm::next, iconSize = 34.dp)
                        PressIcon(
                            if (ui.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            "Repeat", vm::cycleRepeat,
                            tint = if (ui.repeatMode != Player.REPEAT_MODE_OFF) accent else Color.White.copy(0.7f),
                            box = 44.dp, iconSize = 22.dp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                PlayerExtras(vm, ui, accent)
                Spacer(Modifier.height(12.dp))
                PlayerTools(vm, ui, accent)
                val rgLabel = remember(ui.song?.id, ui.positionMs / 3000) {
                    ui.song?.takeIf { it.id >= 0 }?.let { vm.replayGainLabel(it.id) }
                }
                if (rgLabel != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                        Text("ReplayGain  $rgLabel", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(0.6f))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ VLC-style tools

@Composable
private fun PlayerExtras(vm: PlayerViewModel, ui: PlayerUi, accent: Color) {
    val stats by vm.stats.stats.collectAsStateWithLifecycle()
    val song = ui.song
    val st = song?.let { stats[it.id] } ?: SongStats()
    val real = song != null && song.id >= 0     // streams have no stats
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (real) vm.stats.toggleFavorite(song!!.id) }) {
                Icon(
                    if (st.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite",
                    tint = if (st.favorite) accent else Color.White.copy(0.75f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (i in 1..5) {
                    Icon(
                        if (i <= st.rating) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Rate $i",
                        tint = if (i <= st.rating) accent else Color.White.copy(0.55f),
                        modifier = Modifier.size(30.dp).clickable { if (real) vm.stats.setRating(song!!.id, i) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showLyrics = true }) {
                    Icon(Icons.Rounded.Subtitles, "Lyrics", tint = Color.White.copy(0.85f))
                }
                IconButton(onClick = { showQueue = true }) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", tint = Color.White.copy(0.85f))
                }
            }
        }
    }
    if (showQueue) QueueDialog(vm) { showQueue = false }
    if (showLyrics) LyricsDialog(vm) { showLyrics = false }
}

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)
private val SLEEP_MINUTES = listOf(15, 30, 45, 60, 90)

private fun speedText(s: Float): String = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

@Composable
private fun PlayerTools(vm: PlayerViewModel, ui: PlayerUi, accent: Color) {
    val abA by vm.abA.collectAsStateWithLifecycle()
    val abB by vm.abB.collectAsStateWithLifecycle()
    val sleepLeft by vm.sleepLeftMs.collectAsStateWithLifecycle()
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }

    val abLabel = when {
        abA < 0 -> "A-B"
        abB < 0 -> "A " + abA.asClock()
        else -> abA.asClock() + "-" + abB.asClock()
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                ToolPill(Icons.Rounded.Speed, speedText(ui.speed), ui.speed != 1f, accent) { speedMenu = true }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    SPEEDS.forEach { sp ->
                        DropdownMenuItem(
                            text = { Text(speedText(sp)) },
                            onClick = { vm.setSpeed(sp); speedMenu = false },
                        )
                    }
                }
            }
            ToolPill(Icons.Rounded.Repeat, abLabel, abA >= 0, accent) { vm.cycleAb() }
            Box {
                ToolPill(
                    Icons.Rounded.Bedtime,
                    if (sleepLeft > 0) sleepLeft.asClock() else "Sleep",
                    sleepLeft > 0, accent,
                ) { sleepMenu = true }
                DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                    if (sleepLeft > 0) {
                        DropdownMenuItem(text = { Text("Turn off timer") }, onClick = { vm.setSleepTimer(0); sleepMenu = false })
                    }
                    SLEEP_MINUTES.forEach { m ->
                        DropdownMenuItem(
                            text = { Text("$m min") },
                            onClick = { vm.setSleepTimer(m); sleepMenu = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolPill(icon: ImageVector, label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape)
            .background(if (active) accent.copy(0.25f) else Color.White.copy(0.08f))
            .border(1.dp, if (active) accent else Color.White.copy(0.15f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) accent else Color.White.copy(0.8f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) accent else Color.White.copy(0.85f))
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Vinyl(art: ImageBitmap?, spinning: Boolean, diameter: Dp, accent: Color) {
    var angle by remember { mutableFloatStateOf(0f) }
    // Frame-driven rotation keeps the angle when paused (no snap-back to 0).
    LaunchedEffect(spinning) {
        if (spinning) {
            var last = withFrameNanosCompat()
            while (true) {
                val now = withFrameNanosCompat()
                angle = (angle + (now - last) / 1_000_000_000f * 45f) % 360f
                last = now
            }
        }
    }
    val disc = Color(0xFF0A0A0C)
    Box(
        Modifier
            .size(diameter)
            .shadow(28.dp, CircleShape, ambientColor = accent, spotColor = accent)
            .graphicsLayer { rotationZ = angle },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            drawCircle(disc)
            for (i in 1..9) {
                drawCircle(Color.White.copy(alpha = 0.045f), radius = r * (0.45f + i * 0.055f), style = Stroke(1.dp.toPx()))
            }
            drawCircle(
                Brush.sweepGradient(
                    listOf(Color.Transparent, Color.White.copy(0.10f), Color.Transparent, Color.White.copy(0.10f), Color.Transparent)
                ),
                radius = r,
            )
        }
        Box(
            Modifier.fillMaxSize(0.42f).clip(CircleShape)
                .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.4f)))),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(0.9f), modifier = Modifier.size(48.dp))
        }
        Box(Modifier.size(14.dp).clip(CircleShape).background(disc))
    }
}

private suspend fun withFrameNanosCompat(): Long = androidx.compose.runtime.withFrameNanos { it }

/** Decorative animated spectrum. (Real FFT needs android.media.audiofx.Visualizer + RECORD_AUDIO permission.) */
@Composable
private fun WaveSpectrum(active: Boolean, color: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase: Float by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing)
        ),
        label = "phase",
    )
    val amp: Float by animateFloatAsState(
        targetValue = if (active) 1f else 0.08f,
        animationSpec = tween(500),
        label = "amp",
    )
    Canvas(modifier) {
        val bars = 36
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val a: Float = 0.5f + 0.5f * sin(phase * 2f + i * 0.5f)
            val b: Float = 0.5f + 0.5f * sin(phase * 3f - i * 0.9f)
            val h: Float = (0.12f + 0.88f * a * b) * amp * size.height
            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.65f * a),
                topLeft = Offset(i * (w + gap), (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f),
            )
        }
    }
}

@Composable
private fun ProgressBar(positionMs: Long, durationMs: Long, accent: Color, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val target = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(target, tween(250, easing = LinearEasing), label = "progress")
    val shown = if (dragging) dragValue else animated

    Slider(
        value = shown,
        onValueChange = { dragging = true; dragValue = it },
        onValueChangeFinished = {
            onSeek((dragValue * durationMs).toLong())
            dragging = false
        },
        colors = SliderDefaults.colors(
            thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color.White.copy(0.2f),
        ),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        val shownMs = if (dragging) (dragValue * durationMs).toLong() else positionMs
        Text(shownMs.asClock(), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(0.75f))
        Text(durationMs.asClock(), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(0.75f))
    }
}

@Composable
private fun PressIcon(
    icon: ImageVector, desc: String, onClick: () -> Unit,
    tint: Color = Color.White, box: Dp = 52.dp, iconSize: Dp = 28.dp,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.84f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press",
    )
    Box(
        Modifier.size(box).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(iconSize)) }
}

@Composable
private fun PlayPauseButton(playing: Boolean, accent: Color, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "pp-scale",
    )
    // Circle <-> squircle morph as state flips.
    val corner by animateIntAsState(if (playing) 32 else 50, tween(300), label = "pp-corner")
    Box(
        Modifier.size(76.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(16.dp, RoundedCornerShape(corner), ambientColor = accent, spotColor = accent)
            .clip(RoundedCornerShape(corner)).background(accent)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(playing, animationSpec = tween(220), label = "pp-icon") { p ->
            Icon(
                if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (p) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}
