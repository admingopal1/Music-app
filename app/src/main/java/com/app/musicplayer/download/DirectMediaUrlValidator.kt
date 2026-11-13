package com.app.musicplayer.download

import java.net.URI
import java.util.Locale

sealed interface DirectMediaUrlResult {
    data class Valid(val url: String, val fileName: String) : DirectMediaUrlResult
    data class Social(val platform: String) : DirectMediaUrlResult
    data class Invalid(val message: String) : DirectMediaUrlResult
}

/**
 * Accepts direct media files and identifies common social/video post URLs.
 * A post URL is not treated as a media file: platform restrictions are never bypassed.
 */
object DirectMediaUrlValidator {
    private val allowedExtensions = setOf(
        "mp4", "webm", "mkv", "mov", "m4v",
        "mp3", "m4a", "aac", "wav", "ogg", "flac",
    )

    private val socialHosts = linkedMapOf(
        "Instagram" to setOf("instagram.com"),
        "Facebook" to setOf("facebook.com", "fb.watch"),
        "YouTube" to setOf("youtube.com", "youtu.be"),
        "X" to setOf("x.com", "twitter.com"),
        "Telegram" to setOf("t.me", "telegram.me", "telegram.org"),
        "ShareChat" to setOf("sharechat.com"),
        "Moj" to setOf("mojapp.in"),
        "Josh" to setOf("myjosh.in", "joshapp.com"),
        "Chingari" to setOf("chingari.io", "chingari.in"),
        "Roposo" to setOf("roposo.com"),
        "Snapchat" to setOf("snapchat.com"),
        "TikTok" to setOf("tiktok.com"),
        "Vimeo" to setOf("vimeo.com"),
        "Dailymotion" to setOf("dailymotion.com", "dai.ly"),
        "Reddit" to setOf("reddit.com", "redd.it"),
        "Pinterest" to setOf("pinterest.com", "pin.it"),
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

        val platform = socialHosts.entries.firstOrNull { (_, domains) ->
            domains.any { domain -> host == domain || host.endsWith(".$domain") }
        }?.key
        if (platform != null) return DirectMediaUrlResult.Social(platform)

        val fileName = uri.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return DirectMediaUrlResult.Invalid("The URL does not point to a media file.")
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in allowedExtensions) {
            return DirectMediaUrlResult.Invalid(
                "This link is not a direct media file. Use a direct MP4, WebM, MOV or supported audio-file URL."
            )
        }
        return DirectMediaUrlResult.Valid(value, fileName)
    }
}
