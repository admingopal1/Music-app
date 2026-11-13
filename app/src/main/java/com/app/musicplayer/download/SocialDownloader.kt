package com.app.musicplayer.download

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/**
 * Downloads public videos from social links (Instagram, Facebook, TikTok, X, Reddit, Vimeo...)
 * by running yt-dlp + ffmpeg inside the app (youtubedl-android). Android's DownloadManager can only
 * fetch direct media files, not web pages.
 *
 * Only public links work. Private posts and posts that need a login are reported as such;
 * nothing here tries to get around a login.
 */
object SocialDownloader {

    private const val TAG = "SocialDownloader"
    private const val UPDATE_INTERVAL_MS = 12L * 60L * 60L * 1000L

    sealed interface EngineState {
        data object Starting : EngineState
        data object Ready : EngineState
        data class Failed(val reason: String) : EngineState
    }

    enum class Status { RUNNING, DONE, FAILED, CANCELLED }

    /** [progress] is 0..1, or -1 while the size is not known yet. */
    data class Task(
        val id: String,
        val platform: String,
        val progress: Float,
        val status: Status,
        val message: String? = null,
    )

    class DownloadFailed(message: String, cause: Throwable? = null) : Exception(message, cause)
    class DownloadCancelled : Exception("Download cancelled")

    private class Saved(val displayName: String, val isVideo: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelled = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private val _engine = MutableStateFlow<EngineState>(EngineState.Starting)
    val engine: StateFlow<EngineState> = _engine.asStateFlow()

    private val _task = MutableStateFlow<Task?>(null)
    val task: StateFlow<Task?> = _task.asStateFlow()

    /** Increases after every saved file so screens can refresh their lists. */
    private val _savedCount = MutableStateFlow(0)
    val savedCount: StateFlow<Int> = _savedCount.asStateFlow()

    // ------------------------------------------------------------------ engine

    /** Call once from Application.onCreate. Unpacking Python takes a few seconds, so it runs off the main thread. */
    fun initEngine(app: Application) {
        Thread {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                _engine.value = EngineState.Ready
            } catch (e: Throwable) {
                Log.w(TAG, "Could not start the download engine", e)
                _engine.value = EngineState.Failed(e.message ?: e.javaClass.simpleName)
                return@Thread
            }
            updateEngineIfDue(app)
        }.apply {
            name = "ytdlp-init"
            isDaemon = true
        }.start()
    }

