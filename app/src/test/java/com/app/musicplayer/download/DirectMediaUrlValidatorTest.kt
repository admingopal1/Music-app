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

    @Test fun identifiesMoreSocialHosts() {
        assertEquals("TikTok", (DirectMediaUrlValidator.validate("https://vm.tiktok.com/ZMabc/") as DirectMediaUrlResult.Social).platform)
        assertEquals("X", (DirectMediaUrlValidator.validate("https://x.com/user/status/1") as DirectMediaUrlResult.Social).platform)
        assertEquals("X", (DirectMediaUrlValidator.validate("https://twitter.com/user/status/1") as DirectMediaUrlResult.Social).platform)
        assertEquals("Reddit", (DirectMediaUrlValidator.validate("https://www.reddit.com/r/a/comments/b/c/") as DirectMediaUrlResult.Social).platform)
        assertEquals("Vimeo", (DirectMediaUrlValidator.validate("https://vimeo.com/22439234") as DirectMediaUrlResult.Social).platform)
        assertTrue(DirectMediaUrlValidator.validate("https://notx.com/video.mp4") is DirectMediaUrlResult.Valid)
        assertTrue(DirectMediaUrlValidator.validate("https://tiktok.com.evil.example/video.mp4") is DirectMediaUrlResult.Valid)
    }

    @Test fun rejectsEmbeddedCredentialsAndInvalidPorts() {
        assertTrue(DirectMediaUrlValidator.validate("https://user:pass@example.com/video.mp4") is DirectMediaUrlResult.Invalid)
        assertTrue(DirectMediaUrlValidator.validate("https://example.com:99999/video.mp4") is DirectMediaUrlResult.Invalid)
    }
}
