package com.app.musicplayer.audio

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.app.musicplayer.audio.dsp.Biquad
import com.app.musicplayer.audio.dsp.Envelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/** User-facing Hi-Fi engine settings (stored in the "settings" preferences, keys enh_*). */
data class EnhancerSettings(
    val clarity: Float = 0f,       // 0..1  presence + air, de-mud
    val crossfeed: Float = 0f,     // 0..1  BS2B-style headphone crossfeed
    val width: Float = 1f,         // 0..2  1 = normal; bass always stays centred
    val balance: Float = 0f,       // -1..1 left .. right
    val mono: Boolean = false,
    val limiter: Boolean = false,  // engage the engine just for the look-ahead limiter (always in-path when the engine runs)
    val upscale: Float = 0f,       // 0..1  Hi-Res upscaler: rebuilds treble cut off by lossy codecs
    val bass: Float = 0f,          // 0..1  psychoacoustic bass (harmonics + low shelf)
    val aiEq: Boolean = false,     // adaptive, per-track spectral correction
    val autoLevel: Boolean = false,// smart volume levelling
    val night: Boolean = false,    // night mode compressor
    val preset: String = "Off",
) {
    val neutral: Boolean
        get() = clarity <= 0f && crossfeed <= 0f && width == 1f && balance == 0f && !mono && !limiter &&
            upscale <= 0f && bass <= 0f && !aiEq && !autoLevel && !night

    fun write(p: SharedPreferences) {
        p.edit().putFloat("enh_clarity", clarity).putFloat("enh_crossfeed", crossfeed).putFloat("enh_width", width)
            .putFloat("enh_balance", balance).putBoolean("enh_mono", mono).putBoolean("enh_limiter", limiter)
            .putFloat("enh_upscale", upscale).putFloat("enh_bass", bass).putBoolean("enh_ai_eq", aiEq)
            .putBoolean("enh_auto_level", autoLevel).putBoolean("enh_night", night).putString("enh_preset", preset)
            .apply()
    }

    companion object {
        fun read(p: SharedPreferences) = EnhancerSettings(
            clarity = p.getFloat("enh_clarity", 0f),
            crossfeed = p.getFloat("enh_crossfeed", 0f),
            width = p.getFloat("enh_width", 1f),
            balance = p.getFloat("enh_balance", 0f),
            mono = p.getBoolean("enh_mono", false),
            limiter = p.getBoolean("enh_limiter", false),
            upscale = p.getFloat("enh_upscale", 0f),
            bass = p.getFloat("enh_bass", 0f),
            aiEq = p.getBoolean("enh_ai_eq", false),
            autoLevel = p.getBoolean("enh_auto_level", false),
            night = p.getBoolean("enh_night", false),
            preset = p.getString("enh_preset", "Off") ?: "Off",
        )

        const val CUSTOM = "Custom"

        /** One-tap sound signatures for the Hi-Fi engine. */
        val PRESETS: Map<String, EnhancerSettings> = linkedMapOf(
            "Off" to EnhancerSettings(),
            "AI Smart" to EnhancerSettings(aiEq = true, upscale = 0.35f, bass = 0.25f, clarity = 0.25f, autoLevel = true, preset = "AI Smart"),
            "Studio" to EnhancerSettings(aiEq = true, upscale = 0.5f, clarity = 0.15f, crossfeed = 0.3f, preset = "Studio"),
            "Deep Bass" to EnhancerSettings(bass = 0.8f, clarity = 0.2f, upscale = 0.2f, preset = "Deep Bass"),
            "Vocal" to EnhancerSettings(clarity = 0.65f, upscale = 0.3f, width = 0.9f, preset = "Vocal"),
            "Concert" to EnhancerSettings(width = 1.6f, bass = 0.35f, upscale = 0.4f, clarity = 0.2f, preset = "Concert"),
            "Night" to EnhancerSettings(night = true, autoLevel = true, bass = 0.2f, preset = "Night"),
        )
    }
}

