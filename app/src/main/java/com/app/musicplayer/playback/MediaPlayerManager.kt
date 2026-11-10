package com.app.musicplayer.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

object MediaPlayerManager {
    val playbackSpeeds = PlaybackOptions.speeds
    val playbackPitches = PlaybackOptions.pitches

    fun setPlaybackSpeed(player: Player, speed: Float) {
        val supportedSpeed = PlaybackOptions.nearestSpeed(speed)
        player.setPlaybackParameters(PlaybackParameters(supportedSpeed, player.playbackParameters.pitch))
    }

    fun setPlaybackPitch(player: Player, pitch: Float) {
        val supportedPitch = PlaybackOptions.nearestPitch(pitch)
        player.setPlaybackParameters(PlaybackParameters(player.playbackParameters.speed, supportedPitch))
    }

    fun subtitleConfiguration(uri: Uri): MediaItem.SubtitleConfiguration? {
        val extension = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?: return null
        val mimeType = when (extension) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            else -> return null
        }
        return MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType)
            .setLanguage("und")
            .setLabel(extension.uppercase())
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }
}
