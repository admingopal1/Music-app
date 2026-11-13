package com.app.musicplayer.download

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Metadata and a short-lived, site-authorized audio stream URL returned by yt-dlp. */
data class ExtractedAudioStream(
    val url: String,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    val thumbnailUrl: String?,
    val httpHeaders: Map<String, String>,
)

object AudioStreamExtractor {
    /**
     * Resolves public media pages only. Extraction runs on IO and uses yt-dlp's cancellable
     * process API; it never downloads or transcodes the media.
     */
    suspend fun extract(
        context: Context,
        pageUrl: String,
        onStage: (String) -> Unit = {},
    ): ExtractedAudioStream = withContext(Dispatchers.IO) {
        val parsed = android.net.Uri.parse(pageUrl.trim())
        require(parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank()) {
            "Enter a valid public http or https media link."
        }

        when (val state = withTimeoutOrNull(45_000L) {
            SocialDownloader.engine.first { it !is SocialDownloader.EngineState.Starting }
        }) {
            SocialDownloader.EngineState.Ready -> Unit
            is SocialDownloader.EngineState.Failed ->
                throw IllegalStateException("The media engine could not start: ${state.reason}")
            else -> throw IllegalStateException("The media engine is still starting. Try again shortly.")
        }

        onStage("Finding the audio stream…")
        val processId = "audio-${UUID.randomUUID()}"
        val request = YoutubeDLRequest(pageUrl.trim()).apply {
            addOption("-f", "bestaudio/best")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--no-progress")
            addOption("--dump-json")
        }

        val response = try {
            runInterruptible {
                YoutubeDL.getInstance().execute(request, processId, null)
            }
        } catch (cancelled: CancellationException) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            throw cancelled
        } catch (interrupted: InterruptedException) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            throw CancellationException("Audio extraction cancelled", interrupted)
        }

        onStage("Preparing background playback…")
        val info = try {
            JSONObject(response.out)
        } catch (error: Exception) {
            throw IllegalStateException("Could not read audio information from this public link.", error)
        }

        val streamUrl = info.optString("url").takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        } ?: throw IllegalStateException("This link did not provide a playable audio stream.")
        val headerJson = info.optJSONObject("http_headers")
        val headers = buildMap {
            if (headerJson != null) {
                val keys = headerJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = headerJson.optString(key)
                    if (key.isNotBlank() && value.isNotBlank()) put(key, value)
                }
            }
        }
        val thumbnail = info.optString("thumbnail").takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        }
        ExtractedAudioStream(
            url = streamUrl,
            title = info.optString("title").takeIf { it.isNotBlank() } ?: "Online audio",
            artist = info.optString("uploader").takeIf { it.isNotBlank() }
                ?: info.optString("extractor").takeIf { it.isNotBlank() } ?: "Online stream",
            durationSeconds = info.optInt("duration", 0).coerceAtLeast(0),
            thumbnailUrl = thumbnail,
            httpHeaders = headers,
        )
    }
}
