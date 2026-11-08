package com.app.musicplayer.video

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.app.musicplayer.data.asClock
import com.app.musicplayer.ui.theme.MusicTheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen video player (VLC-style): gestures, speed, audio/subtitle tracks, aspect ratio,
 * screen lock, rotation, picture-in-picture, resume position. Also opens "Open with" video links.
 */
class VideoPlayerActivity : ComponentActivity() {

    private data class Queue(val uris: List<String>, val titles: List<String>, val index: Int)

    private var player: ExoPlayer? = null
    private var inPip by mutableStateOf(false)
    private lateinit var resumePrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resumePrefs = getSharedPreferences("video_resume", Context.MODE_PRIVATE)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val queue = readQueue()
        if (queue.uris.isEmpty()) {
            finish()
            return
        }

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val items = queue.uris.mapIndexed { i, u ->
            MediaItem.Builder()
                .setMediaId(u)
                .setUri(Uri.parse(u))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(queue.titles.getOrNull(i) ?: "Video").build())
                .build()
        }
        val resumeAt = resumePrefs.getLong("pos_" + queue.uris[queue.index], 0L)
        exo.setMediaItems(items, queue.index, resumeAt)
        exo.prepare()
        exo.playWhenReady = true
        player = exo

        setContent {
            MusicTheme {
                VideoScreen(
                    activity = this,
                    player = exo,
                    inPip = inPip,
                    onPip = { enterPip() },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun readQueue(): Queue {
        val uris = intent.getStringArrayListExtra(EXTRA_URIS)
        if (!uris.isNullOrEmpty()) {
            val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
            return Queue(uris, titles, intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, uris.lastIndex))
        }
        val data = intent.data ?: return Queue(emptyList(), emptyList(), 0)
        val name = data.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Video"
        return Queue(listOf(data.toString()), listOf(name), 0)
    }

    private fun saveResume() {
        val p = player ?: return
        val id = p.currentMediaItem?.mediaId ?: return
        val pos = p.currentPosition
        val dur = p.duration
        val e = resumePrefs.edit()
        if (dur > 0 && pos > 5_000 && pos < dur - 10_000) e.putLong("pos_$id", pos) else e.remove("pos_$id")
        e.apply()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        saveResume()
        if (!inPip) player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_INDEX = "index"
        private const val MAX_QUEUE = 200   // keeps the Intent small

        fun start(ctx: Context, uris: List<String>, titles: List<String>, index: Int) {
            val from = (index - MAX_QUEUE / 2).coerceAtLeast(0)
            val to = (from + MAX_QUEUE).coerceAtMost(uris.size)
            ctx.startActivity(
                Intent(ctx, VideoPlayerActivity::class.java)
                    .putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.subList(from, to)))
                    .putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles.subList(from, to)))
                    .putExtra(EXTRA_INDEX, index - from)
            )
        }
    }
}

// ------------------------------------------------------------------ UI

private val VIDEO_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

private fun speedText(s: Float): String = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

