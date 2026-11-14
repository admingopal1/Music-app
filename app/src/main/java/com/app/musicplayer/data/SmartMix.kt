package com.app.musicplayer.data

/**
 * Deterministic, on-device ranking for a personal mix. No network or listening-history upload.
 * A caller supplies the current library and stats snapshot; unknown songs remain eligible
 * so a small library can still produce a useful mix.
 */
object SmartMix {
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val MONTH_MS = 30L * 24 * 60 * 60 * 1000

    fun rank(
        songIds: List<Long>,
        stats: Map<Long, SongStats>,
        limit: Int = 30,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Long> {
        if (limit <= 0 || songIds.isEmpty()) return emptyList()
        return songIds.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Long>> { (_, id) ->
                    val s = stats[id] ?: SongStats()
                    (if (s.favorite) 100 else 0) +
                        s.rating.coerceIn(0, 5) * 10 +
                        s.plays.coerceAtLeast(0).coerceAtMost(30) * 2 +
                        when {
                            s.lastPlayed <= 0L -> 0
                            nowMs - s.lastPlayed in 0..WEEK_MS -> 20
                            nowMs - s.lastPlayed in 0..MONTH_MS -> 10
                            else -> 0
                        }
                }
                    .thenByDescending { (_, id) -> stats[id]?.plays ?: 0 }
                    .thenByDescending { (_, id) -> stats[id]?.lastPlayed ?: 0L }
                    .thenBy { it.index },
            )
            .take(limit)
            .map { it.value }
    }
}
