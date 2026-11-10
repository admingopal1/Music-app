package com.app.musicplayer.ringtone

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A suggested ringtone section. */
data class Hook(val startMs: Long, val endMs: Long, val confidence: Float, val reason: String)

/**
 * On-device "AI" hook finder: picks the part of a song that works best as a ringtone — the chorus/hook.
 * Scores every window on loudness, how often its sound repeats elsewhere in the song (choruses repeat),
 * and whether it starts where the energy rises; then snaps the start to a quiet frame so it doesn't cut in mid-note.
 */
object HookFinder {

    /**
     * @param rms per-frame RMS level (0..1)
     * @param bands per-frame band energies, `bands[frame][band]`
     */
    fun find(rms: FloatArray, bands: Array<FloatArray>, frameMs: Int, clipMs: Long): Hook {
        val n = rms.size
        val w = (clipMs / frameMs).toInt().coerceAtLeast(1)
        if (n <= w) return Hook(0, n.toLong() * frameMs, 0f, "Whole song")

        val per = (1000 / frameMs).coerceAtLeast(1)
        val nb = n / per
        val wb = (w / per).coerceAtLeast(1)
        if (nb <= wb + 1) return Hook(0, w.toLong() * frameMs, 0f, "Start of song")

        val dims = bands.firstOrNull()?.size ?: 0
        val db = FloatArray(nb)
        val feat = Array(nb) { FloatArray(dims) }
        for (b in 0 until nb) {
            var e = 0.0
            val acc = DoubleArray(dims)
            for (f in b * per until (b + 1) * per) {
                e += rms[f].toDouble() * rms[f]
                for (d in 0 until dims) acc[d] += bands[f][d].toDouble()
            }
            db[b] = toDb(sqrt(e / per).toFloat())
            val v = FloatArray(dims) { ln(acc[it] / per + 1e-9).toFloat() }
            val mean = v.average().toFloat()
            var norm = 0f
            for (d in 0 until dims) { v[d] -= mean; norm += v[d] * v[d] }
            norm = sqrt(norm).coerceAtLeast(1e-6f)
            for (d in 0 until dims) feat[b][d] = v[d] / norm
        }

        // Loudness relative to the song itself (quiet parts ~0, loudest part 1).
        val lo = db.sorted()[nb / 5]
        val hi = db.max()
        val energy = FloatArray(nb) { if (hi - lo < 1e-3f) 0.5f else ((db[it] - lo) / (hi - lo)).coerceIn(0f, 1f) }

        // Repetition: best match of the next few seconds' timbre somewhere else (non-overlapping) in the song.
        val seq = 4
        val rep = FloatArray(nb)
        for (b in 0 until nb - seq) {
            var best = 0f
            for (c in 0 until nb - seq) {
                if (kotlin.math.abs(c - b) < max(seq, wb / 2)) continue
                var s = 0f
                for (k in 0 until seq) s += dot(feat[b + k], feat[c + k]) * min(energy[b + k], energy[c + k]).coerceAtLeast(0.2f)
                if (s > best) best = s
            }
            rep[b] = best / seq
        }
        val rMin = rep.min(); val rMax = rep.max()
        for (b in rep.indices) rep[b] = if (rMax - rMin < 1e-6f) 0f else (rep[b] - rMin) / (rMax - rMin)

        val local = FloatArray(nb) { 0.55f * energy[it] + 0.45f * rep[it] }
        val scores = FloatArray(nb - wb + 1)
        var bestS = 0
        for (s in scores.indices) {
            var sum = 0f
            for (b in s until s + wb) sum += local[b]
            var score = sum / wb
            if (s < max(8, nb / 12)) score -= 0.25f                                   // intro
            if (s + wb > nb * 0.97f) score -= 0.15f                                   // fade-out ending
            if (s >= 3) {
                val rise = (energy[s] + energy[min(s + 1, nb - 1)] + energy[min(s + 2, nb - 1)]) / 3f -
                    (energy[s - 1] + energy[s - 2] + energy[s - 3]) / 3f
                score += 0.15f * rise.coerceAtLeast(0f)                               // the hook "drops in"
            }
            scores[s] = score
            if (score > scores[bestS]) bestS = s
        }

        // Snap start to the quietest frame just before the chosen block (beat gap / phrase boundary).
        val from = max(0, bestS * per - (1500 / frameMs))
        val to = min(n - w, bestS * per + (500 / frameMs))
        var start = min(bestS * per, n - w)
        if (from <= to) {
            var m = Float.MAX_VALUE
            for (f in from..to) if (rms[f] < m) { m = rms[f]; start = f }
        }

        val sorted = scores.sorted()
        val spread = sorted.last() - sorted.first()
        val confidence = if (spread < 1e-6f) 0f else ((scores[bestS] - sorted[sorted.size / 2]) / spread).coerceIn(0f, 1f)
        val avgRep = (bestS until bestS + wb).map { rep[it] }.average()
        val avgEnergy = (bestS until bestS + wb).map { energy[it] }.average()
        val reason = when {
            avgRep > 0.6 && avgEnergy > 0.5 -> "Chorus / hook detected"
            avgRep > 0.6 -> "Repeating melody detected"
            avgEnergy > 0.6 -> "Most energetic part"
            else -> "Best sounding part"
        }
        return Hook(start.toLong() * frameMs, (start + w).toLong() * frameMs, confidence, reason)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun toDb(x: Float) = if (x <= 1e-6f) -120f else 20f * log10(x)
}
