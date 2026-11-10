package com.app.musicplayer.download

import java.net.URI
import java.util.Locale

sealed interface DirectMediaUrlResult {
    data class Valid(val url: String, val fileName: String) : DirectMediaUrlResult
    data class Social(val platform: String) : DirectMediaUrlResult
    data class Invalid(val message: String) : DirectMediaUrlResult
}

/** Validates direct media links before handing them to Android DownloadManager. */
object DirectMediaUrlValidator {
    private val allowedExtensions = setOf(
        "mp4", "webm", "mkv", "mov", "m4v",
        "mp3", "m4a", "aac", "wav", "ogg", "flac",
    )

    fun validate(raw: String): DirectMediaUrlResult {
        val value = raw.trim()
        if (value.isEmpty()) return DirectMediaUrlResult.Invalid("Enter a media URL.")
        val uri = runCatching { URI(value) }.getOrNull()
            ?: return DirectMediaUrlResult.Invalid("The URL is malformed. Check the link and try again.")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            return DirectMediaUrlResult.Invalid("Only HTTP and HTTPS media links are supported.")
        }
        val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".")
            ?: return DirectMediaUrlResult.Invalid("The URL must include a valid website host.")
        if (!uri.rawUserInfo.isNullOrBlank()) {
            return DirectMediaUrlResult.Invalid("Links containing embedded usernames or passwords are not supported.")
        }
        if (uri.port !in -1..65_535) {
            return DirectMediaUrlResult.Invalid("The URL contains an invalid network port.")
        }

        if (host == "instagram.com" || host.endsWith(".instagram.com")) {
            return DirectMediaUrlResult.Social("Instagram")
        }
        if (host == "facebook.com" || host.endsWith(".facebook.com") ||
            host == "fb.watch" || host.endsWith(".fb.watch")
        ) {
            return DirectMediaUrlResult.Social("Facebook")
        }

        val fileName = uri.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return DirectMediaUrlResult.Invalid("The URL does not point to a media file.")
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in allowedExtensions) {
            return DirectMediaUrlResult.Invalid(
                "Use a direct MP4, WebM, MOV or supported audio-file URL. Web pages and streaming playlists are not direct files."
            )
        }
        return DirectMediaUrlResult.Valid(value, fileName)
    }
}
