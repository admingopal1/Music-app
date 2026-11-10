package com.app.musicplayer.audio

import com.app.musicplayer.audio.dsp.Biquad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class BiquadTest {
    private val rate = 48000

    private fun gainDb(f: Biquad, hz: Double): Double {
        val n = rate
        var inE = 0.0; var outE = 0.0
        for (i in 0 until n) {
            val x = sin(2 * PI * hz * i / rate).toFloat() * 0.5f
            val y = f.process(x)
            if (i > n / 2) { inE += x * x; outE += y * y }
        }
        return 20 * log10(sqrt(outE / inE))
    }

    @Test fun lowPassPassesLowsAndCutsHighs() {
        assertEquals(0.0, gainDb(Biquad().lowPass(rate, 1000.0), 100.0), 0.2)
        assertEquals(-3.0, gainDb(Biquad().lowPass(rate, 1000.0), 1000.0), 0.3)
        assertTrue(gainDb(Biquad().lowPass(rate, 1000.0), 10000.0) < -35)
    }

    @Test fun highPassCutsLows() {
        assertTrue(gainDb(Biquad().highPass(rate, 1000.0), 100.0) < -35)
        assertEquals(0.0, gainDb(Biquad().highPass(rate, 1000.0), 10000.0), 0.2)
    }

    @Test fun peakingHitsTargetGainAtCentre() {
        assertEquals(6.0, gainDb(Biquad().peaking(rate, 3000.0, 1.0, 6.0), 3000.0), 0.2)
        assertEquals(-4.0, gainDb(Biquad().peaking(rate, 300.0, 1.0, -4.0), 300.0), 0.2)
        assertEquals(0.0, gainDb(Biquad().peaking(rate, 3000.0, 1.0, 6.0), 100.0), 0.3)
    }

    @Test fun shelvesReachFullGainOutsideCorner() {
        assertEquals(5.0, gainDb(Biquad().lowShelf(rate, 90.0, 5.0), 20.0), 0.4)
        assertEquals(0.0, gainDb(Biquad().lowShelf(rate, 90.0, 5.0), 5000.0), 0.2)
        assertEquals(5.0, gainDb(Biquad().highShelf(rate, 9000.0, 5.0), 20000.0), 0.6)
    }

    @Test fun bandPassUnityAtCentre() {
        assertEquals(0.0, gainDb(Biquad().bandPass(rate, 1000.0, 1.41), 1000.0), 0.2)
    }

    @Test fun frequencyAboveNyquistIsClampedAndStable() {
        val f = Biquad().highPass(22050, 16000.0)
        var maxAbs = 0f
        for (i in 0 until 22050) maxAbs = maxOf(maxAbs, kotlin.math.abs(f.process(if (i % 2 == 0) 1f else -1f)))
        assertTrue(maxAbs.isFinite() && maxAbs < 4f)
    }
}
