package com.app.musicplayer.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.app.musicplayer.service.MusicService

/** Handles the widget's previous / play-pause / next buttons by briefly connecting to the player service. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, MusicService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val c = try {
                future.get()
            } catch (e: Exception) {
                pending.finish()
                return@addListener
            }
            try {
                when (action) {
                    PlayerWidgets.ACTION_PLAY_PAUSE -> if (c.isPlaying) c.pause() else c.play()
                    PlayerWidgets.ACTION_NEXT -> c.seekToNextMediaItem()
                    PlayerWidgets.ACTION_PREV -> c.seekToPreviousMediaItem()
                }
            } catch (e: Exception) {
                c.release()
                pending.finish()
                return@addListener
            }
            // give the command a moment to reach the service before disconnecting
            Handler(Looper.getMainLooper()).postDelayed({
                c.release()
                pending.finish()
            }, 400)
        }, ContextCompat.getMainExecutor(app))
    }
}