@Composable
private fun VideoScreen(
    activity: ComponentActivity,
    player: ExoPlayer,
    inPip: Boolean,
    onPip: () -> Unit,
    onClose: () -> Unit,
) {
    var title by remember { mutableStateOf(player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var tracks by remember { mutableStateOf(player.currentTracks) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var hud by remember { mutableStateOf<String?>(null) }
    var resize by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var speed by remember { mutableFloatStateOf(1f) }
    var boosted by remember { mutableStateOf(false) }
    var orient by remember { mutableIntStateOf(0) }          // 0 auto, 1 landscape, 2 portrait
    var speedMenu by remember { mutableStateOf(false) }
    var tracksMenu by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    val audio = remember { activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol) }
    var bright by remember {
        mutableFloatStateOf(activity.window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f)
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                title = p.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
                isPlaying = p.isPlaying
                tracks = p.currentTracks
                durMs = if (p.duration == C.TIME_UNSET) 0L else p.duration
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            posMs = player.currentPosition.coerceAtLeast(0)
            if (player.duration != C.TIME_UNSET) durMs = player.duration
            delay(250)
        }
    }
    LaunchedEffect(hud) { if (hud != null) { delay(900); hud = null } }
    LaunchedEffect(controls, isPlaying, speedMenu, tracksMenu, locked) {
        if (controls && isPlaying && !speedMenu && !tracksMenu) { delay(3500); controls = false }
    }

    fun seekBy(ms: Long) {
        val max = if (durMs > 0) durMs else Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0L, max))
        hud = (if (ms > 0) "+" else "-") + abs(ms / 1000) + "s"
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                val pv = PlayerView(c)
                pv.player = player
                pv.useController = false
                pv.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                pv
            },
            update = { it.resizeMode = resize },
            modifier = Modifier.fillMaxSize(),
        )

        // ---- gesture layer (tap, double-tap, long-press 2x, swipes)
        Box(
            Modifier.fillMaxSize()
                .pointerInput(locked) {
                    if (locked) {
                        detectTapGestures(onTap = { controls = !controls })
                    } else {
                        detectTapGestures(
                            onTap = { controls = !controls },
                            onDoubleTap = { o -> seekBy(if (o.x < size.width / 2f) -10_000L else 10_000L) },
                            onLongPress = { boosted = true; player.setPlaybackSpeed(2f); hud = "2x" },
                            onPress = {
                                tryAwaitRelease()
                                if (boosted) { boosted = false; player.setPlaybackSpeed(speed) }
                            },
                        )
                    }
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var startPos = 0L
                    var acc = 0f
                    var target = 0L
                    detectHorizontalDragGestures(
                        onDragStart = { startPos = player.currentPosition; acc = 0f; target = startPos },
                        onDragEnd = { player.seekTo(target) },
                        onDragCancel = { },
                        onHorizontalDrag = { _, dx ->
                            acc += dx
                            val max = if (durMs > 0) durMs else Long.MAX_VALUE
                            target = (startPos + (acc * 60).toLong()).coerceIn(0L, max)
                            hud = target.asClock() + " / " + durMs.asClock()
                        },
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var rightSide = true
                    detectVerticalDragGestures(
                        onDragStart = { o -> rightSide = o.x > size.width / 2f },
                        onVerticalDrag = { _, dy ->
                            val delta = -dy / size.height * 1.4f
                            if (rightSide) {
                                vol = (vol + delta).coerceIn(0f, 1f)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (vol * maxVol).roundToInt(), 0)
                                hud = "Volume " + (vol * 100).roundToInt() + "%"
                            } else {
                                bright = (bright + delta).coerceIn(0.02f, 1f)
                                val lp = activity.window.attributes
                                lp.screenBrightness = bright
                                activity.window.attributes = lp
                                hud = "Brightness " + (bright * 100).roundToInt() + "%"
                            }
                        },
                    )
                }
        )

        // ---- HUD message
        hud?.let {
            Text(
                it, color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(0.65f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        // ---- locked: only an unlock button
        if (locked && controls && !inPip) {
            IconButton(
                onClick = { locked = false; controls = true },
                modifier = Modifier.align(Alignment.CenterStart).displayCutoutPadding().padding(16.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(0.6f)),
            ) { Icon(Icons.Rounded.Lock, "Unlock", tint = MaterialTheme.colorScheme.primary) }
        }

        // ---- controls
        AnimatedVisibility(
            visible = controls && !locked && !inPip,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize().displayCutoutPadding(), verticalArrangement = Arrangement.SpaceBetween) {
                // top bar
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(0.75f), Color.Transparent)))
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                    Text(
                        title, color = Color.White, maxLines = 1,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    )
                    Box {
                        IconButton(onClick = { tracksMenu = true }) { Icon(Icons.Rounded.Audiotrack, "Audio and subtitles", tint = Color.White) }
                        TracksMenu(tracksMenu, tracks, player) { tracksMenu = false }
                    }
                    Box {
                        IconButton(onClick = { speedMenu = true }) {
                            Icon(Icons.Rounded.Speed, "Speed", tint = if (speed != 1f) MaterialTheme.colorScheme.primary else Color.White)
                        }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                            VIDEO_SPEEDS.forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            speedText(s),
                                            color = if (s == speed) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                        )
                                    },
                                    onClick = { speed = s; player.setPlaybackSpeed(s); speedMenu = false },
                                )
                            }
                        }
                    }
                    IconButton(onClick = {
                        resize = when (resize) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        hud = when (resize) {
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Crop to fill"
                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
                            else -> "Fit"
                        }
                    }) { Icon(Icons.Rounded.AspectRatio, "Aspect ratio", tint = Color.White) }
                    IconButton(onClick = {
                        orient = (orient + 1) % 3
                        activity.requestedOrientation = when (orient) {
                            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        hud = when (orient) { 1 -> "Landscape"; 2 -> "Portrait"; else -> "Auto rotate" }
                    }) { Icon(Icons.Rounded.ScreenRotation, "Rotate", tint = Color.White) }
                    if (Build.VERSION.SDK_INT >= 26) {
                        IconButton(onClick = onPip) { Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
                    }
                }

                // bottom bar
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.8f))))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val shown = if (scrubbing) scrubValue else if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (scrubbing) (scrubValue * durMs).toLong() else posMs).asClock(),
                            color = Color.White, style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            value = shown,
                            onValueChange = { scrubbing = true; scrubValue = it },
                            onValueChangeFinished = {
                                player.seekTo((scrubValue * durMs).toLong())
                                scrubbing = false
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(0.25f),
                            ),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(durMs.asClock(), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { locked = true; controls = false; hud = "Screen locked" }) {
                            Icon(Icons.Rounded.LockOpen, "Lock screen", tint = Color.White)
                        }
                        IconButton(onClick = { player.seekToPreviousMediaItem() }) {
                            Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = { seekBy(-10_000L) }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds", tint = Color.White) }
                        IconButton(
                            onClick = { if (player.isPlaying) player.pause() else player.play() },
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary),
                        ) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp),
                            )
                        }
                        IconButton(onClick = { seekBy(10_000L) }) { Icon(Icons.Rounded.Forward10, "Forward 10 seconds", tint = Color.White) }
                        IconButton(onClick = { player.seekToNextMediaItem() }) {
                            Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.width(48.dp))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ audio / subtitle tracks

