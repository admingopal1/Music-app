package com.app.musicplayer.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Lives inside the playback service: pushes title / artist / art / play state to the home screen widgets. */
class WidgetBridge(private val ctx: Context, private val player: Player) : Player.Listener {
    private val io = Executors.newSingleThreadExecutor()
    private var artKey: String? = null
    private val released = AtomicBoolean(false)
    private val publishLock = Any()

    init {
        player.addListener(this)
        push(player.currentMediaItem, player.isPlaying, forceArt = true)
    }

    override fun onEvents(p: Player, events: Player.Events) {
        push(p.currentMediaItem, p.isPlaying, forceArt = false)
    }

    private fun push(item: MediaItem?, playing: Boolean, forceArt: Boolean) {
        val title = item?.mediaMetadata?.title?.toString().orEmpty()
        val artist = item?.mediaMetadata?.artist?.toString().orEmpty()
        val key = item?.mediaId
        val artChanged = forceArt || key != artKey
        artKey = key
        io.execute {
            if (released.get()) return@execute
            try {
                val art = if (artChanged) loadArt(item) else null
                // Serialize the final publish with release so an old bridge cannot
                // publish after a replacement bridge has started updating the widget.
                synchronized(publishLock) {
                    if (released.get()) return@execute
                    PlayerWidgets.save(ctx, title, artist, playing, art, replaceArt = artChanged)
                    PlayerWidgets.refreshAll(ctx)
                }
            } catch (e: Exception) {
                // widgets are best effort
            }
        }
    }

    private fun loadArt(item: MediaItem?): Bitmap? {
        if (item == null) return null
        var bmp: Bitmap? = null
        val artUri = item.mediaMetadata.artworkUri
        if (artUri != null) {
            bmp = runCatching { ctx.contentResolver.openInputStream(artUri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
        if (bmp == null && Build.VERSION.SDK_INT >= 29) {
            val uri = item.localConfiguration?.uri
            if (uri != null) {
                bmp = runCatching { ctx.contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull()
            }
        }
        return bmp?.let { scaleDown(it, 256) }
    }

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / max(b.width, b.height)
        if (s >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    fun release(clearPlaybackState: Boolean = true) {
        synchronized(publishLock) {
            if (!released.compareAndSet(false, true)) return
        }
        player.removeListener(this)
        if (clearPlaybackState) {
            io.execute {
                val p = ctx.getSharedPreferences("widget_state", Context.MODE_PRIVATE)
                PlayerWidgets.save(
                    ctx, p.getString("title", "").orEmpty(), p.getString("artist", "").orEmpty(),
                    playing = false, art = null, replaceArt = false,
                )
                PlayerWidgets.refreshAll(ctx)
                io.shutdown()
            }
        } else {
            io.shutdown()
        }
    }
}
