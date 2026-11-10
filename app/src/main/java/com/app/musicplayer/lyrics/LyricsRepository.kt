package com.app.musicplayer.lyrics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.app.musicplayer.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Finds lyrics for a song, in this order:
 * 1. an .lrc the user loaded for that song (stored privately in the app),
 * 2. an .lrc next to the audio file (only readable by apps on Android 10 and below),
 * 3. an .lrc in the lyrics folder chosen in Settings (matched by file name or "Artist - Title"),
 * 4. lyrics embedded in an MP3's ID3 tag (USLT).
 */
class LyricsRepository(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "lyrics").apply { mkdirs() }

    private val _folder = MutableStateFlow(prefs.getString("lyrics_tree", null))
    val folder: StateFlow<String?> = _folder.asStateFlow()

    @Volatile private var index: Map<String, Uri>? = null
    @Volatile private var indexTime = 0L

    fun setTree(uri: Uri?) {
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        prefs.edit().putString("lyrics_tree", uri?.toString()).apply()
        _folder.value = uri?.toString()
        index = null
    }

    /** Copies a validated, size-limited .lrc / .txt into app storage for this song. */
    fun importFor(songId: Long, uri: Uri): Boolean {
        if (songId < 0L) return false
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
        val supportedName = name?.lowercase()?.let { it.endsWith(".lrc") || it.endsWith(".txt") } == true
        if (!supportedName && mime?.startsWith("text/", ignoreCase = true) != true) return false

        val bytes = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { readLimited(it) }
        }.getOrNull() ?: return false
        if (Lrc.parse(decode(bytes)) == null) return false
        return runCatching {
            dir.mkdirs()
            File(dir, "$songId.lrc").writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    fun removeFor(songId: Long) { File(dir, "$songId.lrc").delete() }

    suspend fun load(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        if (song.id < 0) return@withContext null

        val own = File(dir, "${song.id}.lrc")
        if (own.exists()) {
            val l = runCatching { own.inputStream().use { readLimited(it)?.let { bytes -> Lrc.parse(decode(bytes)) } } }.getOrNull()
            if (l != null) return@withContext l
        }

        if (Build.VERSION.SDK_INT <= 29 && song.folder.isNotBlank() && song.fileName.isNotBlank()) {
            val side = File(song.folder, song.fileName.substringBeforeLast('.') + ".lrc")
            if (side.canRead()) {
                val l = runCatching { side.inputStream().use { readLimited(it)?.let { bytes -> Lrc.parse(decode(bytes)) } } }.getOrNull()
                if (l != null) return@withContext l
            }
        }

        val fromTree = runCatching { findInTree(song) }.getOrNull()
        if (fromTree != null) {
            val l = runCatching {
                ctx.contentResolver.openInputStream(fromTree)?.use { input ->
                    readLimited(input)?.let { Lrc.parse(decode(it)) }
                }
            }.getOrNull()
            if (l != null) return@withContext l
        }

        val embedded = runCatching {
            ctx.contentResolver.openInputStream(song.uri)?.use { Id3Lyrics.read(it) }
        }.getOrNull()
        if (embedded != null) return@withContext Lrc.parse(embedded)
        null
    }

    // ---------------------------------------------------------------- folder index

    private fun findInTree(song: Song): Uri? {
        val treeStr = _folder.value ?: return null
        val tree = Uri.parse(treeStr)
        var idx = index
        if (idx == null || System.currentTimeMillis() - indexTime > 120_000L) {
            idx = buildIndex(tree)
            index = idx
            indexTime = System.currentTimeMillis()
        }
        val keys = listOf(
            norm(song.fileName.substringBeforeLast('.')),
            norm(song.artist + " - " + song.title),
            norm(song.title),
        )
        for (k in keys) {
            if (k.isNotEmpty()) {
                val hit = idx[k]
                if (hit != null) return hit
            }
        }
        return null
    }

    private fun buildIndex(tree: Uri): Map<String, Uri> {
        val out = HashMap<String, Uri>()
        val stack = ArrayDeque<String>()
        stack.addLast(DocumentsContract.getTreeDocumentId(tree))
        var visited = 0
        while (stack.isNotEmpty() && visited < 30_000) {
            val parent = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    visited++
                    val id = c.getString(0)
                    val name = c.getString(1)
                    val mime = c.getString(2)
                    if (id != null && name != null) {
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            stack.addLast(id)
                        } else if (name.endsWith(".lrc", ignoreCase = true)) {
                            out[norm(name.substringBeforeLast('.'))] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        }
                    }
                }
            }
        }
        return out
    }

    private companion object {
        const val MAX_LYRICS_BYTES = 2 * 1024 * 1024
    }

    /** Reads at most 2 MiB, including for providers that do not report a content length. */
    private fun readLimited(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_LYRICS_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) String(bytes, Charsets.ISO_8859_1) else utf8
    }
}
