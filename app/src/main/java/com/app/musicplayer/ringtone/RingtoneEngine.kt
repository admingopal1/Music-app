package com.app.musicplayer.ringtone

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.app.musicplayer.audio.dsp.Biquad
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Waveform + features of a whole song, used for display and hook detection. */
class SongAnalysis(val frameMs: Int, val rms: FloatArray, val bands: Array<FloatArray>, val durationMs: Long)

enum class ToneType(val label: String, val ringtoneType: Int, val dir: String) {
    RINGTONE("Ringtone", RingtoneManager.TYPE_RINGTONE, Environment.DIRECTORY_RINGTONES),
    NOTIFICATION("Notification", RingtoneManager.TYPE_NOTIFICATION, Environment.DIRECTORY_NOTIFICATIONS),
    ALARM("Alarm", RingtoneManager.TYPE_ALARM, Environment.DIRECTORY_ALARMS),
}

object RingtoneEngine {
    const val FRAME_MS = 100
    private val BAND_HZ = doubleArrayOf(80.0, 250.0, 700.0, 2000.0, 5000.0, 10000.0)

    /** Decodes the song once and returns per-100 ms loudness and band energies. Stops early once [keepGoing] is false. */
    fun analyse(ctx: Context, uri: Uri, keepGoing: () -> Boolean = { true }, onProgress: (Float) -> Unit = {}): SongAnalysis {
        val rms = ArrayList<Float>()
        val bands = ArrayList<FloatArray>()
        var filters: Array<Biquad>? = null
        var acc = 0.0
        val bandAcc = DoubleArray(BAND_HZ.size)
        var count = 0
        var frameLen = 4410
        var durationUs = 0L
        PcmDecoder(ctx, uri).use { dec ->
            durationUs = dec.durationUs
            dec.decode(Long.MAX_VALUE) { pcm, frames, ch, rate, ptsUs ->
                val f = filters ?: Array(BAND_HZ.size) { Biquad().bandPass(rate, BAND_HZ[it], 1.2) }.also {
                    filters = it
                    frameLen = rate * FRAME_MS / 1000
                }
                for (i in 0 until frames) {
                    var m = 0f
                    for (c in 0 until ch) m += pcm[i * ch + c]
                    m /= ch
                    acc += m * m
                    for (b in f.indices) { val y = f[b].process(m); bandAcc[b] += (y * y).toDouble() }
                    if (++count == frameLen) {
                        rms += sqrt(acc / count).toFloat()
                        bands += FloatArray(BAND_HZ.size) { (bandAcc[it] / count).toFloat() }
                        acc = 0.0; bandAcc.fill(0.0); count = 0
                    }
                }
                if (durationUs > 0) onProgress((ptsUs.toFloat() / durationUs).coerceIn(0f, 1f))
                keepGoing()
            }
        }
        val dur = if (durationUs > 0) durationUs / 1000 else rms.size.toLong() * FRAME_MS
        return SongAnalysis(FRAME_MS, rms.toFloatArray(), bands.toTypedArray(), dur)
    }

    /**
     * Cuts [startMs, endMs) with fades, encodes it to AAC (.m4a) and adds it to the system ringtones /
     * notifications / alarms. Returns the new MediaStore uri.
     */
    fun export(
        ctx: Context, uri: Uri, startMs: Long, endMs: Long, fadeInMs: Int, fadeOutMs: Int,
        title: String, artist: String, type: ToneType,
    ): Uri {
        val clip = cut(ctx, uri, startMs, endMs)
        applyFades(clip.pcm, clip.channels, clip.rate, fadeInMs, fadeOutMs)
        val tmp = File(ctx.cacheDir, "ringtone_${System.currentTimeMillis()}.m4a")
        try {
            encodeAac(clip.pcm, clip.channels, clip.rate, tmp)
            return insert(ctx, tmp, title, artist, type)
        } finally {
            tmp.delete()
        }
    }

    fun canSetDefault(ctx: Context) = Settings.System.canWrite(ctx)

    fun setDefault(ctx: Context, tone: Uri, type: ToneType) =
        RingtoneManager.setActualDefaultRingtoneUri(ctx, type.ringtoneType, tone)

    // ------------------------------------------------------------------ internals

    class Clip(val pcm: ShortArray, val channels: Int, val rate: Int)