/** Live numbers from the engine for the UI (analyser bars, AI decisions, limiter activity). */
data class EngineStats(
    val bandsDb: List<Float> = List(EngineAnalyzer.BANDS.size) { -90f },
    val correctionDb: List<Float> = List(EngineAnalyzer.BANDS.size) { 0f },
    val scene: String = "",
    val lossySource: Boolean = false,
    val autoUpscale: Float = 0f,
    val levelDb: Float = -90f,
    val autoLevelDb: Float = 0f,
    val gainReductionDb: Float = 0f,
)

/** Shared between the playback service (writer) and the UI (reader); both live in the app process. */
object EngineMonitor {
    private val _stats = MutableStateFlow(EngineStats())
    val stats: StateFlow<EngineStats> = _stats.asStateFlow()

    /** Set by the UI while the analyser is on screen so analysis also runs when the engine is bypassed. */
    @Volatile var listening = false

    internal fun publish(s: EngineStats) { _stats.value = s }
}

/**
 * Octave-band spectrum analyser plus the "AI" decisions built on it: per-track corrective EQ towards a
 * reference spectrum of well-mastered music, lossy-source (treble cut-off) detection and a coarse scene label.
 */
class EngineAnalyzer(private val rate: Int) {
    private val filters = Array(BANDS.size) { Biquad().bandPass(rate, BANDS[it], 1.41) }
    private val acc = DoubleArray(BANDS.size)
    private var accTotal = 0.0
    private var peak = 0f
    private var count = 0
    private val block = rate / 10
    private val fast = FloatArray(BANDS.size) { -90f }
    private val slow = FloatArray(BANDS.size) { -90f }
    private var slowReady = 0
    val correction = FloatArray(BANDS.size)
    var lossy = false; private set
    var hfDeficit = 0f; private set
    var autoUpscale = 0f; private set
    var scene = ""; private set
    var levelDb = -90f; private set
    var crestDb = 12f; private set

    /** Returns true when a new analysis block finished (every 100 ms). */
    fun push(mid: Float): Boolean {
        for (i in filters.indices) {
            val y = filters[i].process(mid)
            acc[i] += (y * y).toDouble()
        }
        accTotal += (mid * mid).toDouble()
        val a = abs(mid)
        if (a > peak) peak = a
        if (++count < block) return false
        finishBlock()
        return true
    }

    private fun finishBlock() {
        val rms = sqrt(accTotal / count).toFloat()
        levelDb = toDb(rms)
        crestDb = 0.9f * crestDb + 0.1f * (toDb(peak) - levelDb)
        val gated = levelDb < -55f
        for (i in BANDS.indices) {
            val db = toDb(sqrt(acc[i] / count).toFloat())
            fast[i] += 0.35f * (db - fast[i])
            if (!gated) slow[i] = if (slowReady < 5) db else slow[i] + 0.04f * (db - slow[i])
            acc[i] = 0.0
        }
        if (!gated) slowReady++
        accTotal = 0.0; peak = 0f; count = 0
        if (slowReady >= 5) decide()
        else scene = if (gated) "Listening…" else "Analysing…"
    }

    private fun decide() {
        // Spectrum relative to the mid bands (125 Hz – 4 kHz) so overall volume doesn't matter.
        val mean = (1..6).map { slow[it] }.average().toFloat()
        val refMean = (1..6).map { REFERENCE[it] }.average().toFloat()
        val d = FloatArray(BANDS.size) { (slow[it] - mean) - (REFERENCE[it] - refMean) }

        hfDeficit = (d[7] - d[8]).coerceAtLeast(0f)
        lossy = hfDeficit > 12f && d[8] < -10f
        autoUpscale = ((hfDeficit - 6f) / 18f).coerceIn(0f, 1f)

        for (i in BANDS.indices) {
            var target = (-d[i] * 0.5f).coerceIn(-6f, 4f)
            if (i == BANDS.lastIndex && lossy) target = min(target, 0f) // nothing there to boost, the upscaler handles it
            correction[i] += 0.08f * (target - correction[i])
        }

        val bassD = (d[0] + d[1]) / 2f
        val vocalD = (d[4] + d[5] + d[6]) / 3f
        scene = when {
            levelDb < -55f -> "Listening…"
            lossy -> "Compressed source · rebuilding highs"
            bassD > 4f -> "Bass-heavy · EDM / Hip-hop"
            vocalD > 3f -> "Vocal-forward · Pop / Bollywood"
            bassD < -4f -> "Light & airy · Acoustic / Classical"
            crestDb < 9f -> "Loud master · protecting dynamics"
            else -> "Balanced mix"
        }
    }

