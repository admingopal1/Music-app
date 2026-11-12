package com.app.musicplayer.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the current queue and position so MediaSession can restore playback when
 * Android or a Bluetooth media button asks the service to resume after process death.
 */
class PlaybackResumeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Snapshot(
        val items: List<MediaItem>,
        val startIndex: Int,
        val positionMs: Long,
        val repeatMode: Int,
        val shuffleEnabled: Boolean,
        val speed: Float,
        val pitch: Float,
    )

    fun save(player: Player) {
        val count = player.mediaItemCount
        val currentIndex = player.currentMediaItemIndex
        if (count <= 0 || currentIndex !in 0 until count) {
            clear()
            return
        }

        // Bound preference size while keeping the currently playing item in the saved window.
        val windowStart = if (count <= MAX_QUEUE_ITEMS) 0
            else (currentIndex - MAX_QUEUE_ITEMS / 2).coerceIn(0, count - MAX_QUEUE_ITEMS)
        val windowEnd = minOf(count, windowStart + MAX_QUEUE_ITEMS)
        val queue = JSONArray()
        var restoredIndex = -1

        for (index in windowStart until windowEnd) {
            val item = player.getMediaItemAt(index)
            val uri = item.localConfiguration?.uri ?: return
            val metadata = item.mediaMetadata
            val entry = JSONObject()
                .put("id", item.mediaId)
                .put("uri", uri.toString())
                .put("title", metadata.title?.toString().orEmpty())
                .put("artist", metadata.artist?.toString().orEmpty())
                .put("album", metadata.albumTitle?.toString().orEmpty())
                .put("artwork", metadata.artworkUri?.toString().orEmpty())
            queue.put(entry)
            if (index == currentIndex) restoredIndex = queue.length() - 1
        }

        if (restoredIndex < 0) return
        val position = if (player.playbackState == Player.STATE_ENDED) 0L
            else player.currentPosition.coerceAtLeast(0L)

        prefs.edit()
            .putString(KEY_QUEUE, queue.toString())
            .putInt(KEY_INDEX, restoredIndex)
            .putLong(KEY_POSITION, position)
            .putInt(KEY_REPEAT, player.repeatMode)
            .putBoolean(KEY_SHUFFLE, player.shuffleModeEnabled)
            .putFloat(KEY_SPEED, player.playbackParameters.speed)
            .putFloat(KEY_PITCH, player.playbackParameters.pitch)
            .apply()
    }

    fun restore(): Snapshot? {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return null
        return runCatching {
            val json = JSONArray(raw)
            if (json.length() == 0 || json.length() > MAX_QUEUE_ITEMS) return null
            val items = ArrayList<MediaItem>(json.length())
            for (i in 0 until json.length()) {
                val entry = json.getJSONObject(i)
                val uriText = entry.getString("uri")
                val uri = Uri.parse(uriText)
                require(uri.scheme in setOf("content", "file", "http", "https"))
                val metadataBuilder = MediaMetadata.Builder()
                    .setTitle(entry.optString("title").ifBlank { null })
                    .setArtist(entry.optString("artist").ifBlank { null })
                    .setAlbumTitle(entry.optString("album").ifBlank { null })
                entry.optString("artwork").takeIf { it.isNotBlank() }?.let {
                    metadataBuilder.setArtworkUri(Uri.parse(it))
                }
                items += MediaItem.Builder()
                    .setMediaId(entry.optString("id").ifBlank { uriText })
                    .setUri(uri)
                    .setMediaMetadata(metadataBuilder.build())
                    .build()
            }
            val index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, items.lastIndex)
            Snapshot(
                items = items,
                startIndex = index,
                positionMs = prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L),
                repeatMode = prefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF)
                    .coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL),
                shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE, false),
                speed = prefs.getFloat(KEY_SPEED, 1f).takeIf { it.isFinite() && it in 0.25f..4f } ?: 1f,
                pitch = prefs.getFloat(KEY_PITCH, 1f).takeIf { it.isFinite() && it in 0.25f..4f } ?: 1f,
            )
        }.getOrElse {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_QUEUE).remove(KEY_INDEX).remove(KEY_POSITION)
            .remove(KEY_REPEAT).remove(KEY_SHUFFLE).remove(KEY_SPEED).remove(KEY_PITCH).apply()
    }

    private companion object {
        const val PREFS_NAME = "playback_resume"
        const val KEY_QUEUE = "queue"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position_ms"
        const val KEY_REPEAT = "repeat_mode"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_SPEED = "speed"
        const val KEY_PITCH = "pitch"
        const val MAX_QUEUE_ITEMS = 500
    }
}
