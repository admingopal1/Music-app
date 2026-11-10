package com.app.musicplayer.playback

import kotlin.math.abs

/**
 * Pure playback option and timer calculations shared by the player and JVM tests.
 * Invalid speed/pitch values fall back to normal playback rather than selecting
 * an arbitrary extreme value.
 */
object PlaybackOptions {
    val speeds = listOf(0.25f, 0.5f, 1f, 1.25f, 1.5f, 2f, 3f)
    val pitches = listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 2f)

    const val SLEEP_FADE_DURATION_MS = 15_000L
    private const val MAX_SLEEP_MINUTES = 7L * 24L * 60L

    fun nearestSpeed(value: Float): Float = nearest(value, speeds, 1f)
    fun nearestPitch(value: Float): Float = nearest(value, pitches, 1f)

    private fun nearest(value: Float, options: List<Float>, fallback: Float): Float {
        if (!value.isFinite()) return fallback
        return options.minBy { abs(it - value) }
    }

    /** Zero or negative minutes disable the timer; positive values are safely widened. */
    fun sleepDurationMs(minutes: Int): Long {
        if (minutes <= 0) return 0L
        return minutes.toLong().coerceAtMost(MAX_SLEEP_MINUTES) * 60_000L
    }

    fun remainingMs(deadlineMs: Long, nowMs: Long): Long =
        (deadlineMs - nowMs).coerceAtLeast(0L)

    /** Full volume until the final 15 seconds, then a linear fade to silence. */
    fun sleepFadeVolume(remainingMs: Long): Float {
        if (remainingMs <= 0L) return 0f
        return (remainingMs.toFloat() / SLEEP_FADE_DURATION_MS).coerceIn(0f, 1f)
    }
}
