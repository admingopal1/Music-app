package com.app.musicplayer.service

import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.app.musicplayer.MainActivity
import com.app.musicplayer.CrashLog
import com.app.musicplayer.audio.SonyAudioEffects
import com.app.musicplayer.playback.SleepTimer
import com.app.musicplayer.audio.AudioLevelProcessor
import com.app.musicplayer.audio.AudioLevelMonitor
import com.app.musicplayer.audio.DspGuard
import com.app.musicplayer.audio.EnhancerController
import com.app.musicplayer.audio.EnhancerProcessor
import com.app.musicplayer.audio.FadeController
import com.app.musicplayer.audio.ReplayGainController
import com.app.musicplayer.audio.ReplayGainProcessor
import com.app.musicplayer.widget.WidgetBridge

class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var usingDsp = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var widgetBridge: WidgetBridge? = null
    private var rgController: ReplayGainController? = null
    private var fadeController: FadeController? = null
    private var enhancerController: EnhancerController? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        // "Skip silence" is toggled from Settings via the shared preferences file.
        val l = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == "skip_silence") player?.skipSilenceEnabled = sp.getBoolean("skip_silence", false)
        }
        prefsListener = l
        prefs.registerOnSharedPreferenceChangeListener(l)

        // The custom audio chain (ReplayGain / Enhancer / spectrum) is used until Media3 reports an
        // unexpected runtime failure; after that the app permanently falls back to the stock audio sink.
        val first = buildPlayer(useDsp = prefs.getBoolean("dsp_chain", true))

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, first).setSessionActivity(openApp).build()
    }

    /** Builds a fully wired player: sink, effects, listeners, widgets and the DSP controllers. */
    private fun buildPlayer(useDsp: Boolean): ExoPlayer {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val rgProcessor = ReplayGainProcessor()
        val enhancerProcessor = EnhancerProcessor()
        val audioLevelProcessor = AudioLevelProcessor()

        val builder = if (useDsp) {
            val renderers = object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(rgProcessor, enhancerProcessor, audioLevelProcessor))
                    .build()
            }
            ExoPlayer.Builder(this, renderers)
        } else {
            ExoPlayer.Builder(this)
        }

        val p = builder
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        usingDsp = useDsp
        player = p
        SleepTimer.attach(p)
        p.skipSilenceEnabled = prefs.getBoolean("skip_silence", false)

        // Hook hardware effects (EQ, bass...) to the player's audio session, and re-hook if it changes.
        val fx = SonyAudioEffects.get(this)
        p.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                fx.attach(audioSessionId)
            }
        })
        fx.attach(p.audioSessionId)

        p.addListener(object : Player.Listener {
            private var errorStreak = 0

            override fun onPlayerError(error: PlaybackException) {
                CrashLog.record("Playback error " + error.errorCodeName, error)
                if (error.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK) {
                    if (usingDsp) {
                        // Switch to the stock audio sink and carry on from the same song and position.
                        // Posted, because a player must not be released from inside its own callback.
                        mainHandler.post { rebuildWithoutDsp() }
                    }
                    // Otherwise leave the error on screen (with its cause) instead of skipping past it.
                    return
                }
                // A damaged or unreadable file: move on (up to 3 in a row) so one bad song does not stop the queue.
                errorStreak++
                if (errorStreak <= 3 && p.hasNextMediaItem()) {
                    p.seekToNextMediaItem()
                    p.prepare()
                    p.play()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) errorStreak = 0
            }
        })

        widgetBridge = WidgetBridge(this, p)
        rgController = ReplayGainController(this, p, rgProcessor)
        fadeController = FadeController(this, p, rgProcessor)
        enhancerController = EnhancerController(this, enhancerProcessor)
        return p
    }

    private fun releaseControllers(clearWidgetState: Boolean = true) {
        enhancerController?.release()
        enhancerController = null
        fadeController?.release()
        fadeController = null
        rgController?.release()
        rgController = null
        widgetBridge?.release(clearPlaybackState = clearWidgetState)
        widgetBridge = null
    }

    /** Replaces the player with one that uses Media3's stock audio sink, keeping queue and position. */
    private fun rebuildWithoutDsp() {
        val old = player ?: return
        if (!usingDsp) return
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("dsp_chain", false).apply()

        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val position = old.currentPosition
        val repeatMode = old.repeatMode
        val shuffle = old.shuffleModeEnabled
        val params = old.playbackParameters

        releaseControllers(clearWidgetState = false)
        val fresh = buildPlayer(useDsp = false)
        fresh.repeatMode = repeatMode
        fresh.shuffleModeEnabled = shuffle
        fresh.playbackParameters = params
        if (items.isNotEmpty()) {
            fresh.setMediaItems(items, index.coerceIn(0, items.lastIndex), position)
            fresh.prepare()
            fresh.play()
        }
        mediaSession?.player = fresh
        old.release()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = mediaSession?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        SleepTimer.detach()
        releaseControllers()
        prefsListener?.let { getSharedPreferences("settings", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        SonyAudioEffects.get(this).release()
        AudioLevelMonitor.reset()
        super.onDestroy()
    }
}
