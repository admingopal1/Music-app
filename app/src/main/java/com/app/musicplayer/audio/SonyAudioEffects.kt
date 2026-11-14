package com.app.musicplayer.audio

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.ln
import kotlin.math.roundToInt

data class FxState(
    val available: Boolean = false,
    val enabled: Boolean = true,
    val centerFreqsHz: List<Int> = emptyList(),
    val bandLevelsDb: List<Float> = emptyList(),
    val minDb: Float = -12f,
    val maxDb: Float = 12f,
    val bassStrength: Float = 0f,        // 0..1  -> 0..1000
    val virtualizerStrength: Float = 0f, // 0..1  -> 0..1000
    val loudnessGain: Float = 0f,        // 0..1  -> 0..1000 mB (0..+10 dB)
    val reverbPreset: Int = 0,           // PresetReverb.PRESET_*
    val activePreset: String = "Flat",
    val customProfiles: List<String> = emptyList(),
    val bassSupported: Boolean = false,
    val virtSupported: Boolean = false,
    val loudnessSupported: Boolean = false,
    val reverbSupported: Boolean = false,
)

/**
 * App-wide wrapper around Android's hardware audio effects. The MusicService attaches it to ExoPlayer's
 * audioSessionId; the UI observes [state] and calls the setters.
 */
