package com.app.musicplayer.audio

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.C
import com.app.musicplayer.CrashLog
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh
import kotlin.math.roundToInt

/** User-facing sound enhancer settings (stored in the "settings" preferences, keys enh_*). */
data class EnhancerSettings(
    val clarity: Float = 0f,       // 0..1  treble detail
    val crossfeed: Float = 0f,     // 0..1  headphone crossfeed
    val width: Float = 1f,         // 0..2  1 = normal, 0 = mono-ish, 2 = wide
    val balance: Float = 0f,       // -1..1 left .. right
    val mono: Boolean = false,
    val limiter: Boolean = false,  // soft limiter to avoid clipping
) {
    fun write(p: SharedPreferences) {
        p.edit().putFloat("enh_clarity", clarity).putFloat("enh_crossfeed", crossfeed).putFloat("enh_width", width)
            .putFloat("enh_balance", balance).putBoolean("enh_mono", mono).putBoolean("enh_limiter", limiter).apply()
    }

    companion object {
        fun read(p: SharedPreferences) = EnhancerSettings(
            clarity = p.getFloat("enh_clarity", 0f),
            crossfeed = p.getFloat("enh_crossfeed", 0f),
            width = p.getFloat("enh_width", 1f),
            balance = p.getFloat("enh_balance", 0f),
            mono = p.getBoolean("enh_mono", false),
            limiter = p.getBoolean("enh_limiter", false),
        )
    }
}

/**
 * Stereo DSP in front of the AudioTrack: treble clarity, headphone crossfeed, stereo width, mono, balance, soft limiter.
 * Works on 16-bit and float stereo; other formats pass through untouched.
 */
class EnhancerProcessor : BaseAudioProcessor() {
    @Volatile var settings = EnhancerSettings()
    private var encoding = C.ENCODING_INVALID
    private var rate = 44100
    private var cfL = 0f
    private var cfR = 0f
    private var hiL = 0f
    private var hiR = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (inputAudioFormat.channelCount != 2 || (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT)) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        encoding = enc
        rate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        return inputAudioFormat
    }

override fun queueInput(inputBuffer: ByteBuffer) {
        if (DspGuard.bypass) {
            val passthrough = replaceOutputBuffer(inputBuffer.remaining())
            passthrough.put(inputBuffer)
            passthrough.flip()
            return
        }
        val start = inputBuffer.position()
        try {
            process(inputBuffer)
        } catch (e: RuntimeException) {
            CrashLog.record("EnhancerProcessor", e)
            inputBuffer.position(start)
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
        }
    }

    private fun process(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val s = settings
        val neutral = s.clarity <= 0f && s.crossfeed <= 0f && s.width == 1f && s.balance == 0f && !s.mono && !s.limiter
        if (neutral) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val aCf = (1.0 - exp(-2.0 * PI * 700.0 / rate)).toFloat()
        val aHi = (1.0 - exp(-2.0 * PI * 4000.0 / rate)).toFloat()
        val g = s.clarity * 0.9f
        val k = s.crossfeed
        val norm = 1f / (1f + 0.5f * k)
        val gl = if (s.balance > 0f) 1f - s.balance else 1f
        val gr = if (s.balance < 0f) 1f + s.balance else 1f
        val is16 = encoding == C.ENCODING_PCM_16BIT
        val frame = if (is16) 4 else 8

        while (inputBuffer.remaining() >= frame) {
            var l: Float
            var r: Float
            if (is16) {
                l = inputBuffer.getShort() / 32768f
                r = inputBuffer.getShort() / 32768f
            } else {
                l = inputBuffer.getFloat()
                r = inputBuffer.getFloat()
            }
            if (g > 0f) {                                  // clarity: lift everything above ~4 kHz
                hiL += aHi * (l - hiL)
                hiR += aHi * (r - hiR)
                l += g * (l - hiL)
                r += g * (r - hiR)
            }
            if (k > 0f) {                                  // crossfeed: a little low-passed opposite channel
                cfL += aCf * (l - cfL)
                cfR += aCf * (r - cfR)
                val nl = (l + k * cfR) * norm
                val nr = (r + k * cfL) * norm
                l = nl
                r = nr
            }
            if (s.width != 1f) {                           // stereo width via mid/side
                val m = (l + r) * 0.5f
                val d = (l - r) * 0.5f * s.width
                l = m + d
                r = m - d
            }
            if (s.mono) {
                val m = (l + r) * 0.5f
                l = m
                r = m
            }
            l *= gl
            r *= gr
            if (s.limiter) {
                l = soft(l)
                r = soft(r)
            }
            if (is16) {
                out.putShort((l * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                out.putShort((r * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            } else {
                out.putFloat(l.coerceIn(-1f, 1f))
                out.putFloat(r.coerceIn(-1f, 1f))
            }
        }
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    private fun soft(x: Float): Float {
        val a = abs(x)
        if (a <= 0.85f) return x
        val y = 0.85f + 0.15f * tanh((a - 0.85f) / 0.15f)
        return if (x < 0f) -y else y
    }

    override fun onFlush() {
        cfL = 0f; cfR = 0f; hiL = 0f; hiR = 0f
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        onFlush()
    }
}

/** Keeps [EnhancerProcessor] in sync with the settings. */
class EnhancerController(ctx: Context, private val processor: EnhancerProcessor) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith("enh_")) apply()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        apply()
    }

    fun apply() { processor.settings = EnhancerSettings.read(prefs) }
    fun release() { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}
