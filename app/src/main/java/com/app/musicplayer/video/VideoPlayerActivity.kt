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
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.rounded.ZoomOutMap
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.app.musicplayer.playback.MediaPlayerManager
import com.app.musicplayer.ui.theme.MusicTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePictureInPictureParams()
            }
        })
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
        updatePictureInPictureParams()

        val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::attachSubtitle)
        }

        setContent {
            MusicTheme {
                VideoScreen(
                    activity = this,
                    player = exo,
                    inPip = inPip,
                    onPip = { enterPip() },
                    onAddSubtitle = {
                        subtitlePicker.launch(
                            arrayOf("application/x-subrip", "text/vtt", "text/x-ssa", "text/plain", "application/octet-stream")
                        )
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun readQueue(): Queue {
        val suppliedUris = intent.getStringArrayListExtra(EXTRA_URIS)
        if (!suppliedUris.isNullOrEmpty()) {
            if (suppliedUris.size > MAX_QUEUE || suppliedUris.any { !isAllowedVideoUri(it) }) {
                return Queue(emptyList(), emptyList(), 0)
            }
            val titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty().take(MAX_QUEUE)
            return Queue(
                suppliedUris,
                titles,
                intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, suppliedUris.lastIndex),
            )
        }
        val data = intent.data ?: return Queue(emptyList(), emptyList(), 0)
        if (!isAllowedVideoUri(data.toString())) return Queue(emptyList(), emptyList(), 0)
        val name = data.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Video"
        return Queue(listOf(data.toString()), listOf(name), 0)
    }

    private fun isAllowedVideoUri(raw: String): Boolean {
        if (raw.isBlank() || raw.length > MAX_URI_LENGTH) return false
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in ALLOWED_VIDEO_SCHEMES &&
            (uri.authority?.length ?: 0) <= MAX_AUTHORITY_LENGTH
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
                updatePictureInPictureParams()
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            }
        }
    }

    private fun updatePictureInPictureParams() {
        if (Build.VERSION.SDK_INT < 26) return
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(player?.isPlaying == true)
        }
        setPictureInPictureParams(builder.build())
    }

    private fun attachSubtitle(uri: Uri) {
        val subtitle = MediaPlayerManager.subtitleConfiguration(uri)
        if (subtitle == null) {
            Toast.makeText(this, "Choose an SRT, VTT, or ASS subtitle file.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Could not keep access to the subtitle file: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        val p = player
        val item = p?.currentMediaItem
        if (p == null || item == null) {
            Toast.makeText(this, "No video is currently loaded.", Toast.LENGTH_SHORT).show()
            return
        }
        p.replaceMediaItem(
            p.currentMediaItemIndex,
            item.buildUpon().setSubtitleConfigurations(listOf(subtitle)).build(),
        )
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
        private const val MAX_URI_LENGTH = 4_096
        private const val MAX_AUTHORITY_LENGTH = 512
        private val ALLOWED_VIDEO_SCHEMES = setOf("content", "file", "http", "https")

        fun start(ctx: Context, uris: List<String>, titles: List<String>, index: Int) {
            if (uris.isEmpty() || uris.size > 10_000 || uris.any { it.isBlank() || it.length > MAX_URI_LENGTH }) {
                return
            }
            val safeIndex = index.coerceIn(0, uris.lastIndex)
            val from = (safeIndex - MAX_QUEUE / 2).coerceAtLeast(0)
            val to = (from + MAX_QUEUE).coerceAtMost(uris.size)
            val windowUris = uris.subList(from, to)
            val windowTitles = windowUris.indices.map { offset ->
                titles.getOrNull(from + offset)?.take(256).orEmpty()
            }
            ctx.startActivity(
                Intent(ctx, VideoPlayerActivity::class.java)
                    .putStringArrayListExtra(EXTRA_URIS, ArrayList(windowUris))
                    .putStringArrayListExtra(EXTRA_TITLES, ArrayList(windowTitles))
                    .putExtra(EXTRA_INDEX, safeIndex - from)
            )
        }
    }
}

// ------------------------------------------------------------------ UI

private val VIDEO_SPEEDS = MediaPlayerManager.playbackSpeeds
private const val VIDEO_TAG = "VideoPlayer"

private fun speedText(s: Float): String = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

@Composable
private fun VideoScreen(
    activity: ComponentActivity,
    player: ExoPlayer,
    inPip: Boolean,
    onPip: () -> Unit,
    onAddSubtitle: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
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
    var previewFrame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var seekRippleTrigger by remember { mutableIntStateOf(0) }
    var seekRippleLeft by remember { mutableStateOf(false) }
    val seekRippleProgress = remember { Animatable(1f) }

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
    LaunchedEffect(player.currentMediaItem?.mediaId, scrubbing, scrubValue, durMs) {
        if (!scrubbing || durMs <= 0L) {
            previewFrame = null
            return@LaunchedEffect
        }
        delay(180)
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return@LaunchedEffect
        previewFrame = loadVideoFrame(context, uri, (scrubValue * durMs).toLong())
    }
    LaunchedEffect(hud) { if (hud != null) { delay(900); hud = null } }
    LaunchedEffect(seekRippleTrigger) {
        if (seekRippleTrigger > 0) {
            seekRippleProgress.snapTo(0f)
            seekRippleProgress.animateTo(1f, tween(550))
        }
    }
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
            update = {
                it.resizeMode = resize
                it.scaleX = zoom
                it.scaleY = zoom
                it.translationX = panX
                it.translationY = panY
            },
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
                            onDoubleTap = { o ->
                                seekRippleLeft = o.x < size.width / 2f
                                seekRippleTrigger++
                                seekBy(if (seekRippleLeft) -10_000L else 10_000L)
                            },
                            onLongPress = { boosted = true; MediaPlayerManager.setPlaybackSpeed(player, 2f); hud = "2x" },
                            onPress = {
                                tryAwaitRelease()
                                if (boosted) { boosted = false; MediaPlayerManager.setPlaybackSpeed(player, speed) }
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
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(1f, 3f)
                        val maxX = size.width * (zoom - 1f) / 2f
                        val maxY = size.height * (zoom - 1f) / 2f
                        if (zoom == 1f) {
                            panX = 0f
                            panY = 0f
                        } else {
                            panX = (panX + pan.x).coerceIn(-maxX, maxX)
                            panY = (panY + pan.y).coerceIn(-maxY, maxY)
                        }
                        if (zoom > 1f) hud = "Zoom ${"%.1f".format(Locale.US, zoom)}x"
                    }
                }
        )

        if (seekRippleTrigger > 0) {
            Box(
                Modifier.align(if (seekRippleLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 32.dp)
                    .size(104.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val progress = seekRippleProgress.value
                    drawCircle(
                        Color.White.copy(alpha = 0.45f * (1f - progress)),
                        radius = size.minDimension * (0.18f + 0.42f * progress),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
                Icon(
                    if (seekRippleLeft) Icons.Rounded.Replay10 else Icons.Rounded.Forward10,
                    contentDescription = if (seekRippleLeft) "Rewind 10 seconds" else "Forward 10 seconds",
                    tint = Color.White.copy(alpha = 1f - seekRippleProgress.value * 0.5f),
                    modifier = Modifier.size(42.dp),
                )
            }
        }

        // ---- HUD message
        AnimatedVisibility(
            visible = hud != null,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            hud?.let {
                Text(
                    it, color = Color.White, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(0.65f))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
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
            enter = fadeIn() + scaleIn(initialScale = 0.98f),
            exit = fadeOut() + scaleOut(targetScale = 0.98f),
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
                        TracksMenu(tracksMenu, tracks, player, onAddSubtitle) { tracksMenu = false }
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
                                    onClick = { speed = s; MediaPlayerManager.setPlaybackSpeed(player, s); speedMenu = false },
                                )
                            }
                        }
                    }
                    if (zoom > 1f) {
                        IconButton(onClick = { zoom = 1f; panX = 0f; panY = 0f; hud = "Zoom reset" }) {
                            Icon(Icons.Rounded.ZoomOutMap, "Reset zoom", tint = Color.White)
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
                    if (scrubbing) previewFrame?.let { frame ->
                        Image(
                            bitmap = frame.asImageBitmap(),
                            contentDescription = "Seek preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                                .padding(bottom = 6.dp)
                                .size(width = 128.dp, height = 72.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                    }
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

private suspend fun loadVideoFrame(context: Context, uri: Uri, positionMs: Long): android.graphics.Bitmap? =
    withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(
                positionMs.coerceAtLeast(0L) * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
        } catch (e: Exception) {
            Log.w(VIDEO_TAG, "Could not load a seek-preview frame for $uri", e)
            null
        } finally {
            retriever.release()
        }
    }

private data class TrackOpt(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

private fun trackOptions(tracks: Tracks, type: Int): List<TrackOpt> {
    val out = ArrayList<TrackOpt>()
    for (g in tracks.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            val name = when {
                type == C.TRACK_TYPE_VIDEO && f.height > 0 -> "${f.height}p"
                type == C.TRACK_TYPE_VIDEO && f.width > 0 -> "${f.width} px"
                else -> f.label
                    ?: f.language?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() }
                    ?: "Track ${out.size + 1}"
            }
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

private fun automaticVideoQuality(p: Player) {
    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        .build()
}

@Composable
private fun TracksMenu(
    expanded: Boolean,
    tracks: Tracks,
    player: Player,
    onAddSubtitle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val audioOpts = trackOptions(tracks, C.TRACK_TYPE_AUDIO)
    val subOpts = trackOptions(tracks, C.TRACK_TYPE_TEXT)
    val videoOpts = trackOptions(tracks, C.TRACK_TYPE_VIDEO)
    val check = "  \u2713"
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            "Video quality", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        DropdownMenuItem(
            text = { Text("Automatic" + if (videoOpts.none { it.selected }) check else "") },
            onClick = { automaticVideoQuality(player); onDismiss() },
        )
        videoOpts.forEach { o ->
            DropdownMenuItem(
                text = { Text(o.label + if (o.selected) check else "") },
                onClick = { selectTrack(player, o); onDismiss() },
            )
        }
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
        DropdownMenuItem(
            text = { Text("Add subtitle file…") },
            onClick = { onDismiss(); onAddSubtitle() },
        )
    }
}