class SonyAudioEffects private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("audio_fx", Context.MODE_PRIVATE)

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var reverb: PresetReverb? = null

    private var sessionId = 0
    private var devMinMb = -1500
    private var devMaxMb = 1500
    private val savedBands: List<Float> =
        prefs.getString(K_BANDS, "")?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: emptyList()

    private val _state = MutableStateFlow(
        FxState(
            enabled = prefs.getBoolean(K_ENABLED, true),
            bassStrength = prefs.getFloat(K_BASS, 0f),
            virtualizerStrength = prefs.getFloat(K_VIRT, 0f),
            loudnessGain = prefs.getFloat(K_LOUD, 0f),
            reverbPreset = prefs.getInt(K_REVERB, 0),
            activePreset = prefs.getString(K_PRESET, "Flat") ?: "Flat",
            customProfiles = profileNames(),
        )
    )
    val state: StateFlow<FxState> = _state.asStateFlow()

    // ---------------------------------------------------------------- lifecycle

    @Synchronized
    fun attach(newSession: Int) {
        if (newSession <= 0 || newSession == sessionId) return
        releaseEffects()
        sessionId = newSession

        equalizer = create("Equalizer") { Equalizer(0, newSession) }
        bassBoost = create("BassBoost") { BassBoost(0, newSession) }
        virtualizer = create("Virtualizer") { Virtualizer(0, newSession) }
        loudness = create("LoudnessEnhancer") { LoudnessEnhancer(newSession) }
        reverb = create("PresetReverb") { PresetReverb(0, newSession) }

        val eq = equalizer
        var freqs = emptyList<Int>()
        var levels = emptyList<Float>()
        var minDb = -12f
        var maxDb = 12f
        if (eq != null) {
            val range = eq.bandLevelRange // millibels
            devMinMb = range[0].toInt()
            devMaxMb = range[1].toInt()
            minDb = maxOf(-12f, devMinMb / 100f)
            maxDb = minOf(12f, devMaxMb / 100f)
            val n = eq.numberOfBands.toInt()
            freqs = List(n) { eq.getCenterFreq(it.toShort()) / 1000 } // milliHz -> Hz
            val cur = _state.value.bandLevelsDb
            levels = when {
                cur.size == n -> cur
                savedBands.size == n -> savedBands
                else -> List(n) { 0f }
            }
        }
        _state.update {
            it.copy(
                available = eq != null,
                centerFreqsHz = freqs,
                bandLevelsDb = levels.map { l -> l.coerceIn(minDb, maxDb) },
                minDb = minDb,
                maxDb = maxDb,
                bassSupported = bassBoost?.strengthSupported == true,
                virtSupported = virtualizer?.strengthSupported == true,
                loudnessSupported = loudness != null,
                reverbSupported = reverb != null,
            )
        }
        applyAll()
    }

    @Synchronized
    fun release() {
        releaseEffects()
        sessionId = 0
        _state.update { it.copy(available = false) }
    }

    private fun releaseEffects() {
        equalizer?.release(); equalizer = null
        bassBoost?.release(); bassBoost = null
        virtualizer?.release(); virtualizer = null
        loudness?.release(); loudness = null
        reverb?.release(); reverb = null
    }

    // ---------------------------------------------------------------- public API

    fun setEnabled(on: Boolean) {
        _state.update { it.copy(enabled = on) }
        applyAll(); persist()
    }

    fun setBandLevel(band: Int, db: Float) {
        val s = _state.value
        if (band !in s.bandLevelsDb.indices) return
        val v = db.coerceIn(s.minDb, s.maxDb)
        val list = s.bandLevelsDb.toMutableList().also { it[band] = v }
        _state.update { it.copy(bandLevelsDb = list, activePreset = CUSTOM) }
        val eq = equalizer
        if (eq != null) guard("setBandLevel") { eq.setBandLevel(band.toShort(), toMb(v)) }
        persist()
    }

    /** Built-in preset name or a saved custom profile name. */
    fun applyPreset(name: String) {
        val s = _state.value
        val builtIn = PRESETS[name]
        val levels: List<Float> = if (builtIn != null) {
            s.centerFreqsHz.map { hz -> curveAt(builtIn, hz).coerceIn(s.minDb, s.maxDb) }
        } else {
            val saved = prefs.getString(PROFILE_PREFIX + name, null)
                ?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: return
            List(s.bandLevelsDb.size) { i -> saved.getOrElse(i) { 0f }.coerceIn(s.minDb, s.maxDb) }
        }
        _state.update { it.copy(bandLevelsDb = levels, activePreset = name) }
        applyAll(); persist()
    }

    fun saveProfile(rawName: String) {
        val name = rawName.trim().replace("|", " ")
        val s = _state.value
        if (name.isEmpty() || s.bandLevelsDb.isEmpty() || name in PRESETS.keys) return
        val names = (profileNames() + name).distinct()
        prefs.edit()
            .putString(PROFILE_PREFIX + name, s.bandLevelsDb.joinToString(","))
            .putString(K_PROFILES, names.joinToString("|"))
            .apply()
        _state.update { it.copy(customProfiles = names, activePreset = name) }
        persist()
    }

    fun deleteProfile(name: String) {
        val names = profileNames() - name
        prefs.edit().remove(PROFILE_PREFIX + name).putString(K_PROFILES, names.joinToString("|")).apply()
        _state.update { it.copy(customProfiles = names) }
    }

    fun setBassStrength(v: Float) {
        _state.update { it.copy(bassStrength = v.coerceIn(0f, 1f)) }
        applyAll(); persist()
    }

    fun setClearBass(v: Float) = setBassStrength(v)

    fun setVirtualizerStrength(v: Float) {
        _state.update { it.copy(virtualizerStrength = v.coerceIn(0f, 1f)) }
        applyAll(); persist()
    }

    fun setLoudnessGain(v: Float) {
        _state.update { it.copy(loudnessGain = v.coerceIn(0f, 1f)) }
        applyAll(); persist()
    }

    fun setReverbPreset(index: Int) {
        _state.update { it.copy(reverbPreset = index.coerceIn(0, 6)) }
        applyAll(); persist()
    }

    // ---------------------------------------------------------------- internals

    @Synchronized
    private fun applyAll() {
        val s = _state.value
        equalizer?.let { e ->
            guard("eq") {
                val n = e.numberOfBands.toInt()
                s.bandLevelsDb.forEachIndexed { i, db -> if (i < n) e.setBandLevel(i.toShort(), toMb(db)) }
                e.enabled = s.enabled
            }
        }
        bassBoost?.let { b ->
            guard("bass") {
                if (b.strengthSupported) b.setStrength((s.bassStrength * 1000f).roundToInt().toShort())
                b.enabled = s.enabled && s.bassStrength > 0f
            }
        }
        virtualizer?.let { v ->
            guard("virtualizer") {
                if (v.strengthSupported) v.setStrength((s.virtualizerStrength * 1000f).roundToInt().toShort())
                v.enabled = s.enabled && s.virtualizerStrength > 0f
            }
        }
        loudness?.let { l ->
            guard("loudness") {
                l.setTargetGain((s.loudnessGain * 1000f).roundToInt()) // millibels
                l.enabled = s.enabled && s.loudnessGain > 0f
            }
        }
        reverb?.let { r ->
            guard("reverb") {
                r.preset = s.reverbPreset.toShort()
                r.enabled = s.enabled && s.reverbPreset != 0
            }
        }
    }

    private fun persist() {
        val s = _state.value
        val e = prefs.edit()
            .putBoolean(K_ENABLED, s.enabled)
            .putFloat(K_BASS, s.bassStrength)
            .putFloat(K_VIRT, s.virtualizerStrength)
            .putFloat(K_LOUD, s.loudnessGain)
            .putInt(K_REVERB, s.reverbPreset)
            .putString(K_PRESET, s.activePreset)
        if (s.bandLevelsDb.isNotEmpty()) e.putString(K_BANDS, s.bandLevelsDb.joinToString(","))
        e.apply()
    }

    private fun profileNames(): List<String> =
        (prefs.getString(K_PROFILES, "") ?: "").split("|").filter { it.isNotBlank() }

    private fun toMb(db: Float): Short =
        (db * 100f).roundToInt().coerceIn(devMinMb, devMaxMb).toShort()

    private inline fun <T> create(name: String, block: () -> T): T? =
        try { block() } catch (t: Throwable) { Log.w(TAG, "$name unavailable", t); null }

    private inline fun guard(name: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "$name failed", t) }
    }

    companion object {
        private const val TAG = "SonyAudioEffects"
        const val CUSTOM = "Custom"
        private const val K_ENABLED = "enabled"
        private const val K_BANDS = "bands"
        private const val K_BASS = "bass"
        private const val K_VIRT = "virt"
        private const val K_LOUD = "loud"
        private const val K_REVERB = "reverb"
        private const val K_PRESET = "preset"
        private const val K_PROFILES = "profiles"
        private const val PROFILE_PREFIX = "profile_"

        /** Reference frequencies (Hz) the preset curves are defined on; interpolated to device bands. */
        private val REF_FREQS = listOf(60, 230, 910, 3600, 14000)

        /** Gains in dB at [REF_FREQS]. */
        val PRESETS: Map<String, List<Float>> = linkedMapOf(
            "Flat" to listOf(0f, 0f, 0f, 0f, 0f),
            "Rock" to listOf(5f, 3f, -1f, 3f, 5f),
            "Pop" to listOf(-1f, 2f, 4f, 2f, -1f),
            "Jazz" to listOf(3f, 2f, -2f, 2f, 4f),
            "Bass Booster" to listOf(9f, 6f, 1f, 0f, 0f),
            "Heavy Metal" to listOf(6f, 3f, -2f, 4f, 6f),
            "Hip-Hop" to listOf(7f, 5f, 0f, 2f, 3f),
            "Classical" to listOf(4f, 3f, -2f, 3f, 4f),
            "Acoustic" to listOf(3f, 2f, 1f, 3f, 2f),
            "Vocal" to listOf(-2f, 1f, 5f, 4f, 1f),
            "R&B" to listOf(5f, 3f, 1f, 3f, 4f),
            "Electronic" to listOf(5f, 2f, 0f, 3f, 5f),
            "Dance" to listOf(6f, 3f, 0f, 4f, 5f),
            "Podcast" to listOf(-3f, 0f, 4f, 3f, -2f),
            "Treble Boost" to listOf(-2f, -1f, 1f, 5f, 7f),
            "Deep Bass" to listOf(10f, 6f, 1f, -1f, -2f),
        )

        private fun curveAt(points: List<Float>, hz: Int): Float {
            val f = hz.coerceIn(REF_FREQS.first(), REF_FREQS.last()).toFloat()
            for (i in 0 until REF_FREQS.size - 1) {
                if (f <= REF_FREQS[i + 1]) {
                    val lo = ln(REF_FREQS[i].toFloat())
                    val hi = ln(REF_FREQS[i + 1].toFloat())
                    val t = (ln(f) - lo) / (hi - lo)
                    return points[i] + (points[i + 1] - points[i]) * t
                }
            }
            return points.last()
        }

        @Volatile private var instance: SonyAudioEffects? = null
        fun get(context: Context): SonyAudioEffects =
            instance ?: synchronized(this) {
                instance ?: SonyAudioEffects(context.applicationContext).also { instance = it }
            }
    }
}