private data class TrackOpt(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

private fun trackOptions(tracks: Tracks, type: Int): List<TrackOpt> {
    val out = ArrayList<TrackOpt>()
    for (g in tracks.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            val name = f.label
                ?: f.language?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() }
                ?: "Track ${out.size + 1}"
            out += TrackOpt(g, i, name, g.isTrackSelected(i))
        }
    }
    return out
}

private fun selectTrack(p: Player, o: TrackOpt) {
    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(o.group.type, false)
        .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
        .build()
}

private fun disableSubtitles(p: Player) {
    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()
}

@Composable
private fun TracksMenu(expanded: Boolean, tracks: Tracks, player: Player, onDismiss: () -> Unit) {
    val audioOpts = trackOptions(tracks, C.TRACK_TYPE_AUDIO)
    val subOpts = trackOptions(tracks, C.TRACK_TYPE_TEXT)
    val check = "  \u2713"
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            "Audio", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (audioOpts.isEmpty()) {
            DropdownMenuItem(text = { Text("Default") }, onClick = onDismiss, enabled = false)
        }
        audioOpts.forEach { o ->
            DropdownMenuItem(
                text = { Text(o.label + if (o.selected) check else "") },
                onClick = { selectTrack(player, o); onDismiss() },
            )
        }
        Text(
            "Subtitles", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        DropdownMenuItem(
            text = { Text("Off" + if (subOpts.none { it.selected }) check else "") },
            onClick = { disableSubtitles(player); onDismiss() },
        )
        subOpts.forEach { o ->
            DropdownMenuItem(
                text = { Text(o.label + if (o.selected) check else "") },
                onClick = { selectTrack(player, o); onDismiss() },
            )
        }
    }
}
