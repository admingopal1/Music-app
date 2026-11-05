package com.app.musicplayer.ui

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import com.app.musicplayer.audio.AudioFxManager
import com.app.musicplayer.data.ArtLoader
import com.app.musicplayer.data.MediaStoreRepository
import com.app.musicplayer.data.PlaylistStore
import com.app.musicplayer.data.Song
import com.app.musicplayer.data.toMediaItem
import com.app.musicplayer.service.MusicService
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

data class PlayerUi(
    val isPlaying: Boolean = false,
    val song: Song? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    val fx: AudioFxManager = AudioFxManager.get(ctx)
    val playlists = PlaylistStore(ctx)

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()
    private val _art = MutableStateFlow<Bitmap?>(null)
    val art: StateFlow<Bitmap?> = _art.asStateFlow()
    private val _accent = MutableStateFlow(DefaultAccent)
    val accent: StateFlow<Color> = _accent.asStateFlow()

    private var controller: MediaController? = null
    private var songById: Map<Long, Song> = emptyMap()
    private var artFor: Long? = null
    private var artJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refreshUi()
    }

    init {
        connect()
        viewModelScope.launch {
            while (true) {
                delay(250)
                val c = controller
                if (c != null && c.isPlaying) _ui.update { it.copy(positionMs = c.currentPosition) }
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
        val song = id?.let { songById[it] }
        val dur = if (c.duration == C.TIME_UNSET) 0L else c.duration
        _ui.value = PlayerUi(
            isPlaying = c.isPlaying,
            song = song,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = dur,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
        )
        if (song?.id != artFor) {
            artFor = song?.id
            loadArt(song)
        }
    }

    private fun loadArt(song: Song?) {
        artJob?.cancel()
        artJob = viewModelScope.launch {
            if (song == null) {
                _art.value = null
                _accent.value = DefaultAccent
                return@launch
            }
            val bmp = ArtLoader.load(ctx, song)
            _art.value = bmp
            _accent.value = if (bmp != null) withContext(Dispatchers.Default) { extractAccent(bmp) } else DefaultAccent
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

    override fun onCleared() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onCleared()
    }
}
