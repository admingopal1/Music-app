package com.app.musicplayer.audio

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.C
import com.app.musicplayer.CrashLog
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.extractor.metadata.flac.VorbisComment
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Gain / peak values read from a file's ReplayGain tags (dB and linear peak). */
data class RgInfo(val trackGain: Float?, val trackPeak: Float?, val albumGain: Float?, val albumPeak: Float?)

object ReplayGain {
    const val MODE_OFF = 0
    const val MODE_TRACK = 1
    const val MODE_ALBUM = 2

    data class Settings(val mode: Int, val preampDb: Float, val fallbackDb: Float, val limit: Boolean)

    fun readSettings(p: SharedPreferences): Settings {
        val mode = p.getInt("rg_mode", MODE_OFF)
        val preamp = p.getFloat("rg_preamp", 0f)
        val fallback = p.getFloat("rg_fallback", 0f)
        return Settings(
            mode = mode.takeIf { it in MODE_OFF..MODE_ALBUM } ?: MODE_OFF,
            preampDb = if (preamp.isFinite()) preamp.coerceIn(-12f, 12f) else 0f,
            fallbackDb = if (fallback.isFinite()) fallback.coerceIn(-24f, 12f) else 0f,
            limit = p.getBoolean("rg_limit", true),
        )
    }

    fun dbToLinear(db: Float): Float {
        val safeDb = if (db.isFinite()) db.coerceIn(-24f, 24f) else 0f
        return 10.0.pow(safeDb / 20.0).toFloat()
    }

    /** The gain in dB that will be applied for this track, or null when ReplayGain is off. */
    fun appliedDb(info: RgInfo?, s: Settings): Float? {
        if (s.mode !in MODE_TRACK..MODE_ALBUM) return null
        val gain: Float? = if (info == null) null
        else if (s.mode == MODE_ALBUM) info.albumGain?.takeIf { it.isFinite() } ?: info.trackGain?.takeIf { it.isFinite() }
        else info.trackGain?.takeIf { it.isFinite() } ?: info.albumGain?.takeIf { it.isFinite() }
        val peak: Float? = if (info == null) null
        else if (s.mode == MODE_ALBUM) info.albumPeak?.takeIf { it.isFinite() && it > 0f } ?: info.trackPeak?.takeIf { it.isFinite() && it > 0f }
        else info.trackPeak?.takeIf { it.isFinite() && it > 0f } ?: info.albumPeak?.takeIf { it.isFinite() && it > 0f }

        val preamp = if (s.preampDb.isFinite()) s.preampDb.coerceIn(-12f, 12f) else 0f
        val fallback = if (s.fallbackDb.isFinite()) s.fallbackDb.coerceIn(-24f, 12f) else 0f
        var db = if (gain == null) fallback else gain.coerceIn(-24f, 24f) + preamp
        if (s.limit && peak != null) {
            val maxDb = -20f * log10(peak) // gain at which the loudest sample just reaches full scale
            if (maxDb.isFinite()) db = min(db, maxDb)
        }
        return (if (db.isFinite()) db else 0f).coerceIn(-24f, 24f)
    }

    private val NUM = Regex("[-+]?\\d+(?:[.,]\\d+)?")
    private fun num(v: String?): Float? = v?.let { NUM.find(it)?.value?.replace(',', '.')?.toFloatOrNull()?.takeIf { n -> n.isFinite() } }

    /** [tags] keys must be lower-case. Handles ReplayGain 2 tags and Opus R128 tags. */
    fun fromTags(tags: Map<String, String>): RgInfo? {
        val normalized = tags.mapKeys { it.key.lowercase() }
        var tg = num(normalized["replaygain_track_gain"])
        var ag = num(normalized["replaygain_album_gain"])
        val tp = num(normalized["replaygain_track_peak"])
        val ap = num(normalized["replaygain_album_peak"])
        // Opus R128 gains are Q7.8 dB relative to -23 LUFS; ReplayGain 2 uses -18 LUFS.
        if (tg == null) tg = num(normalized["r128_track_gain"])?.let { it / 256f + 5f }
        if (ag == null) ag = num(normalized["r128_album_gain"])?.let { it / 256f + 5f }
        if (tg == null && ag == null) return null
        return RgInfo(tg, tp, ag, ap)
    }
}

/** Remembers tag values per library song id so the gain is known before a track starts. */
class ReplayGainStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("replaygain", Context.MODE_PRIVATE)

    fun get(id: String): RgInfo? {
        val p = prefs.getString(id, null)?.split(';') ?: return null
        if (p.size < 4) return null
        return RgInfo(
            p[0].takeIf { it.isNotBlank() }?.toFloatOrNull(),
            p[1].takeIf { it.isNotBlank() }?.toFloatOrNull(),
            p[2].takeIf { it.isNotBlank() }?.toFloatOrNull(),
            p[3].takeIf { it.isNotBlank() }?.toFloatOrNull(),
        )
    }

    fun put(id: String, i: RgInfo) {
        prefs.edit().putString(id, "${i.trackGain ?: ""};${i.trackPeak ?: ""};${i.albumGain ?: ""};${i.albumPeak ?: ""}").apply()
    }
}