    fun bands(): List<Float> = fast.toList()

    fun reset() {
        filters.forEach { it.reset() }
        acc.fill(0.0); accTotal = 0.0; count = 0; peak = 0f
    }

    companion object {
        val BANDS = doubleArrayOf(63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

        /** Long-term average octave-band level of commercially mastered music (dB, relative). */
        val REFERENCE = floatArrayOf(2f, 2.5f, 1f, 0f, -1.5f, -3.5f, -6f, -9.5f, -15f)

        fun toDb(x: Float): Float = if (x <= 1e-6f) -120f else 20f * log10(x)
    }
}

/**
 * Hi-Fi DSP engine in front of the AudioTrack. Signal flow:
 * analyser → AI EQ → psychoacoustic bass → clarity → Hi-Res upscaler → crossfeed → width (mono bass) →
 * mono / balance → auto level → night compressor → look-ahead limiter → TPDF dither.
 * Works on 16-bit and float stereo; other formats pass through untouched.
 */
class EnhancerProcessor : BaseAudioProcessor() {
    @Volatile var settings = EnhancerSettings()
    private var encoding = C.ENCODING_INVALID
    private var rate = 44100
    private var built: EnhancerSettings? = null

    private var analyzer = EngineAnalyzer(rate)
    private val aiL = Array(EngineAnalyzer.BANDS.size) { Biquad() }
    private val aiR = Array(EngineAnalyzer.BANDS.size) { Biquad() }
    private val aiApplied = FloatArray(EngineAnalyzer.BANDS.size)

    // bass engine
    private val bassLp1 = Biquad(); private val bassLp2 = Biquad()
    private val harmHp = Biquad(); private val harmLp = Biquad()
    private var bassEnv = Envelope(rate, 5.0, 80.0)
    private val shelfL = Biquad(); private val shelfR = Biquad()
    private val subL = Biquad(); private val subR = Biquad()
    // clarity
    private val mudL = Biquad(); private val mudR = Biquad()
    private val presL = Biquad(); private val presR = Biquad()
    private val airL = Biquad(); private val airR = Biquad()
    // upscaler
    private val upBandL = arrayOf(Biquad(), Biquad()); private val upBandR = arrayOf(Biquad(), Biquad())
    private val upOutL = arrayOf(Biquad(), Biquad()); private val upOutR = arrayOf(Biquad(), Biquad())
    // crossfeed
    private val cfLpL = Biquad(); private val cfLpR = Biquad()
    private val cfDelay = FloatArray(128)
    private var cfLen = 13
    private var cfPos = 0
    // width
    private val sideLp = Biquad()
    // auto level
    private val kHp = Biquad(); private val kShelf = Biquad()
    private var loudMs = 0.0
    private var loudCoef = 0.0
    private var agcDb = 0f
    private var agcGain = 1f
    private var agcCoef = 0f
    // night compressor
    private var compEnv = Envelope(rate, 8.0, 220.0)
    private var compGain = 1f
    private var compTick = 0
    // limiter
    private var laLen = 192
    private var laL = FloatArray(laLen)
    private var laR = FloatArray(laLen)
    private var laPos = 0
    private var limTarget = 1f
    private var limGain = 1f
    private var limHold = 0
    private var limAtt = 0f
    private var limRel = 0f
    private var minGainBlock = 1f
    // dither
    private var seed = 0x2545F491

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (inputAudioFormat.channelCount != 2 || (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT)) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    init { prepare() }

