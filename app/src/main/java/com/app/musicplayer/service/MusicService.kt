package com.app.musicplayer.service

import android.app.PendingIntent
import android.content.SharedPreferences
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.app.musicplayer.MainActivity
import com.app.musicplayer.audio.AudioFxManager

class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
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
        val fx = AudioFxManager.get(this)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                fx.attach(audioSessionId)
            }
        })
        fx.attach(player.audioSessionId)

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
        prefsListener?.let { getSharedPreferences("settings", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        AudioFxManager.get(this).release()
        super.onDestroy()
    }
}
