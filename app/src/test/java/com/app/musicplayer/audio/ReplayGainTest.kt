package com.app.musicplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayGainTest {

    private val settings = ReplayGain.Settings(
        mode = ReplayGain.MODE_TRACK,
        preampDb = 0f,
        fallbackDb = 0f,
        limit = true,
    )

    @Test
    fun parsesTrackAndAlbumTagsWithUnits() {
        val info = ReplayGain.fromTags(
            mapOf(
                "replaygain_track_gain" to "-6.25 dB",
                "replaygain_track_peak" to "0.75",
                "replaygain_album_gain" to "+2.0 dB",
                "replaygain_album_peak" to "0.90",
            )
        )
        assertEquals(-6.25f, info?.trackGain ?: Float.NaN, 0.001f)
        assertEquals(0.75f, info?.trackPeak ?: Float.NaN, 0.001f)
        assertEquals(2.0f, info?.albumGain ?: Float.NaN, 0.001f)
        assertEquals(0.90f, info?.albumPeak ?: Float.NaN, 0.001f)
    }

    @Test
    fun usesOpusR128Q78GainWithReferenceOffset() {
        val info = ReplayGain.fromTags(mapOf("r128_track_gain" to "256"))
        assertEquals(6.0f, info?.trackGain ?: Float.NaN, 0.001f)
    }

    @Test
    fun trackModeFallsBackToAlbumTagWhenTrackTagIsMissing() {
        val info = RgInfo(trackGain = null, trackPeak = null, albumGain = -3f, albumPeak = null)
        assertEquals(-3f, ReplayGain.appliedDb(info, settings) ?: Float.NaN, 0.001f)
    }

    @Test
    fun albumModePrefersAlbumGainAndPeakLimitsIt() {
        val info = RgInfo(trackGain = 8f, trackPeak = 0.5f, albumGain = 12f, albumPeak = 0.5f)
        val albumSettings = settings.copy(mode = ReplayGain.MODE_ALBUM)
        assertEquals(6.0206f, ReplayGain.appliedDb(info, albumSettings) ?: Float.NaN, 0.01f)
    }

    @Test
    fun disabledModeReturnsNull() {
        assertNull(ReplayGain.appliedDb(RgInfo(5f, 1f, 4f, 1f), settings.copy(mode = ReplayGain.MODE_OFF)))
    }

    @Test
    fun gainConversionUsesDecibels() {
        assertEquals(2.0f, ReplayGain.dbToLinear(6.0206f), 0.001f)
    }
    @Test
    fun ignoresNonFiniteTagNumbersAndUsesSafeFallback() {
        val info = ReplayGain.fromTags(
            mapOf(
                "replaygain_track_gain" to "999999999999999999999999",
                "replaygain_track_peak" to "NaN",
            )
        )
        assertNull(info)
        assertEquals(0f, ReplayGain.appliedDb(null, settings.copy(fallbackDb = Float.NaN)) ?: Float.NaN, 0f)
    }

    @Test
    fun appliedGainSanitizesNonFiniteMetadataAndSettings() {
        val info = RgInfo(
            trackGain = Float.NaN,
            trackPeak = Float.POSITIVE_INFINITY,
            albumGain = 4f,
            albumPeak = 0.5f,
        )
        assertEquals(4f, ReplayGain.appliedDb(info, settings.copy(preampDb = Float.NaN)) ?: Float.NaN, 0.001f)
    }

    @Test
    fun dbConversionNeverReturnsNonFiniteGain() {
        assertEquals(1f, ReplayGain.dbToLinear(Float.NaN), 0f)
        assertEquals(1f, ReplayGain.dbToLinear(Float.POSITIVE_INFINITY), 0f)
    }

}