    private fun prepare() {
        analyzer = EngineAnalyzer(rate)
        bassEnv = Envelope(rate, 5.0, 80.0)
        compEnv = Envelope(rate, 8.0, 220.0)
        bassLp1.lowPass(rate, 120.0); bassLp2.lowPass(rate, 120.0)
        harmHp.highPass(rate, 140.0); harmLp.lowPass(rate, 480.0)
        subL.highPass(rate, 25.0); subR.highPass(rate, 25.0)
        for (b in upBandL + upBandR) b.reset()
        upBandL[0].highPass(rate, 5000.0); upBandR[0].highPass(rate, 5000.0)
        upBandL[1].lowPass(rate, 11000.0); upBandR[1].lowPass(rate, 11000.0)
        for (b in upOutL + upOutR) b.highPass(rate, 11000.0)
        cfLpL.lowPass(rate, 700.0, 0.5); cfLpR.lowPass(rate, 700.0, 0.5)
        cfLen = (rate * 0.0003).toInt().coerceIn(1, 63)
        sideLp.lowPass(rate, 150.0)
        kHp.highPass(rate, 60.0, 0.5); kShelf.highShelf(rate, 1500.0, 4.0)
        loudCoef = 1.0 - exp(-1.0 / (rate * 3.0))
        agcCoef = Envelope.coef(rate, 60.0)
        laLen = (rate * 0.004).toInt().coerceAtLeast(16)
        laL = FloatArray(laLen); laR = FloatArray(laLen); laPos = 0
        limAtt = (1.0 - exp(-3.0 / laLen)).toFloat()
        limRel = Envelope.coef(rate, 120.0)
        aiApplied.fill(0f)
        built = null
        resetState()
    }

    private fun rebuild(s: EnhancerSettings) {
        val b = s.bass.toDouble()
        shelfL.lowShelf(rate, 90.0, b * 5.0); shelfR.lowShelf(rate, 90.0, b * 5.0)
        val c = s.clarity.toDouble()
        mudL.peaking(rate, 300.0, 0.9, -c * 2.5); mudR.peaking(rate, 300.0, 0.9, -c * 2.5)
        presL.peaking(rate, 3000.0, 1.0, c * 3.0); presR.peaking(rate, 3000.0, 1.0, c * 3.0)
        airL.highShelf(rate, 9000.0, c * 5.0); airR.highShelf(rate, 9000.0, c * 5.0)
        built = s
    }

