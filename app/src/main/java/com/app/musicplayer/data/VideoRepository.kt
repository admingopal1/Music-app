package com.app.musicplayer.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Video(
    val id: Long,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val folder: String,
    val uri: Uri,
)

object VideoRepository {
    suspend fun loadVideos(ctx: Context): List<Video> = withContext(Dispatchers.IO) {
        val collection: Uri = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        // DATA is deprecated and may be unavailable for scoped-storage media on Android 10+.
        // RELATIVE_PATH is the supported folder hint on modern Android versions.
        val folderColumn = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Video.Media.RELATIVE_PATH
        else
            MediaStore.Video.Media.DATA
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            folderColumn,
        )
        val order = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val out = ArrayList<Video>()
        try {
            ctx.contentResolver.query(collection, projection, null, null, order)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val iW = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val iH = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val iFolder = c.getColumnIndex(folderColumn)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    out += Video(
                        id = id,
                        title = (c.getString(iName) ?: "Video").substringBeforeLast('.'),
                        durationMs = c.getLong(iDur),
                        sizeBytes = c.getLong(iSize),
                        width = c.getInt(iW),
                        height = c.getInt(iH),
                        folder = if (iFolder >= 0) {
                            val path = c.getString(iFolder).orEmpty().trimEnd('/')
                            if (Build.VERSION.SDK_INT >= 29) path.substringAfterLast('/', path)
                            else path.substringBeforeLast('/', "")
                        } else "",
                        uri = ContentUris.withAppendedId(collection, id),
                    )
                }
            }
        } catch (e: SecurityException) {
            // permission not granted yet
        }
        out
    }
}

/** Small cached thumbnails for the video list. */
object VideoThumbs {
    private val cache = LruCache<Long, Bitmap>(80)

    suspend fun load(ctx: Context, v: Video): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(v.id) ?: try {
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.loadThumbnail(v.uri, Size(320, 180), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    ctx.contentResolver, v.id, MediaStore.Video.Thumbnails.MINI_KIND, null,
                )
            }
        } catch (e: Throwable) {
            null
        }?.also { cache.put(v.id, it) }
    }
}
