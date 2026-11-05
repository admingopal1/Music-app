package com.app.musicplayer.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaStoreRepository {
    suspend fun loadSongs(ctx: Context): List<Song> = withContext(Dispatchers.IO) {
        val collection: Uri = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"
        val order = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val out = ArrayList<Song>()
        try {
            ctx.contentResolver.query(collection, projection, selection, null, order)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val artist = c.getString(iArtist)?.takeUnless { it == "<unknown>" } ?: "Unknown artist"
                    out += Song(
                        id = id,
                        title = c.getString(iTitle) ?: "Unknown",
                        artist = artist,
                        album = c.getString(iAlbum) ?: "Unknown album",
                        albumId = c.getLong(iAlbumId),
                        durationMs = c.getLong(iDur),
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    )
                }
            }
        } catch (e: SecurityException) {
            // permission not granted yet
        }
        out
    }
}

/** Loads cover art as a Bitmap (needed for Palette). Tries thumbnail API, embedded tag, legacy albumart URI. */
object ArtLoader {
    private val cache = LruCache<Long, Bitmap>(24)

    suspend fun load(ctx: Context, song: Song): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(song.id)
            ?: (thumbnail(ctx, song) ?: embedded(ctx, song) ?: legacy(ctx, song))?.also { cache.put(song.id, it) }
    }

    private fun thumbnail(ctx: Context, s: Song): Bitmap? =
        if (Build.VERSION.SDK_INT >= 29) {
            try { ctx.contentResolver.loadThumbnail(s.uri, Size(600, 600), null) } catch (e: Throwable) { null }
        } else null

    private fun embedded(ctx: Context, s: Song): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, s.uri)
            r.embeddedPicture?.let { bytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1200) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun legacy(ctx: Context, s: Song): Bitmap? = try {
        ctx.contentResolver.openInputStream(s.albumArtUri)?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Throwable) {
        null
    }
}
