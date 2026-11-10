package com.app.musicplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackOptionsTest {
    @Test fun speedSnapsToNearestSupportedValue() {
        assertEquals(1.25f, PlaybackOptions.nearestSpeed(1.3f), 0.0001f)
        assertEquals(3f, PlaybackOptions.nearestSpeed(9f), 0.0001f)
    }

    @Test fun invalidSpeedFallsBackToNormal() {
        assertEquals(1f, PlaybackOptions.nearestSpeed(Float.NaN), 0.0001f)
        assertEquals(1f, PlaybackOptions.nearestSpeed(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test fun pitchSnapsToNearestSupportedValue() {
        assertEquals(0.9f, PlaybackOptions.nearestPitch(0.88f), 0.0001f)
        assertEquals(2f, PlaybackOptions.nearestPitch(5f), 0.0001f)
    }

    @Test fun invalidPitchFallsBackToNormal() {
        assertEquals(1f, PlaybackOptions.nearestPitch(Float.NEGATIVE_INFINITY), 0.0001f)
    }

    @Test fun sleepDurationHandlesDisableAndLargeInputs() {
        assertEquals(0L, PlaybackOptions.sleepDurationMs(0))
        assertEquals(60_000L, PlaybackOptions.sleepDurationMs(1))
        assertEquals(7L * 24L * 60L * 60_000L, PlaybackOptions.sleepDurationMs(Int.MAX_VALUE))
    }

    @Test fun remainingTimeNeverGoesNegative() {
        assertEquals(4_000L, PlaybackOptions.remainingMs(10_000L, 6_000L))
        assertEquals(0L, PlaybackOptions.remainingMs(10_000L, 11_000L))
    }

    @Test fun sleepFadeOnlyChangesDuringFinalFifteenSeconds() {
        assertEquals(1f, PlaybackOptions.sleepFadeVolume(30_000L), 0.0001f)
        assertEquals(0.5f, PlaybackOptions.sleepFadeVolume(7_500L), 0.0001f)
        assertEquals(0f, PlaybackOptions.sleepFadeVolume(0L), 0.0001f)
    }
}
