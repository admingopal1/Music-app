package com.app.musicplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartMixTest {
    @Test fun favoritesAndStrongRatingsRankAheadOfUnplayedSongs() {
        val ranked = SmartMix.rank(
            songIds = listOf(1L, 2L, 3L),
            stats = mapOf(
                1L to SongStats(plays = 2),
                2L to SongStats(favorite = true),
                3L to SongStats(rating = 5),
            ),
            nowMs = 1_000_000L,
        )
        assertEquals(listOf(2L, 3L, 1L), ranked)
    }

    @Test fun recentListeningAddsABonusAndTiesRemainStable() {
        val ranked = SmartMix.rank(
            songIds = listOf(10L, 20L, 30L),
            stats = mapOf(
                20L to SongStats(lastPlayed = 900_000L),
                30L to SongStats(lastPlayed = 900_000L),
            ),
            nowMs = 1_000_000L,
        )
        assertEquals(listOf(20L, 30L, 10L), ranked)
    }

    @Test fun respectsLimitAndHandlesEmptyOrInvalidLimits() {
        assertEquals(listOf(1L, 2L), SmartMix.rank(listOf(1L, 2L, 3L), emptyMap(), limit = 2))
        assertEquals(emptyList<Long>(), SmartMix.rank(listOf(1L), emptyMap(), limit = 0))
        assertEquals(emptyList<Long>(), SmartMix.rank(emptyList(), emptyMap()))
    }

    @Test fun capsPlayCountContributionAndClampsInvalidRatings() {
        val ranked = SmartMix.rank(
            songIds = listOf(1L, 2L),
            stats = mapOf(
                1L to SongStats(plays = 10_000, rating = 99),
                2L to SongStats(plays = 30, rating = 5),
            ),
        )
        assertEquals(listOf(1L, 2L), ranked)
    }
}
