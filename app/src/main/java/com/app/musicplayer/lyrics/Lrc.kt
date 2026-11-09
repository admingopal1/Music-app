package com.app.musicplayer.lyrics

data class LyricLine(val timeMs: Long, val text: String)

/** [synced] = lines carry timestamps (LRC). Otherwise the lines are plain text (unsynced lyrics). */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

object Lrc {
    private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val META = Regex("""^\[[A-Za-z#]+:.*]$""")
    private val WORD_TAG = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(raw: String): Lyrics? {
        val text = raw.removePrefix("\uFEFF")
        if (text.isBlank()) return null
        val offset = OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<LyricLine>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val stamps = TIME.findAll(line).toList()
            if (stamps.isEmpty()) {
                if (!META.matches(line)) plain.add(LyricLine(-1L, line))
            } else {
                val content = line.substring(stamps.last().range.last + 1).replace(WORD_TAG, "").trim()
                for (m in stamps) timed.add(LyricLine((toMs(m) - offset).coerceAtLeast(0L), content))
            }
        }
        return when {
            timed.isNotEmpty() -> Lyrics(timed.sortedBy { it.timeMs }, synced = true)
            plain.isNotEmpty() -> Lyrics(plain, synced = false)
            else -> null
        }
    }

    private fun toMs(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val fracStr = m.groupValues[3]
        val frac = when (fracStr.length) {
            0 -> 0L
            1 -> fracStr.toLong() * 100
            2 -> fracStr.toLong() * 10
            else -> fracStr.take(3).toLong()
        }
        return min * 60_000L + sec * 1000L + frac
    }
}
