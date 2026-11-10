package com.app.musicplayer.share

import java.io.InputStream

/** A validated, inclusive single HTTP byte range. */
data class SingleByteRange(val start: Long, val endInclusive: Long) {
    companion object {
        /** Supports bytes=START-END, bytes=START-, and bytes=-SUFFIX. */
        fun parse(header: String, totalBytes: Long): SingleByteRange? {
            if (totalBytes <= 0L || !header.startsWith("bytes=", ignoreCase = true)) return null
            val spec = header.substringAfter('=', "").trim()
            if (spec.isEmpty() || ',' in spec) return null
            val dash = spec.indexOf('-')
            if (dash < 0 || spec.indexOf('-', dash + 1) >= 0) return null
            val left = spec.substring(0, dash).trim()
            val right = spec.substring(dash + 1).trim()
            if (left.isEmpty() && right.isEmpty()) return null
            if (left.isEmpty()) {
                val suffix = right.toLongOrNull()?.takeIf { it > 0L } ?: return null
                val length = suffix.coerceAtMost(totalBytes)
                return SingleByteRange(totalBytes - length, totalBytes - 1L)
            }
            val start = left.toLongOrNull()?.takeIf { it >= 0L } ?: return null
            if (start >= totalBytes) return null
            val requestedEnd = if (right.isEmpty()) totalBytes - 1L
                else right.toLongOrNull()?.takeIf { it >= start } ?: return null
            return SingleByteRange(start, requestedEnd.coerceAtMost(totalBytes - 1L))
        }
    }
}

/** Skip exactly [byteCount] bytes, falling back to reads when a stream cannot skip. */
internal fun InputStream.skipFully(byteCount: Long): Boolean {
    if (byteCount < 0L) return false
    var remaining = byteCount
    val buffer = ByteArray(8 * 1024)
    while (remaining > 0L) {
        val skipped = skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
            continue
        }
        val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
        when {
            read > 0 -> remaining -= read.toLong()
            read < 0 -> return false
            else -> {
                if (read() < 0) return false
                remaining--
            }
        }
    }
    return true
}
