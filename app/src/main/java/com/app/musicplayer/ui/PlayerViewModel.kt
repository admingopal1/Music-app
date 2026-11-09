package com.app.musicplayer.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import com.app.musicplayer.audio.AudioFxManager
import com.app.musicplayer.data.ArtLoader
import com.app.musicplayer.data.MediaStoreRepository
import com.app.musicplayer.data.PlaylistStore
import com.app.musicplayer.audio.ReplayGain
import com.app.musicplayer.audio.ReplayGainStore
import com.app.musicplayer.data.BackupManager
import com.app.musicplayer.data.Song
import com.app.musicplayer.data.StatsStore
import com.app.musicplayer.lyrics.LyricsRepository
import com.app.musicplayer.data.Video
import com.app.musicplayer.data.VideoRepository
import com.app.musicplayer.data.toMediaItem
import com.app.musicplayer.service.MusicService
import com.app.musicplayer.playback.MediaPlayerManager
import com.app.musicplayer.ui.theme.ACCENT_CHOICES
import com.app.musicplayer.ui.theme.DefaultAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QueueItem(val index: Int, val title: String, val artist: String)

data class PlayerUi(
    val isPlaying: Boolean = false,
    val song: Song? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    val fx: AudioFxManager = AudioFxManager.get(ctx)
    val playlists = PlaylistStore(ctx)
    val stats = StatsStore(ctx)
    val lyricsRepo = LyricsRepository(ctx)

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()
    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos.asStateFlow()
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()
    private val _art = MutableStateFlow<Bitmap?>(null)
    val art: StateFlow<Bitmap?> = _art.asStateFlow()
    private val _accent = MutableStateFlow(DefaultAccent)
    val accent: StateFlow<Color> = _accent.asStateFlow()

    // ---- VLC-style extras: A-B repeat, sleep timer, colour mode
    private val _abA = MutableStateFlow(-1L)
    val abA: StateFlow<Long> = _abA.asStateFlow()
    private val _abB = MutableStateFlow(-1L)
    val abB: StateFlow<Long> = _abB.asStateFlow()
    private val _sleepLeft = MutableStateFlow(0L)
    val sleepLeftMs: StateFlow<Long> = _sleepLeft.asStateFlow()
    private var sleepJob: Job? = null

    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _dynamic = MutableStateFlow(prefs.getBoolean("dynamic_colors", false))
    val dynamicColors: StateFlow<Boolean> = _dynamic.asStateFlow()

    private val _amoled = MutableStateFlow(prefs.getBoolean("amoled", true))
    val amoled: StateFlow<Boolean> = _amoled.asStateFlow()
    private val _accentIdx = MutableStateFlow(prefs.getInt("accent_idx", 0).coerceIn(0, ACCENT_CHOICES.lastIndex))
    val accentIdx: StateFlow<Int> = _accentIdx.asStateFlow()
    private val _skipSilence = MutableStateFlow(prefs.getBoolean("skip_silence", false))
    val skipSilence: StateFlow<Boolean> = _skipSilence.asStateFlow()

    private val backup = BackupManager(ctx)
    private val _backupMsg = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMsg.asStateFlow()
    private val _fadeSecs = MutableStateFlow(prefs.getFloat("fade_secs", 0f))
    val fadeSecs: StateFlow<Float> = _fadeSecs.asStateFlow()
    private val rgStore = ReplayGainStore(ctx)
    private val _rgMode = MutableStateFlow(prefs.getInt("rg_mode", 0))
    val rgMode: StateFlow<Int> = _rgMode.asStateFlow()
    private val _rgPreamp = MutableStateFlow(prefs.getFloat("rg_preamp", 0f))
    val rgPreamp: StateFlow<Float> = _rgPreamp.asStateFlow()
    private val _rgFallback = MutableStateFlow(prefs.getFloat("rg_fallback", 0f))
    val rgFallback: StateFlow<Float> = _rgFallback.asStateFlow()
    private val _rgLimit = MutableStateFlow(prefs.getBoolean("rg_limit", true))
    val rgLimit: StateFlow<Boolean> = _rgLimit.asStateFlow()

    private var controller: MediaController? = null
    private var songById: Map<Long, Song> = emptyMap()
    private var artFor: Long? = null
    private var artJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refreshUi()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.toLongOrNull()?.let { stats.recordPlay(it) }
        }
    }

    init {
        _accent.value = baseAccent()
        connect()
        viewModelScope.launch {
            while (true) {
                val abActive = _abA.value >= 0 && _abB.value > _abA.value
                delay(if (abActive) 80 else 250)
                val c = controller
                if (c != null && c.isPlaying) {
                    if (abActive && c.currentPosition >= _abB.value) c.seekTo(_abA.value)
                    _ui.update { it.copy(positionMs = c.currentPosition) }
                }
            }
        }
    }

    // ------------------------------------------------------------- connection

    private fun connect() {
        val token = SessionToken(ctx, ComponentName(ctx, MusicService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { c ->
                controller = c
                c.addListener(listener)
                refreshUi()
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun refreshUi() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId?.toLongOrNull()
        val song = id?.let { songById[it] } ?: c.currentMediaItem?.let { streamSong(it, c.duration) }
        val dur = if (c.duration == C.TIME_UNSET) 0L else c.duration
        _ui.value = PlayerUi(
            isPlaying = c.isPlaying,
            song = song,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = dur,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            speed = c.playbackParameters.speed,
        )
        if (song?.id != artFor) {
            artFor = song?.id
            _abA.value = -1L
            _abB.value = -1L
            loadArt(song)
        }
    }

    private fun loadArt(song: Song?) {
        artJob?.cancel()
        artJob = viewModelScope.launch {
            if (song == null) {
                _art.value = null
                _accent.value = baseAccent()
                return@launch
            }
            val bmp = ArtLoader.load(ctx, song)
            _art.value = bmp
            _accent.value = if (bmp != null && _dynamic.value) withContext(Dispatchers.Default) { extractAccent(bmp) } else baseAccent()
        }
    }

    private fun extractAccent(bmp: Bitmap): Color {
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val rgb = (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch)?.rgb
            ?: return DefaultAccent
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb, hsl)
        hsl[1] = hsl[1].coerceAtLeast(0.45f)
        hsl[2] = hsl[2].coerceIn(0.55f, 0.72f) // keep it readable on the dark surface
        return Color(ColorUtils.HSLToColor(hsl))
    }

    // ------------------------------------------------------------- library

    fun loadLibrary() {
        viewModelScope.launch {
            val list = MediaStoreRepository.loadSongs(ctx)
            songById = list.associateBy { it.id }
            _songs.value = list
            refreshUi()
        }
    }

    fun songById(id: Long): Song? = songById[id]

    fun importLyrics(songId: Long, uri: Uri, done: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { lyricsRepo.importFor(songId, uri) } }
            done()
        }
    }

    fun loadVideos() {
        viewModelScope.launch { _videos.value = VideoRepository.loadVideos(ctx) }
    }

    // ------------------------------------------------------------- playback

    fun playSongs(list: List<Song>, index: Int) {
        val c = controller ?: return
        if (list.isEmpty()) return
        c.setMediaItems(list.map { it.toMediaItem() }, index.coerceIn(0, list.lastIndex), 0L)
        c.prepare()
        c.play()
    }

    fun playPlaylist(name: String) {
        val list = playlists.lists.value[name].orEmpty().mapNotNull { songById[it] }
        playSongs(list, 0)
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNext() }
    fun previous() { controller?.seekToPrevious() }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        refreshUi()
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    // ------------------------------------------------------------- settings / queue / streams

    private fun baseAccent(): Color = ACCENT_CHOICES[_accentIdx.value.coerceIn(0, ACCENT_CHOICES.lastIndex)]

    fun setAmoled(enabled: Boolean) {
        prefs.edit().putBoolean("amoled", enabled).apply()
        _amoled.value = enabled
    }

    fun setAccentIdx(i: Int) {
        prefs.edit().putInt("accent_idx", i).apply()
        _accentIdx.value = i
        _accent.value = baseAccent()
        artFor = null
        refreshUi()
    }

    /** The playback service listens to this preference and applies it to ExoPlayer. */
    /** 0 = off. Read by the playback service. */
    fun setFadeSecs(secs: Float) {
        val v = secs.coerceIn(0f, 12f)
        prefs.edit().putFloat("fade_secs", v).apply()
        _fadeSecs.value = v
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            _backupMsg.value = withContext(Dispatchers.IO) {
                runCatching {
                    val json = backup.export(_songs.value, playlists.lists.value, stats.stats.value)
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
                    "Backup saved."
                }.getOrElse { "Backup failed: ${it.message}" }
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            _backupMsg.value = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
                    val r = backup.restore(text, _songs.value, playlists, stats)
                    "Restored ${r.playlists} playlists, ${r.matched} songs matched, ${r.missing} not found on this phone. " +
                        "Restart the app to apply settings and equalizer."
                }.getOrElse { "Restore failed: ${it.message}" }
            }
        }
    }

    // ReplayGain settings are read by the playback service (same preferences file).
    fun setRgMode(mode: Int) { prefs.edit().putInt("rg_mode", mode).apply(); _rgMode.value = mode }
    fun setRgPreamp(db: Float) {
        val v = db.coerceIn(-12f, 12f); prefs.edit().putFloat("rg_preamp", v).apply(); _rgPreamp.value = v
    }
    fun setRgFallback(db: Float) {
        val v = db.coerceIn(-24f, 12f); prefs.edit().putFloat("rg_fallback", v).apply(); _rgFallback.value = v
    }
    fun setRgLimit(on: Boolean) { prefs.edit().putBoolean("rg_limit", on).apply(); _rgLimit.value = on }

    /** e.g. "-6.2 dB" for the current song, "no tag · +0.0 dB" when the file has no ReplayGain tag. */
    fun replayGainLabel(songId: Long): String? {
        val s = ReplayGain.readSettings(prefs)
        if (s.mode == ReplayGain.MODE_OFF) return null
        val info = rgStore.get(songId.toString())
        val db = ReplayGain.appliedDb(info, s) ?: return null
        return (if (info == null) "no tag · " else "") + "%+.1f dB".format(db)
    }

    fun setSkipSilence(enabled: Boolean) {
        prefs.edit().putBoolean("skip_silence", enabled).apply()
        _skipSilence.value = enabled
    }

    private fun streamSong(item: MediaItem, durationMs: Long): Song {
        val md = item.mediaMetadata
        return Song(
            id = -1L,
            title = md.title?.toString() ?: "Stream",
            artist = md.artist?.toString() ?: "Stream",
            album = md.albumTitle?.toString() ?: "",
            albumId = 0L,
            durationMs = if (durationMs == C.TIME_UNSET) 0L else durationMs,
            uri = item.localConfiguration?.uri ?: Uri.EMPTY,
        )
    }

    fun playUrl(url: String) {
        val c = controller ?: return
        val item = MediaItem.Builder()
            .setMediaId(url)
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(url.substringAfterLast('/').substringBefore('?').ifBlank { url })
                    .setArtist("Network stream")
                    .build()
            )
            .build()
        c.setMediaItems(listOf(item))
        c.prepare()
        c.play()
    }

    fun playNext(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) playSongs(listOf(song), 0)
        else c.addMediaItem(c.currentMediaItemIndex + 1, song.toMediaItem())
    }

    fun addToQueue(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) playSongs(listOf(song), 0) else c.addMediaItem(song.toMediaItem())
    }

    fun queueItems(): List<QueueItem> {
        val c = controller ?: return emptyList()
        return (0 until c.mediaItemCount).map { i ->
            val md = c.getMediaItemAt(i).mediaMetadata
            QueueItem(i, md.title?.toString() ?: "Unknown", md.artist?.toString() ?: "")
        }
    }

    fun currentIndex(): Int = controller?.currentMediaItemIndex ?: 0
    fun jumpTo(index: Int) { controller?.let { it.seekToDefaultPosition(index); it.play() } }
    fun removeFromQueue(index: Int) { controller?.removeMediaItem(index) }
    fun moveInQueue(from: Int, to: Int) {
        val c = controller ?: return
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    // ------------------------------------------------------------- VLC-style extras

    fun setSpeed(speed: Float) {
        controller?.let { MediaPlayerManager.setPlaybackSpeed(it, speed) }
        refreshUi()
    }

    /** Tap 1 = set A, tap 2 = set B (loop A-B), tap 3 = clear. */
    fun cycleAb() {
        val c = controller ?: return
        when {
            _abA.value < 0 -> _abA.value = c.currentPosition
            _abB.value < 0 -> if (c.currentPosition > _abA.value + 500) _abB.value = c.currentPosition
            else -> { _abA.value = -1L; _abB.value = -1L }
        }
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepLeft.value = 0L
            runCatching { controller?.volume = 1f }
            return
        }
        sleepJob = viewModelScope.launch {
            var left = minutes * 60_000L
            while (left > 0) {
                _sleepLeft.value = left
                if (left <= 15_000L) runCatching { controller?.volume = (left / 15_000f).coerceIn(0f, 1f) }
                delay(1000)
                left -= 1000
            }
            _sleepLeft.value = 0L
            controller?.pause()
            runCatching { controller?.volume = 1f }
        }
    }

    fun setDynamicColors(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_colors", enabled).apply()
        _dynamic.value = enabled
        artFor = null   // force art/accent to be recomputed
        refreshUi()
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onCleared()
    }
}