    private fun updateAiEq(enabled: Boolean) {
        for (i in aiApplied.indices) {
            val want = if (enabled) analyzer.correction[i] else 0f
            if (abs(want - aiApplied[i]) > 0.05f) {
                aiApplied[i] = want
                val hz = EngineAnalyzer.BANDS[i]
                aiL[i].peaking(rate, hz, 1.0, want.toDouble())
                aiR[i].peaking(rate, hz, 1.0, want.toDouble())
            }
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val s = settings
        val listening = EngineMonitor.listening
        if (s.neutral && !listening) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        if (built != s) rebuild(s)

        val is16 = encoding == C.ENCODING_PCM_16BIT
        val frame = if (is16) 4 else 8
        val bypass = s.neutral
        val aiActive = s.aiEq && !bypass
        val anyAiEq = aiActive || aiApplied.any { it != 0f }
        val upAmt = max(s.upscale, if (s.aiEq) analyzer.autoUpscale * 0.6f else 0f) * 0.7f
        val bassAmt = s.bass
        val clarity = s.clarity > 0f
        val cf = s.crossfeed * 0.45f
        val cfNorm = 1f / (1f + cf)
        val widen = s.width != 1f
        val widthLow = min(s.width, 1f)
        val widthNorm = 1f / (1f + max(0f, s.width - 1f) * 0.25f)
        val gl = if (s.balance > 0f) 1f - s.balance else 1f
        val gr = if (s.balance < 0f) 1f + s.balance else 1f
        val boosting = s.bass > 0f || s.clarity > 0f || upAmt > 0f || s.aiEq || s.autoLevel || s.night || s.width > 1f
        val ceiling = 0.944f // -0.5 dBFS

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
            if (analyzer.push((l + r) * 0.5f)) {
                updateAiEq(aiActive)
                publish(s)
            }
            if (bypass) {
                write(out, l, r, is16, dither = false)
                continue
            }

            if (anyAiEq) {
                for (i in aiL.indices) {
                    if (aiApplied[i] != 0f) { l = aiL[i].process(l); r = aiR[i].process(r) }
                }
            }
            if (bassAmt > 0f) {
                l = subL.process(l); r = subR.process(r)
                val low = bassLp2.process(bassLp1.process((l + r) * 0.5f))
                val env = bassEnv.process(abs(low))
                val n = (low / (env + 1e-4f)).coerceIn(-2f, 2f)
                val harm = harmLp.process(harmHp.process((abs(n) - 0.6366f + 0.3f * n * abs(n)) * env))
                val add = harm * bassAmt
                l = shelfL.process(l) + add
                r = shelfR.process(r) + add
            }
            if (clarity) {
                l = airL.process(presL.process(mudL.process(l)))
                r = airR.process(presR.process(mudR.process(r)))
            }
            if (upAmt > 0f) {
                val bl = upBandL[1].process(upBandL[0].process(l))
                val br = upBandR[1].process(upBandR[0].process(r))
                l += upOutL[1].process(upOutL[0].process(abs(bl) + 0.5f * bl * abs(bl))) * upAmt
                r += upOutR[1].process(upOutR[0].process(abs(br) + 0.5f * br * abs(br))) * upAmt
            }
            if (cf > 0f) {
                val lpL = cfLpL.process(l)
                val lpR = cfLpR.process(r)
                val idx = cfPos
                val back = (idx - cfLen * 2 + cfDelay.size) % cfDelay.size
                val dL = cfDelay[back]
                val dR = cfDelay[back + 1]
                cfDelay[idx] = lpL; cfDelay[idx + 1] = lpR
                cfPos = (idx + 2) % cfDelay.size
                val nl = (l + cf * dR) * cfNorm
                val nr = (r + cf * dL) * cfNorm
                l = nl; r = nr
            }
            if (widen) {
                val m = (l + r) * 0.5f
                val side = (l - r) * 0.5f
                val sLow = sideLp.process(side)
                val ns = sLow * widthLow + (side - sLow) * s.width
                l = (m + ns) * widthNorm
                r = (m - ns) * widthNorm
            }
            if (s.mono) {
                val m = (l + r) * 0.5f
                l = m; r = m
            }
            l *= gl
            r *= gr

            if (s.autoLevel) {
                val k = kShelf.process(kHp.process((l + r) * 0.5f))
                loudMs += loudCoef * (k * k - loudMs)
                l *= agcGain; r *= agcGain
                agcGain += agcCoef * (dbToLin(agcDb) - agcGain)
            }
            if (s.night) {
                if (compTick-- <= 0) {
                    compTick = 8
                    val lvl = EngineAnalyzer.toDb(compEnv.value)
                    val over = lvl - COMP_THRESHOLD_DB
                    val gr2 = if (over > 0f) over * (1f - 1f / 3f) else 0f
                    compGain = dbToLin(COMP_MAKEUP_DB - gr2)
                }
                compEnv.process(max(abs(l), abs(r)))
                l *= compGain; r *= compGain
            }
            run {
                val p = max(abs(l), abs(r))
                val need = if (p > ceiling) ceiling / p else 1f
                if (need < limTarget) { limTarget = need; limHold = laLen }
                else if (limHold > 0) limHold--
                else limTarget += limRel * (1f - limTarget)
                limGain += (if (limTarget < limGain) limAtt else limRel) * (limTarget - limGain)
                if (limGain < minGainBlock) minGainBlock = limGain
                val dl = laL[laPos]; val dr = laR[laPos]
                laL[laPos] = l; laR[laPos] = r
                laPos = (laPos + 1) % laLen
                l = soft(dl * limGain); r = soft(dr * limGain)
            }
            write(out, l, r, is16, dither = is16 && boosting)
        }
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    private fun write(out: ByteBuffer, l: Float, r: Float, is16: Boolean, dither: Boolean) {
        if (is16) {
            val d1 = if (dither) tpdf() else 0f
            val d2 = if (dither) tpdf() else 0f
            out.putShort(Math.round(l * 32768f + d1).coerceIn(-32768, 32767).toShort())
            out.putShort(Math.round(r * 32768f + d2).coerceIn(-32768, 32767).toShort())
        } else {
            out.putFloat(l.coerceIn(-1f, 1f))
            out.putFloat(r.coerceIn(-1f, 1f))
        }
    }

    private fun publish(s: EnhancerSettings) {
        if (s.autoLevel) {
            val loud = 10f * log10(loudMs.coerceAtLeast(1e-12).toFloat())
            if (loud > -50f) {
                val want = (TARGET_LEVEL_DB - loud).coerceIn(-9f, 9f)
                agcDb += (want - agcDb).coerceIn(-0.25f, 0.25f)
            }
        }
        EngineMonitor.publish(
            EngineStats(
                bandsDb = analyzer.bands(),
                correctionDb = if (s.aiEq) aiApplied.toList() else List(aiApplied.size) { 0f },
                scene = analyzer.scene,
                lossySource = analyzer.lossy,
                autoUpscale = if (s.aiEq) analyzer.autoUpscale else 0f,
                levelDb = analyzer.levelDb,
                autoLevelDb = if (s.autoLevel) agcDb else 0f,
                gainReductionDb = (-EngineAnalyzer.toDb(minGainBlock)).coerceAtLeast(0f),
            )
        )
        minGainBlock = 1f
    }

    private fun tpdf(): Float {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
        val a = (seed and 0xFFFF) / 65535f
        val b = ((seed ushr 16) and 0xFFFF) / 65535f
        return a - b
    }

    private fun soft(x: Float): Float {
        val a = abs(x)
        if (a <= 0.95f) return x
        val y = 0.95f + 0.05f * tanh((a - 0.95f) / 0.05f)
        return if (x < 0f) -y else y
    }

    private fun resetState() {
        analyzer.reset()
        (aiL + aiR + arrayOf(bassLp1, bassLp2, harmHp, harmLp, shelfL, shelfR, subL, subR, mudL, mudR, presL, presR,
            airL, airR, cfLpL, cfLpR, sideLp, kHp, kShelf) + upBandL + upBandR + upOutL + upOutR).forEach { it.reset() }
        bassEnv.reset()
        compEnv.reset()
        cfDelay.fill(0f); cfPos = 0
        laL.fill(0f); laR.fill(0f); laPos = 0
        limTarget = 1f; limGain = 1f; limHold = 0
        compGain = 1f; compTick = 0
        agcGain = dbToLin(agcDb)
    }

    /** The pending format becomes active here (see BaseAudioProcessor.flush). */
    override fun onFlush() {
        val fmt = inputAudioFormat
        if (fmt != AudioProcessor.AudioFormat.NOT_SET) {
            encoding = fmt.encoding
            val newRate = fmt.sampleRate.coerceAtLeast(8000)
            if (newRate != rate) { rate = newRate; prepare(); return }
        }
        resetState()
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        agcDb = 0f
        resetState()
    }

    companion object {
        private const val TARGET_LEVEL_DB = -17f
        private const val COMP_THRESHOLD_DB = -26f
        private const val COMP_MAKEUP_DB = 6f
        private fun dbToLin(db: Float): Float = 10f.pow(db / 20f)
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
