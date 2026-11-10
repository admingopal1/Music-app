package com.app.musicplayer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SongStats(
    val favorite: Boolean = false,
    val rating: Int = 0,          // 0..5
    val plays: Int = 0,
    val lastPlayed: Long = 0L,
    val updatedAt: Long = 0L,
)

/** Favorites, 1-5 star ratings, play counts and last-played time. Stored locally (SharedPreferences). */
class StatsStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("song_stats", Context.MODE_PRIVATE)
    private val _stats = MutableStateFlow(load())
    val stats: StateFlow<Map<Long, SongStats>> = _stats.asStateFlow()

    private fun load(): Map<Long, SongStats> {
        val out = HashMap<Long, SongStats>()
        for ((k, v) in prefs.all) {
            val id = k.toLongOrNull() ?: continue
            val p = (v as? String)?.split(',') ?: continue
            if (p.size < 4) continue
            out[id] = SongStats(
                favorite = p[0] == "1",
                rating = p[1].toIntOrNull() ?: 0,
                plays = p[2].toIntOrNull() ?: 0,
                lastPlayed = p[3].toLongOrNull() ?: 0L,
                updatedAt = p.getOrNull(4)?.toLongOrNull() ?: 0L,
            )
        }
        return out
    }

    private fun save(id: Long, s: SongStats) {
        // Keep local edits strictly ordered even when changes happen in the same millisecond
        // or the device clock moves backwards.
        val updated = s.copy(updatedAt = maxOf(System.currentTimeMillis(), s.updatedAt + 1L))
        prefs.edit().putString(id.toString(), "${if (updated.favorite) 1 else 0},${updated.rating},${updated.plays},${updated.lastPlayed},${updated.updatedAt}").apply()
        _stats.value = _stats.value + (id to updated)
    }

    /** Writes many entries at once (used by restore). */
    fun putAll(entries: Map<Long, SongStats>) {
        if (entries.isEmpty()) return
        val e = prefs.edit()
        for ((id, s) in entries) e.putString(id.toString(), "${if (s.favorite) 1 else 0},${s.rating},${s.plays},${s.lastPlayed},${s.updatedAt}")
        e.apply()
        _stats.value = _stats.value + entries
    }

    fun toggleFavorite(id: Long) {
        val s = _stats.value[id] ?: SongStats()
        save(id, s.copy(favorite = !s.favorite))
    }

    /** Tapping the same star again clears the rating. */
    fun setRating(id: Long, rating: Int) {
        val s = _stats.value[id] ?: SongStats()
        save(id, s.copy(rating = if (s.rating == rating) 0 else rating.coerceIn(0, 5)))
    }

    fun recordPlay(id: Long) {
        val s = _stats.value[id] ?: SongStats()
        save(id, s.copy(plays = s.plays + 1, lastPlayed = System.currentTimeMillis()))
    }
}
