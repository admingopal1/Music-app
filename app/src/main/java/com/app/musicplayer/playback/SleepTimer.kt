package com.app.musicplayer.playback

import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sleep timer that lives with the playback service, not with a screen.
 * It used to be owned by the ViewModel, so swiping the app away cancelled the timer
 * while the music kept playing all night.
 */
object SleepTimer {
    private val _leftMs = MutableStateFlow(0L)
    val leftMs: StateFlow<Long> = _leftMs.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var baseVolume: Float? = null
    private var player: Player? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Called by MusicService once the player exists. */
    fun attach(p: Player) = onMain {
        if (player !== p) cancelLocked()
        player = p
    }

    /** Called by MusicService when it is destroyed. */
    fun detach() = onMain {
        cancelLocked()
        player = null
    }

    fun start(minutes: Int) = onMain {
        cancelLocked()
        val p = player ?: return@onMain
        val durationMs = PlaybackOptions.sleepDurationMs(minutes)
        if (durationMs <= 0L) return@onMain

        // Fade relative to whatever volume the user currently has, and restore it afterwards.
        val base = p.volume
        baseVolume = base
        job = scope.launch {
            // elapsedRealtime is monotonic, so wall-clock changes cannot make the timer early or late.
            val deadline = SystemClock.elapsedRealtime() + durationMs
            while (true) {
                val left = PlaybackOptions.remainingMs(deadline, SystemClock.elapsedRealtime())
                _leftMs.value = left
                if (left <= 0L) break
                if (left <= PlaybackOptions.SLEEP_FADE_DURATION_MS) {
                    runCatching { p.volume = PlaybackOptions.sleepVolume(base, left) }
                }
                delay(minOf(1_000L, left))
            }
            _leftMs.value = 0L
            runCatching { p.pause() }
            restoreVolumeLocked()
        }
    }

    fun cancel() = onMain { cancelLocked() }

    private fun cancelLocked() {
        job?.cancel()
        job = null
        _leftMs.value = 0L
        restoreVolumeLocked()
    }

    private fun restoreVolumeLocked() {
        val original = baseVolume ?: return
        runCatching { player?.volume = original }
        baseVolume = null
    }
}
