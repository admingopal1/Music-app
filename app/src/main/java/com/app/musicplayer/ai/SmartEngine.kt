package com.app.musicplayer.ai

import com.app.musicplayer.data.Song
import com.app.musicplayer.data.SongStats
import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * On-device "AI" for the library: typo-tolerant search, a taste model built from play counts / favourites /
 * ratings / recency, personalised mixes and similar-song suggestions for the AI DJ. Nothing leaves the phone.
 */
object SmartEngine {
    private const val DAY = 24L * 60 * 60 * 1000

    // ----------------------------------------------------------------- search

    /** Songs matching [query], best first. Tolerates typos, missing words, accents and word order. */
    fun search(songs: List<Song>, query: String): List<Song> {
        val q = tokens(query)
        if (q.isEmpty()) return songs
        return songs.asSequence()
            .map { it to searchScore(it, q) }
            .filter { it.second > 0.55f }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()
    }

    private fun searchScore(song: Song, q: List<String>): Float {
        val title = tokens(song.title)
        val fields = title + tokens(song.artist) + tokens(song.album) + tokens(song.fileName.substringBeforeLast('.'))
        if (fields.isEmpty()) return 0f
        var total = 0f
        for (t in q) {
            var best = 0f
            for (f in fields) {
                val s = when {
                    f == t -> 1f
                    f.startsWith(t) -> 0.95f
                    t.length >= 3 && f.contains(t) -> 0.85f
                    else -> similarity(t, f)
                }
                if (s > best) best = s
            }
            total += best
        }
        val avg = total / q.size
        val titleBonus = if (q.all { t -> title.any { it.startsWith(t) } }) 0.1f else 0f
        return avg + titleBonus
    }

    /** 1 - normalised Levenshtein distance, scaled so short words need near-exact matches. */
    internal fun similarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val cmp = if (b.length > a.length + 2) b.substring(0, a.length + 1) else b  // prefix typos: "arjit" ~ "arijit"
        val d = levenshtein(a, cmp)
        val allowed = when {
            a.length <= 3 -> 0
            a.length <= 5 -> 1
            else -> 2
        }
        if (d > allowed) return 0f
        return 0.8f - 0.1f * d
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private val NON_ALNUM = Regex("[^\\p{L}\\p{Nd}]+")
    private val MARKS = Regex("\\p{Mn}+")

    internal fun tokens(s: String): List<String> =
        MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
            .split(NON_ALNUM).filter { it.isNotEmpty() }

    // ----------------------------------------------------------------- taste model

    /** How much the listener likes each artist (0..1), learned from listening history. */
    fun artistAffinity(songs: List<Song>, stats: Map<Long, SongStats>, now: Long = System.currentTimeMillis()): Map<String, Float> {
        val raw = HashMap<String, Float>()
        for (s in songs) {
            val st = stats[s.id] ?: continue
            raw[s.artist] = (raw[s.artist] ?: 0f) + songAffinity(st, now)
        }
        val top = raw.values.maxOrNull() ?: return emptyMap()
        if (top <= 0f) return emptyMap()
        return raw.mapValues { (it.value / top).coerceIn(0f, 1f) }
    }

    /** Liking of one song from its stats: plays (log), favourite, stars, and how recently it was played. */
    private fun songAffinity(st: SongStats, now: Long): Float {
        val recency = if (st.lastPlayed > 0) exp(-(now - st.lastPlayed).toDouble() / (30.0 * DAY)).toFloat() else 0f
        val rating = if (st.rating > 0) (st.rating - 2.5f) * 0.6f else 0f
        return ln(1f + st.plays) + (if (st.favorite) 2f else 0f) + rating + recency
    }