    /**
     * Instagram, Facebook and friends change their pages all the time, so an old yt-dlp stops working.
     * The engine can update itself without a new APK; this checks at most twice a day.
     */
    private fun updateEngineIfDue(app: Context) {
        val prefs = app.getSharedPreferences("social_dl", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_update", 0L) < UPDATE_INTERVAL_MS) return
        try {
            YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE)
            prefs.edit().putLong("last_update", now).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "yt-dlp update failed (will retry later)", e)
        }
    }

    // ------------------------------------------------------------------ download

    /** Starts a download that keeps running if the screen is closed. Only one at a time. */
    fun startDownload(ctx: Context, url: String, platform: String) {
        if (_task.value?.status == Status.RUNNING) return
        val app = ctx.applicationContext
        val id = "dl-${System.nanoTime()}"
        _task.value = Task(id, platform, progress = -1f, status = Status.RUNNING)
        scope.launch {
            try {
                val saved = execute(app, url, id) { p ->
                    _task.update { t -> if (t != null && t.id == id) t.copy(progress = p) else t }
                }
                val where = if (saved.isVideo) "your Videos" else "your Library"
                _task.value = Task(id, platform, 1f, Status.DONE, "Saved “${saved.displayName}”. Find it in $where.")
                _savedCount.update { it + 1 }
            } catch (e: DownloadCancelled) {
                _task.value = Task(id, platform, 0f, Status.CANCELLED, "Download cancelled.")
            } catch (e: Exception) {
                val text = (e as? DownloadFailed)?.message ?: explain(e)
                _task.value = Task(id, platform, 0f, Status.FAILED, text)
            } finally {
                cancelled.remove(id)
            }
        }
    }

    fun cancel() {
        val t = _task.value ?: return
        if (t.status != Status.RUNNING) return
        cancelled.add(t.id)
        // destroyProcessById can throw if the process already ended; the cancelled flag decides the outcome.
        runCatching { YoutubeDL.getInstance().destroyProcessById(t.id) }
    }

    /** Hides a finished task from the screen. */
    fun dismiss() {
        _task.update { t -> if (t != null && t.status != Status.RUNNING) null else t }
    }

    private suspend fun execute(ctx: Context, url: String, id: String, onProgress: (Float) -> Unit): Saved {
        when (val state = withTimeoutOrNull(45_000L) { engine.first { it !is EngineState.Starting } }) {
            EngineState.Ready -> Unit
            is EngineState.Failed -> throw DownloadFailed("The download engine could not start: ${state.reason}")
            else -> throw DownloadFailed("The download engine is still starting. Please try again in a moment.")
        }

        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dir = File(base, "social_dl/$id").apply { mkdirs() }
        try {
            val request = YoutubeDLRequest(url)
            request.addOption("-o", "${dir.absolutePath}/%(title).80s-%(id)s.%(ext)s")
            // Prefer MP4 so every player can open it; ffmpeg merges the separate video and audio streams.
            request.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
            request.addOption("--merge-output-format", "mp4")
            request.addOption("--no-playlist")
            request.addOption("--windows-filenames")
            request.addOption("--no-mtime")
            request.addOption("--socket-timeout", "20")

            try {
                YoutubeDL.getInstance().execute(
                    request = request,
                    processId = id,
                    callback = { progress, _, _ -> onProgress((progress / 100f).coerceIn(-1f, 1f)) },
                )
            } catch (e: Exception) {
                if (id in cancelled) throw DownloadCancelled()
                throw DownloadFailed(explain(e), e)
            }
            if (id in cancelled) throw DownloadCancelled()

            val output = dir.listFiles()
                ?.filter { it.isFile && !isTemporary(it.name) }
                ?.maxByOrNull { it.lastModified() }
                ?: throw DownloadFailed("No video was found in this post. It may be a photo or text post.")
            return save(ctx, output)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun isTemporary(name: String): Boolean =
        name.endsWith(".part") || name.endsWith(".ytdl") || name.endsWith(".temp") ||
            name.endsWith(".json") || name.endsWith(".jpg") || name.endsWith(".webp") ||
            Regex("""\.f\d+\.[A-Za-z0-9]+$""").containsMatchIn(name)

    // ------------------------------------------------------------------ saving

    /** Copies the finished file into the shared Movies / Music folder so the Videos tab and other apps see it. */
    private fun save(ctx: Context, file: File): Saved {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/mp4"
        val isAudio = mime.startsWith("audio/")

        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val collection = if (isAudio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isAudio) "Music/MusicPlayer" else "Movies/MusicPlayer")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw DownloadFailed("Could not create the file on this phone.")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open the new file for writing.")
                out.use { o -> file.inputStream().use { it.copyTo(o) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw DownloadFailed("Could not save the file: ${e.message}", e)
            }
            return Saved(file.name, !isAudio)
        }

        // Android 9 and older: keep it in the app's own media folder (no storage permission needed) and index it.
        val folder = ctx.getExternalFilesDir(if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES)
            ?: ctx.filesDir
        folder.mkdirs()
        var target = File(folder, file.name)
        var n = 1
        val base = file.name.substringBeforeLast('.', file.name)
        val suffix = if (file.name.contains('.')) "." + file.name.substringAfterLast('.') else ""
        while (target.exists()) target = File(folder, "$base ($n)$suffix").also { n++ }
        try {
            file.copyTo(target)
        } catch (e: Exception) {
            throw DownloadFailed("Could not save the file: ${e.message}", e)
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(target.path), arrayOf(mime), null)
        return Saved(target.name, !isAudio)
    }

    // ------------------------------------------------------------------ errors

    /** Turns yt-dlp's technical error text into something a person can act on. */
    private fun explain(e: Throwable): String {
        val raw = e.message.orEmpty()
        val m = raw.lowercase()
        return when {
            "private" in m || "login" in m || "log in" in m || "sign in" in m ||
                "cookies" in m || "authentication" in m || "age-restricted" in m ->
                "This post is private or needs a login. Only public links can be downloaded."
            "404" in m || "not found" in m || "removed" in m || "unavailable" in m || "deleted" in m || "expired" in m ->
                "This video is no longer available (it was deleted or the link expired)."
            "403" in m || "429" in m || "rate" in m && "limit" in m || "too many requests" in m ->
                "The site refused the request or is limiting access. Try again later."
            "unsupported url" in m ->
                "This link is not supported."
            "no video" in m || "there is no video" in m ->
                "No video was found in this post. It may be a photo or text post."
            "unable to resolve" in m || "timed out" in m || "timeout" in m || "connection" in m || "network" in m ->
                "Network problem. Check your internet connection and try again."
            else -> {
                val line = raw.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
                "Download failed" + if (line.isNotEmpty()) ": " + line.take(160) else "."
            }
        }
    }
}
