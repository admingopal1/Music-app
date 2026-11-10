package com.app.musicplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Lightweight PCM spectrum meter. It observes the decoded audio stream inside Media3;
 * it does not record microphone input and does not require RECORD_AUDIO permission.
 */
object AudioLevelMonitor {
    const val BAND_COUNT = 24
    private val _levels = MutableStateFlow(List(BAND_COUNT) { 0f })
    val levels: StateFlow<List<Float>> = _levels.asStateFlow()

    internal fun publish(values: FloatArray) {
        _levels.value = List(BAND_COUNT) { i -> values.getOrElse(i) { 0f }.coerceIn(0f, 1f) }
    }

    fun reset() {
        _levels.value = List(BAND_COUNT) { 0f }
    }
}

/** Pass-through Media3 processor that computes a smoothed FFT spectrum from decoded PCM. */
class AudioLevelProcessor : BaseAudioProcessor() {
    private val fftSize = 1024
    private val bandCount = AudioLevelMonitor.BAND_COUNT
    private val real = FloatArray(fftSize)
    private val imaginary = FloatArray(fftSize)
    private val smoothed = FloatArray(bandCount)
    private val window = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))).toFloat()
    }
    private var sampleCount = 0
    private var channelCount = 2
    private var encoding = C.ENCODING_INVALID
    private var lastPublishNs = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) return AudioProcessor.AudioFormat.NOT_SET
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        encoding = inputAudioFormat.encoding
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        val output = replaceOutputBuffer(size)
        if (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT) {
            val samples = inputBuffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
            val bytesPerSample = if (encoding == C.ENCODING_PCM_16BIT) 2 else 4
            val bytesPerFrame = channelCount * bytesPerSample
            while (samples.remaining() >= bytesPerFrame) {
                var mono = 0f
                repeat(channelCount) {
                    mono += if (encoding == C.ENCODING_PCM_16BIT) {
                        samples.short / 32768f
                    } else {
                        samples.float.coerceIn(-1f, 1f)
                    }
                }
                acceptSample(mono / channelCount)
            }
        }
        output.put(inputBuffer)
        output.flip()
    }

    private fun acceptSample(sample: Float) {
        real[sampleCount] = sample
        sampleCount++
        if (sampleCount < fftSize) return
        sampleCount = 0
        for (i in 0 until fftSize) {
            real[i] *= window[i]
            imaginary[i] = 0f
        }
        fft()
        val values = FloatArray(bandCount)
        val maxBin = fftSize / 2
        for (band in 0 until bandCount) {
            val start = max(1, ((maxBin - 1).toDouble().pow(band.toDouble() / bandCount)).toInt())
            val end = max(start + 1, ((maxBin - 1).toDouble().pow((band + 1.0) / bandCount)).toInt())
                .coerceAtMost(maxBin)
            var energy = 0f
            for (bin in start until end) {
                energy += real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            }
            val magnitude = sqrt(energy / (end - start).coerceAtLeast(1))
            values[band] = (magnitude * 18f).coerceIn(0f, 1f)
            smoothed[band] = max(values[band], smoothed[band] * 0.78f)
        }
        val now = System.nanoTime()
        if (now - lastPublishNs >= 33_000_000L) {
            lastPublishNs = now
            AudioLevelMonitor.publish(smoothed)
        }
    }

    /** In-place radix-2 Cooley-Tukey FFT. */
    private fun fft() {
        var j = 0
        for (i in 1 until fftSize) {
            var bit = fftSize shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im
            }
        }
        var length = 2
        while (length <= fftSize) {
            val angle = (-2.0 * PI / length).toFloat()
            val wLenReal = kotlin.math.cos(angle).toFloat()
            val wLenImag = kotlin.math.sin(angle).toFloat()
            var base = 0
            while (base < fftSize) {
                var wReal = 1f
                var wImag = 0f
                for (offset in 0 until length / 2) {
                    val even = base + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * wReal - imaginary[odd] * wImag
                    val oddImag = real[odd] * wImag + imaginary[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImag
                    real[even] += oddReal
                    imaginary[even] += oddImag
                    val nextReal = wReal * wLenReal - wImag * wLenImag
                    wImag = wReal * wLenImag + wImag * wLenReal
                    wReal = nextReal
                }
                base += length
            }
            length = length shl 1
        }
    }

    override fun onFlush() {
        sampleCount = 0
        lastPublishNs = 0L
        real.fill(0f)
        imaginary.fill(0f)
        smoothed.fill(0f)
        AudioLevelMonitor.reset()
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        onFlush()
    }
}
