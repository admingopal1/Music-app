package com.app.musicplayer.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectMediaUrlValidatorTest {
    @Test fun acceptsDirectHttpsVideoWithQueryParameters() {
        val result = DirectMediaUrlValidator.validate(" https://cdn.example.com/path/clip.mp4?token=abc ")
        assertTrue(result is DirectMediaUrlResult.Valid)
        assertEquals("clip.mp4", (result as DirectMediaUrlResult.Valid).fileName)
    }

    @Test fun acceptsDirectHttpAudio() {
        val result = DirectMediaUrlValidator.validate("http://media.example.com/song.flac")
        assertTrue(result is DirectMediaUrlResult.Valid)
    }

    @Test fun rejectsUnsupportedSchemesAndMalformedHosts() {
        assertTrue(DirectMediaUrlValidator.validate("file:///sdcard/song.mp3") is DirectMediaUrlResult.Invalid)
        assertTrue(DirectMediaUrlValidator.validate("https:///song.mp3") is DirectMediaUrlResult.Invalid)
        assertTrue(DirectMediaUrlValidator.validate("not a url") is DirectMediaUrlResult.Invalid)
    }

    @Test fun rejectsWebPagesAndStreamingPlaylists() {
        assertTrue(DirectMediaUrlValidator.validate("https://example.com/watch.html") is DirectMediaUrlResult.Invalid)
        assertTrue(DirectMediaUrlValidator.validate("https://example.com/stream.m3u8") is DirectMediaUrlResult.Invalid)
    }

    @Test fun identifiesSocialHostsWithoutSubstringFalsePositives() {
        assertEquals("Instagram", (DirectMediaUrlValidator.validate("https://www.instagram.com/p/abc") as DirectMediaUrlResult.Social).platform)
        assertEquals("Facebook", (DirectMediaUrlValidator.validate("https://m.facebook.com/watch/abc") as DirectMediaUrlResult.Social).platform)
        assertTrue(DirectMediaUrlValidator.validate("https://notinstagram.com/video.mp4") is DirectMediaUrlResult.Valid)
        assertTrue(DirectMediaUrlValidator.validate("https://instagram.com.evil.example/video.mp4") is DirectMediaUrlResult.Valid)
    }

    @Test fun identifiesAdditionalGlobalVideoAndSocialPlatforms() {
        val cases = mapOf(
            "https://www.snapchat.com/spotlight/abc" to "Snapchat",
            "https://www.tiktok.com/@user/video/123" to "TikTok",
            "https://vimeo.com/123456" to "Vimeo",
            "https://www.dailymotion.com/video/abc" to "Dailymotion",
            "https://redd.it/abc123" to "Reddit",
            "https://pin.it/abc123" to "Pinterest",
            "https://fb.watch/abc123" to "Facebook",
        )
        cases.forEach { (url, expected) ->
            val result = DirectMediaUrlValidator.validate(url)
            assertTrue("$url should be identified as a social link", result is DirectMediaUrlResult.Social)
            assertEquals(expected, (result as DirectMediaUrlResult.Social).platform)
        }
    }

    @Test fun rejectsEmbeddedCredentialsAndInvalidPorts() {
        assertTrue(DirectMediaUrlValidator.validate("https://user:pass@example.com/video.mp4") is DirectMediaUrlResult.Invalid)
        assertTrue(DirectMediaUrlValidator.validate("https://example.com:99999/video.mp4") is DirectMediaUrlResult.Invalid)
    }

    @Test fun identifiesCommonIndianAndGlobalVideoPlatforms() {
        val cases = mapOf(
            "https://youtu.be/abc123" to "YouTube",
            "https://x.com/user/status/123" to "X",
            "https://t.me/channel/123" to "Telegram",
            "https://sharechat.com/video/abc" to "ShareChat",
            "https://mojapp.in/video/abc" to "Moj",
            "https://myjosh.in/video/abc" to "Josh",
            "https://www.chingari.io/video/abc" to "Chingari",
            "https://www.roposo.com/video/abc" to "Roposo",
        )
        cases.forEach { (url, expected) ->
            val result = DirectMediaUrlValidator.validate(url)
            assertTrue("$url should be identified as a social link", result is DirectMediaUrlResult.Social)
            assertEquals(expected, (result as DirectMediaUrlResult.Social).platform)
        }
    }
}