    /** A personalised mix: favourite artists' songs, loved tracks, and fresh picks, spread across artists. */
    fun forYou(songs: List<Song>, stats: Map<Long, SongStats>, size: Int = 50, now: Long = System.currentTimeMillis()): List<Song> {
        if (songs.isEmpty()) return emptyList()
        val aff = artistAffinity(songs, stats, now)
        val newest = songs.maxOf { it.dateAdded }.coerceAtLeast(1L)
        val scored = songs.map { s ->
            val st = stats[s.id]
            var score = (aff[s.artist] ?: 0f) * 2f
            if (st != null) {
                score += songAffinity(st, now) * 0.6f
                // Played in the last few hours: let it rest.
                if (now - st.lastPlayed < 6 * 60 * 60 * 1000L) score -= 2f
            } else {
                score += 0.4f  // discovery bonus for never-played songs
            }
            // Recently added music gets a gentle lift (dateAdded is in seconds).
            score += 0.5f * max(0f, 1f - (newest - s.dateAdded) / (60f * 60 * 24 * 30))
            score += stableJitter(s.id, now / DAY) * 0.35f   // changes daily, so the mix refreshes
            s to score
        }.sortedByDescending { it.second }.map { it.first }
        return diversify(scored, size)
    }

    /** Songs you played a lot but not for at least three weeks. */
    fun rediscover(songs: List<Song>, stats: Map<Long, SongStats>, size: Int = 50, now: Long = System.currentTimeMillis()): List<Song> =
        songs.filter { s ->
            val st = stats[s.id] ?: return@filter false
            st.lastPlayed > 0 && (st.plays >= 2 || st.favorite || st.rating >= 4) && now - st.lastPlayed > 21 * DAY
        }.sortedByDescending { s -> stats[s.id]?.let { songAffinity(it, now) } ?: 0f }
            .let { diversify(it, size) }

    /** Songs that fit after [seed]: same artist / album / folder, artists you like, avoiding [exclude]. */
    fun similar(
        seed: Song,
        songs: List<Song>,
        stats: Map<Long, SongStats>,
        exclude: Set<Long>,
        count: Int = 5,
        now: Long = System.currentTimeMillis(),
    ): List<Song> {
        val aff = artistAffinity(songs, stats, now)
        val seedTokens = tokens(seed.title).toSet()
        val seedDuration = seed.durationMs.coerceAtLeast(1L)
        val scored = songs.asSequence()
            .filter { it.id != seed.id && it.id !in exclude }
            .map { s ->
                val st = stats[s.id]
                var score = 0f
                if (s.artist.equals(seed.artist, true) && seed.artist.isNotBlank()) score += 3f
                if (s.album.equals(seed.album, true) && seed.album.isNotBlank()) score += 2f
                if (s.folder == seed.folder && seed.folder.isNotBlank()) score += 1f
                score += (aff[s.artist] ?: 0f) * 1.5f
                if (st != null) score += min(2f, songAffinity(st, now) * 0.3f)
                if (tokens(s.title).any { it.length > 3 && it in seedTokens }) score += 0.5f
                val ratio = s.durationMs.toFloat() / seedDuration
                if (ratio in 0.6f..1.6f) score += 0.3f
                if (st != null && now - st.lastPlayed < 2 * 60 * 60 * 1000L) score -= 3f
                score += stableJitter(s.id, now / (60 * 60 * 1000L)) * 0.8f
                s to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()
        return diversify(scored, count, maxPerArtist = 2)
    }

    /** Keeps the order but stops one artist from dominating; skipped songs are appended afterwards. */
    private fun diversify(list: List<Song>, size: Int, maxPerArtist: Int = 3): List<Song> {
        val out = ArrayList<Song>(size)
        val rest = ArrayList<Song>()
        val perArtist = HashMap<String, Int>()
        for (s in list) {
            if (out.size >= size) break
            val n = perArtist[s.artist] ?: 0
            if (n < maxPerArtist) { out += s; perArtist[s.artist] = n + 1 } else rest += s
        }
        for (s in rest) { if (out.size >= size) break; out += s }
        return out
    }

    private fun stableJitter(id: Long, salt: Long): Float {
        var x = id * 0x9E3779B97F4A7C15uL.toLong() + salt * 0xBF58476D1CE4E5B9uL.toLong()
        x = x xor (x ushr 31)
        x *= 0x94D049BB133111EBuL.toLong()
        x = x xor (x ushr 29)
        return ((x ushr 11) and 0xFFFF).toFloat() / 0xFFFF
    }
}