    private fun cut(ctx: Context, uri: Uri, startMs: Long, endMs: Long): Clip {
        val startUs = startMs * 1000
        val endUs = endMs * 1000
        var out = ShortArray(0)
        var n = 0
        var channels = 2
        var rate = 44100
        PcmDecoder(ctx, uri).use { dec ->
            dec.seekUs(startUs)
            dec.decode(endUs + 200_000) { pcm, frames, ch, r, ptsUs ->
                val oc = minOf(ch, 2)
                if (out.isEmpty()) {
                    channels = oc; rate = r
                    out = ShortArray(((endMs - startMs) * r / 1000 + r).toInt() * oc)
                }
                for (i in 0 until frames) {
                    val t = ptsUs + i * 1_000_000L / r
                    if (t < startUs) continue
                    if (t >= endUs) return@decode false
                    if (n + oc > out.size) return@decode false
                    for (c in 0 until oc) {
                        out[n++] = (pcm[i * ch + c] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    }
                }
                true
            }
        }
        require(n > 0) { "Could not decode this song" }
        return Clip(out.copyOf(n), channels, rate)
    }

    internal fun applyFades(pcm: ShortArray, ch: Int, rate: Int, fadeInMs: Int, fadeOutMs: Int) {
        val frames = pcm.size / ch
        val fi = (fadeInMs.toLong() * rate / 1000).toInt().coerceAtMost(frames / 2)
        val fo = (fadeOutMs.toLong() * rate / 1000).toInt().coerceAtMost(frames / 2)
        for (i in 0 until fi) {
            val g = i.toFloat() / fi
            for (c in 0 until ch) pcm[i * ch + c] = (pcm[i * ch + c] * g * g).toInt().toShort()
        }
        for (i in 0 until fo) {
            val g = i.toFloat() / fo
            val f = frames - 1 - i
            for (c in 0 until ch) pcm[f * ch + c] = (pcm[f * ch + c] * g * g).toInt().toShort()
        }
    }

    private fun encodeAac(pcm: ShortArray, ch: Int, rate: Int, file: File) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val info = MediaCodec.BufferInfo()
            var pos = 0
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val ii = enc.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = enc.getInputBuffer(ii)!!.order(ByteOrder.nativeOrder())
                        buf.clear()
                        val pts = (pos / ch).toLong() * 1_000_000L / rate
                        val count = minOf(buf.remaining() / 2, pcm.size - pos) / ch * ch
                        if (count <= 0) {
                            enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            buf.asShortBuffer().put(pcm, pos, count)
                            pos += count
                            enc.queueInputBuffer(ii, 0, count * 2, pts, 0)
                        }
                    }
                }
                val oi = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(enc.outputFormat)
                        muxer.start(); muxing = true
                    }
                    oi >= 0 -> {
                        val ob = enc.getOutputBuffer(oi)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) {
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            muxer.writeSampleData(track, ob, info)
                        }
                        enc.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
        } finally {
            runCatching { enc.stop() }
            enc.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun insert(ctx: Context, file: File, title: String, artist: String, type: ToneType): Uri {
        val resolver = ctx.contentResolver
        val name = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.IS_RINGTONE, type == ToneType.RINGTONE)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, type == ToneType.NOTIFICATION)
            put(MediaStore.Audio.Media.IS_ALARM, type == ToneType.ALARM)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.m4a")
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, type.dir)
            values.put(MediaStore.Audio.Media.IS_PENDING, 1)
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: error("Could not create the ringtone file")
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(type.dir).apply { mkdirs() }
        var dest = File(dir, "$name.m4a")
        var i = 1
        while (dest.exists()) dest = File(dir, "$name (${i++}).m4a")
        file.copyTo(dest)
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, dest.name)
        @Suppress("DEPRECATION")
        values.put(MediaStore.Audio.Media.DATA, dest.absolutePath)
        return resolver.insert(MediaStore.Audio.Media.getContentUriForPath(dest.absolutePath)!!, values)
            ?: error("Could not register the ringtone")
    }
}

/** Minimal MediaExtractor + MediaCodec PCM decoder (float samples, interleaved). */
private class PcmDecoder(ctx: Context, uri: Uri) : AutoCloseable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private var channels: Int
    private var rate: Int
    val durationUs: Long

    init {
        extractor.setDataSource(ctx, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); error("No audio track") }
        val fmt = extractor.getTrackFormat(track)
        extractor.selectTrack(track)
        channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
    }

    fun seekUs(us: Long) = extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

    /** Feeds decoded chunks to [onChunk] (samples, frames, channels, rate, ptsUs) until it returns false or [untilUs]. */
    fun decode(untilUs: Long, onChunk: (FloatArray, Int, Int, Int, Long) -> Boolean) {
        val info = MediaCodec.BufferInfo()
        var isFloat = false
        var tmp = FloatArray(0)
        var inDone = false
        while (true) {
            if (!inDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val n = extractor.readSampleData(codec.getInputBuffer(ii)!!, 0)
                    if (n < 0 || extractor.sampleTime > untilUs) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else if (oi >= 0) {
                val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                ob.position(info.offset); ob.limit(info.offset + info.size)
                val samples = if (isFloat) info.size / 4 else info.size / 2
                if (tmp.size < samples) tmp = FloatArray(samples)
                if (isFloat) ob.asFloatBuffer().get(tmp, 0, samples)
                else { val sb = ob.asShortBuffer(); for (i in 0 until samples) tmp[i] = sb.get(i) / 32768f }
                codec.releaseOutputBuffer(oi, false)
                val more = samples == 0 || onChunk(tmp, samples / channels, channels, rate, info.presentationTimeUs)
                if (!more || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }
}
