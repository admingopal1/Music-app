package com.app.musicplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class AudioLevelProcessorTest {
    @Test
    fun spectrumAnalysisPreservesPcmAndConsumesInput() {
        val previousBypass = DspGuard.bypass
        val processor = AudioLevelProcessor()
        try {
            DspGuard.bypass = false
            processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            processor.flush()

            val input = ByteBuffer.allocateDirect(1_024 * 2 * 2).order(ByteOrder.nativeOrder())
            repeat(1_024) { frame ->
                val sample = (sin(frame * 2.0 * Math.PI * 440.0 / 44_100.0) * 12_000).toInt().toShort()
                input.putShort(sample)
                input.putShort(sample)
            }
            input.flip()
            val expected = ByteArray(input.remaining())
            input.duplicate().get(expected)

            processor.queueInput(input)

            assertFalse("AudioProcessor must consume the input buffer", input.hasRemaining())
            val output = processor.output
            assertEquals("AudioProcessor must pass through every PCM byte", expected.size, output.remaining())
            val actual = ByteArray(output.remaining())
            output.get(actual)
            assertArrayEquals(expected, actual)
        } finally {
            processor.reset()
            DspGuard.bypass = previousBypass
        }
    }
    @Test
    fun floatSpectrumIgnoresNonFiniteSamplesAndPreservesOriginalPcm() {
        val previousBypass = DspGuard.bypass
        val processor = AudioLevelProcessor()
        try {
            DspGuard.bypass = false
            AudioLevelMonitor.reset()
            processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT))
            processor.flush()

            val input = ByteBuffer.allocateDirect(1_024 * 2 * 4).order(ByteOrder.nativeOrder())
            repeat(1_024) { frame ->
                val sample = (sin(frame * 2.0 * Math.PI * 440.0 / 44_100.0) * 0.5).toFloat()
                input.putFloat(if (frame == 0) Float.NaN else sample)
                input.putFloat(if (frame == 1) Float.POSITIVE_INFINITY else sample)
            }
            input.flip()
            val expected = ByteArray(input.remaining())
            input.duplicate().get(expected)

            processor.queueInput(input)

            assertFalse("AudioProcessor must consume the input buffer", input.hasRemaining())
            val output = processor.output
            val actual = ByteArray(output.remaining())
            output.get(actual)
            assertArrayEquals("Spectrum analysis must not modify source audio", expected, actual)
            assertTrue("Spectrum values must remain finite", AudioLevelMonitor.levels.value.all { it.isFinite() })
        } finally {
            processor.reset()
            AudioLevelMonitor.reset()
            DspGuard.bypass = previousBypass
        }
    }

}
