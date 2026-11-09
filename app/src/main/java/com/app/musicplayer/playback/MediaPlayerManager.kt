package com.app.musicplayer.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

object MediaPlayerManager {
    val playbackSpeeds = listOf(0.25f, 0.5f, 1f, 1.25f, 1.5f, 2f, 3f)
    val playbackPitches = listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 2f)

    fun setPlaybackSpeed(player: Player, speed: Float) {
        val supportedSpeed = playbackSpeeds.minBy { kotlin.math.abs(it - speed) }
        player.setPlaybackParameters(PlaybackParameters(supportedSpeed, player.playbackParameters.pitch))
    }

    fun setPlaybackPitch(player: Player, pitch: Float) {
        val supportedPitch = playbackPitches.minBy { kotlin.math.abs(it - pitch) }
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
