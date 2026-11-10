package com.app.musicplayer.ringtone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class HookFinderTest {
    private val frameMs = 100

    /** Builds a synthetic song: sections of (seconds, level, band profile). */
    private fun song(vararg sections: Triple<Int, Float, FloatArray>): Pair<FloatArray, Array<FloatArray>> {
        val rnd = Random(7)
        val rms = ArrayList<Float>()
        val bands = ArrayList<FloatArray>()
        for ((sec, level, profile) in sections) repeat(sec * 10) {
            val j = 1f + (rnd.nextFloat() - 0.5f) * 0.2f
            rms += level * j
            bands += FloatArray(profile.size) { profile[it] * level * level * j }
        }
        return rms.toFloatArray() to bands.toTypedArray()
    }

    private val intro = floatArrayOf(1f, 0.5f, 0.3f, 0.2f, 0.1f, 0.05f)
    private val verse = floatArrayOf(0.6f, 1f, 0.8f, 0.4f, 0.2f, 0.1f)
    private val chorus = floatArrayOf(1f, 0.7f, 0.6f, 1f, 0.8f, 0.5f)

    @Test fun picksTheRepeatedLoudChorus() {
        val (rms, bands) = song(
            Triple(15, 0.05f, intro), Triple(30, 0.2f, verse), Triple(30, 0.5f, chorus),
            Triple(30, 0.2f, verse), Triple(30, 0.5f, chorus), Triple(10, 0.05f, intro),
        )
        val h = HookFinder.find(rms, bands, frameMs, 20_000)
        val inChorus1 = h.startMs in 43_000L..56_000L
        val inChorus2 = h.startMs in 103_000L..116_000L
        assertTrue("start=${h.startMs}", inChorus1 || inChorus2)
        assertEquals(20_000L, h.endMs - h.startMs)
        assertTrue(h.reason, h.reason.contains("Chorus"))
    }

    @Test fun avoidsTheIntroEvenIfLoud() {
        val (rms, bands) = song(Triple(10, 0.5f, chorus), Triple(60, 0.3f, verse), Triple(40, 0.45f, chorus))
        val h = HookFinder.find(rms, bands, frameMs, 30_000)
        assertTrue("start=${h.startMs}", h.startMs >= 60_000L)
    }

    @Test fun shortSongReturnsWholeSong() {
        val (rms, bands) = song(Triple(10, 0.3f, verse))
        val h = HookFinder.find(rms, bands, frameMs, 30_000)
        assertEquals(0L, h.startMs)
        assertEquals(10_000L, h.endMs)
    }

    @Test fun fadesRampSamplesToZeroAtEdges() {
        val pcm = ShortArray(44100 * 2) { 10000 }
        RingtoneEngine.applyFades(pcm, 2, 44100, 250, 250)
        assertEquals(0, pcm[0].toInt())
        assertEquals(0, pcm[pcm.size - 1].toInt())
        assertEquals(10000, pcm[pcm.size / 2].toInt())
    }
}
