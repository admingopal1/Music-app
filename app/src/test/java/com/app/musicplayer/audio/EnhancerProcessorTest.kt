package com.app.musicplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class EnhancerProcessorTest {
    private val rate = 44100

    private fun processor(enc: Int, s: EnhancerSettings): EnhancerProcessor {
        val p = EnhancerProcessor()
        p.settings = s
        val out = p.configure(AudioProcessor.AudioFormat(rate, 2, enc))
        assertEquals(enc, out.encoding)
        p.flush()
        return p
    }

    private fun stereoSine(frames: Int, amp: Float, hz: Double = 220.0, phase: Int = 0) =
        FloatArray(frames * 2) { val i = it / 2 + phase; amp * sin(2 * PI * hz * i / rate).toFloat() }

    private fun runFloat(p: EnhancerProcessor, input: FloatArray, chunk: Int = 4096): FloatArray {
        val res = FloatArray(input.size)
        var w = 0
        var i = 0
        while (i < input.size) {
            val n = minOf(chunk, input.size - i)
            val buf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            for (k in 0 until n) buf.putFloat(input[i + k])
            buf.flip()
            p.queueInput(buf)
            val out = p.output
            while (out.hasRemaining()) res[w++] = out.float
            i += n
        }
        assertEquals(input.size, w)
        return res
    }

    private fun run16(p: EnhancerProcessor, input: ShortArray): ShortArray {
        val buf = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.nativeOrder())
        input.forEach { buf.putShort(it) }
        buf.flip()
        p.queueInput(buf)
        val out = p.output
        return ShortArray(input.size) { out.short }
    }

    private fun rms(x: FloatArray, from: Int = x.size / 2) =
        sqrt((from until x.size).sumOf { (x[it] * x[it]).toDouble() } / (x.size - from)).toFloat()

    @Test fun neutralSettingsAreBitExact16() {
        val p = processor(C.ENCODING_PCM_16BIT, EnhancerSettings())
        val input = ShortArray(8192) { ((it * 7919) % 65536 - 32768).toShort() }
        assertArrayEquals(input, run16(p, input))
    }

    @Test fun neutralSettingsAreBitExactFloat() {
        val p = processor(C.ENCODING_PCM_FLOAT, EnhancerSettings())
        val input = stereoSine(4096, 0.8f)
        assertArrayEquals(input, runFloat(p, input), 0f)
    }

    @Test fun everyPresetStaysBelowFullScaleOnHotInput() {
        for ((name, preset) in EnhancerSettings.PRESETS) {
            for (hz in doubleArrayOf(60.0, 1000.0, 9000.0)) {
                val p = processor(C.ENCODING_PCM_FLOAT, preset)
                val out = runFloat(p, stereoSine(rate * 2, 0.99f, hz))
                val peak = out.maxOf { abs(it) }
                assertTrue("$name @ $hz Hz peak=$peak", peak <= 1f && out.all { it.isFinite() })
            }
        }
    }

    @Test fun maximalSettingsAreStableAndClean() {
        val s = EnhancerSettings(clarity = 1f, crossfeed = 1f, width = 2f, upscale = 1f, bass = 1f,
            aiEq = true, autoLevel = true, night = true)
        val p = processor(C.ENCODING_PCM_FLOAT, s)
        val noise = java.util.Random(1)
        val out = runFloat(p, FloatArray(rate * 2 * 4) { (noise.nextGaussian() * 0.3).toFloat() })
        assertTrue(out.all { it.isFinite() && abs(it) <= 1f })
    }

    @Test fun bassEnhancementAddsLowEndEnergy() {
        val dry = rms(runFloat(processor(C.ENCODING_PCM_FLOAT, EnhancerSettings()), stereoSine(rate, 0.2f, 70.0)))
        val wet = rms(runFloat(processor(C.ENCODING_PCM_FLOAT, EnhancerSettings(bass = 1f)), stereoSine(rate, 0.2f, 70.0)))
        assertTrue("dry=$dry wet=$wet", wet > dry * 1.3f)
    }

    @Test fun monoBassWidthKeepsLowFrequencySideUnchanged() {
        val p = processor(C.ENCODING_PCM_FLOAT, EnhancerSettings(width = 2f))
        val frames = rate
        val input = FloatArray(frames * 2) { val i = it / 2; val v = 0.2f * sin(2 * PI * 50.0 * i / rate).toFloat(); if (it % 2 == 0) v else -v }
        val out = runFloat(p, input)
        val side = FloatArray(frames) { (out[2 * it] - out[2 * it + 1]) * 0.5f }
        val inSide = FloatArray(frames) { (input[2 * it] - input[2 * it + 1]) * 0.5f }
        assertTrue(rms(side) < rms(inSide) * 1.05f)
    }

    @Test fun autoLevelPullsQuietMusicUp() {
        val p = processor(C.ENCODING_PCM_FLOAT, EnhancerSettings(autoLevel = true))
        val input = stereoSine(rate * 20, 0.02f, 1000.0)
        val out = runFloat(p, input)
        assertTrue(rms(out, out.size - rate) > rms(input, input.size - rate) * 1.5f)
    }

    @Test fun endOfStreamDrainsLookAheadSoNoSamplesAreLost() {
        val p = processor(C.ENCODING_PCM_FLOAT, EnhancerSettings(limiter = true))
        val input = stereoSine(rate / 2, 0.5f, 440.0)
        val out = runFloat(p, input)
        p.queueEndOfStream()
        val tail = p.output
        val drained = tail.remaining() / 4
        val all = out + FloatArray(drained) { tail.float }
        assertTrue(p.isEnded)
        val delay = all.size - input.size
        assertTrue("delay=$delay", delay > 0)
        for (i in input.indices) assertEquals(input[i], all[i + delay], 1e-4f)
    }

    @Test fun limiterHoldsCeilingOnSuddenPeaks() {
        val p = processor(C.ENCODING_PCM_FLOAT, EnhancerSettings(limiter = true))
        val input = FloatArray(rate * 2) { i -> val f = i / 2; if ((f / 2000) % 2 == 0) 0.1f else if (f % 2000 == 0) 0.999f else 0.3f }
        val peak = runFloat(p, input).maxOf { abs(it) }
        assertTrue("peak=$peak", peak <= 0.95f)
    }

    @Test fun mono16BitPassesThroughUnconfigured() {
        val p = EnhancerProcessor()
        p.settings = EnhancerSettings(bass = 1f)
        val out = p.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, out)
        assertTrue(!p.isActive)
    }
}
