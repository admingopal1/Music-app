package com.app.musicplayer.service

import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.app.musicplayer.MainActivity
import com.app.musicplayer.audio.SonyAudioEffects
import com.app.musicplayer.audio.AudioLevelProcessor
import com.app.musicplayer.audio.AudioLevelMonitor
import com.app.musicplayer.audio.EnhancerController
import com.app.musicplayer.audio.EnhancerProcessor
import com.app.musicplayer.audio.FadeController
import com.app.musicplayer.audio.ReplayGainController
import com.app.musicplayer.audio.ReplayGainProcessor
import com.app.musicplayer.widget.WidgetBridge

class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var widgetBridge: WidgetBridge? = null
    private var rgController: ReplayGainController? = null
    private var fadeController: FadeController? = null
    private var enhancerController: EnhancerController? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate() {
        super.onCreate()

        // ReplayGain: a small audio processor in front of the AudioTrack scales the PCM.
        val rgProcessor = ReplayGainProcessor()
        val enhancerProcessor = EnhancerProcessor()
        val audioLevelProcessor = AudioLevelProcessor()
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

        val player = ExoPlayer.Builder(this, renderers)
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

        // "Skip silence" is toggled from Settings via the shared preferences file.
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        player.skipSilenceEnabled = prefs.getBoolean("skip_silence", false)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == "skip_silence") player.skipSilenceEnabled = sp.getBoolean("skip_silence", false)
        }
        prefsListener = l
        prefs.registerOnSharedPreferenceChangeListener(l)

        // Hook DSP effects to the player's audio session (and re-hook if ExoPlayer changes it).
        val fx = SonyAudioEffects.get(this)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                fx.attach(audioSessionId)
            }
        })
        fx.attach(player.audioSessionId)

        // Keeps the home screen widgets in sync with what is playing.
        widgetBridge = WidgetBridge(this, player)
        rgController = ReplayGainController(this, player, rgProcessor)
        fadeController = FadeController(this, player, rgProcessor)
        enhancerController = EnhancerController(this, enhancerProcessor)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = mediaSession?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        enhancerController?.release()
        enhancerController = null
        fadeController?.release()
        fadeController = null
        rgController?.release()
        rgController = null
        widgetBridge?.release()
        widgetBridge = null
        prefsListener?.let { getSharedPreferences("settings", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        SonyAudioEffects.get(this).release()
        AudioLevelMonitor.reset()
        super.onDestroy()
    }
}