/** Scales 16-bit / float PCM by a smoothly changing gain (30 ms ramp, so track changes don't click). */
class ReplayGainProcessor : BaseAudioProcessor() {
    @Volatile private var target = 1f
    @Volatile private var rg = 1f
    @Volatile private var fade = 1f
    private var current = 1f
    private var step = 0.001f
    private var channels = 2
    private var encoding = C.ENCODING_INVALID

    fun setGainDb(db: Float) {
        val safeDb = if (db.isFinite()) db.coerceIn(-24f, 24f) else 0f
        rg = ReplayGain.dbToLinear(safeDb)
        target = rg * fade
    }
    fun setFade(f: Float) {
        fade = if (f.isFinite()) f.coerceIn(0f, 1f) else 1f
        target = rg * fade
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET   // leave other formats untouched
        }
        encoding = enc
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        step = 1f / max(1f, inputAudioFormat.sampleRate * 0.03f)
        current = target
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
            CrashLog.record("ReplayGainProcessor", e)
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
        val t = target
        if (current == t && t == 1f) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        if (encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= 2 * channels) {
                if (current != t) current = if (current < t) min(t, current + step) else max(t, current - step)
                for (c in 0 until channels) {
                    val s = (inputBuffer.getShort() * current).toInt().coerceIn(-32768, 32767)
                    out.putShort(s.toShort())
                }
            }
        } else {
            while (inputBuffer.remaining() >= 4 * channels) {
                if (current != t) current = if (current < t) min(t, current + step) else max(t, current - step)
                for (c in 0 until channels) {
                    // Float decoders can emit NaN / infinity; never propagate them to AudioTrack.
                    val sample = sanitizePcmSample(inputBuffer.getFloat())
                    out.putFloat(sanitizePcmSample(sample * current))
                }
            }
        }
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())   // any partial frame
        out.flip()
    }

    override fun onFlush() { current = target }
    override fun onReset() { encoding = C.ENCODING_INVALID }
}

/** Reads ReplayGain tags of the playing track (ExoPlayer exposes them) and drives [ReplayGainProcessor]. */
class ReplayGainController(
    ctx: Context,
    private val player: Player,
    private val processor: ReplayGainProcessor,
) : Player.Listener {
    private val store = ReplayGainStore(ctx)
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith("rg_")) apply()
    }

    init {
        player.addListener(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        apply()
    }

    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { apply() }

    override fun onTracksChanged(tracks: Tracks) {
        val tags = HashMap<String, String>()
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO || !g.isSelected) continue
            for (i in 0 until g.length) {
                val md = g.getTrackFormat(i).metadata ?: continue
                for (j in 0 until md.length()) collect(md.get(j), tags)
            }
        }
        val info = ReplayGain.fromTags(tags)
        val id = player.currentMediaItem?.mediaId
        if (info != null && id != null && id.toLongOrNull() != null) store.put(id, info)
        apply()
    }

    private fun collect(e: Metadata.Entry, out: MutableMap<String, String>) {
        when (e) {
            is TextInformationFrame -> {
                val k = e.description
                val v = e.values.firstOrNull()
                if (e.id == "TXXX" && k != null && v != null) out[k.lowercase()] = v
            }
            is InternalFrame -> out[e.description.lowercase()] = e.text
            is VorbisComment -> out[e.key.lowercase()] = e.value
            else -> Unit
        }
    }

    fun apply() {
        val id = player.currentMediaItem?.mediaId
        val info = id?.let { store.get(it) }
        val db = ReplayGain.appliedDb(info, ReplayGain.readSettings(prefs))
        processor.setGainDb(db ?: 0f)
    }

    fun release() {
        player.removeListener(this)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }
}

/**
 * "Fade between songs": fades the end of a song out and the start of the next one in
 * (a fade-through, not an overlapping crossfade). Uses the same processor as ReplayGain.
 */
class FadeController(
    ctx: Context,
    private val player: Player,
    private val processor: ReplayGainProcessor,
) : Player.Listener {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var fadeInActive = false
    private var lastFade = 1f

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 100)
        }
    }

    init {
        player.addListener(this)
        handler.post(tick)
    }

    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        fadeInActive = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        update()
    }

    private fun update() {
        val storedSecs = prefs.getFloat("fade_secs", 0f)
        val secs = if (storedSecs.isFinite()) storedSecs.coerceIn(0f, 12f) else 0f
        var f = 1f
        val d = player.duration
        if (secs > 0f && d != C.TIME_UNSET && d > 0L) {
            val p = player.currentPosition
            val half = min(secs * 500f, d / 4f).coerceAtLeast(1f) // milliseconds for each half; avoid zero on very short media
            if (fadeInActive) {
                if (p >= half) fadeInActive = false else f = min(f, (p / half).coerceIn(0f, 1f))
            }
            if (player.hasNextMediaItem() && player.repeatMode != Player.REPEAT_MODE_ONE) {
                f = min(f, ((d - p) / half).coerceIn(0f, 1f))
            }
        }
        if (f != lastFade) {
            lastFade = f
            processor.setFade(f)
        }
    }

    fun release() {
        handler.removeCallbacks(tick)
        player.removeListener(this)
        processor.setFade(1f)
    }
}
