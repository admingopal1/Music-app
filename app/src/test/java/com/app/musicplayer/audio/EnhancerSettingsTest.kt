package com.app.musicplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EnhancerSettingsTest {
    @Test
    fun normalizedClampsAllNumericControlsToSupportedRanges() {
        val safe = EnhancerSettings(
            clarity = 4f,
            crossfeed = -2f,
            width = 9f,
            balance = -3f,
            mono = true,
            limiter = true,
        ).normalized()

        assertEquals(1f, safe.clarity, 0f)
        assertEquals(0f, safe.crossfeed, 0f)
        assertEquals(2f, safe.width, 0f)
        assertEquals(-1f, safe.balance, 0f)
        assertEquals(true, safe.mono)
        assertEquals(true, safe.limiter)
    }

    @Test
    fun normalizedLeavesValidSettingsUnchanged() {
        val settings = EnhancerSettings(
            clarity = 0.4f,
            crossfeed = 0.25f,
            width = 1.2f,
            balance = 0.15f,
        )
        assertEquals(settings, settings.normalized())
    }

    @Test
    fun defaultsAreNeutralAndDoNotEnableOptionalProcessing() {
        val settings = EnhancerSettings().normalized()
        assertEquals(0f, settings.clarity, 0f)
        assertEquals(0f, settings.crossfeed, 0f)
        assertEquals(1f, settings.width, 0f)
        assertEquals(0f, settings.balance, 0f)
        assertFalse(settings.mono)
        assertFalse(settings.limiter)
    }
}
