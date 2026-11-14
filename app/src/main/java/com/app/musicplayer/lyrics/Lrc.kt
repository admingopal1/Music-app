package com.app.musicplayer.lyrics

data class LyricWord(val timeMs: Long, val text: String)
data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord> = emptyList())

/** [synced] = lines carry timestamps (LRC). Otherwise the lines are plain text (unsynced lyrics). */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

object Lrc {
    private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val META = Regex("""^\[[A-Za-z#]+:.*]$""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    fun parse(raw: String): Lyrics? {
        val text = raw.removePrefix("\uFEFF")
        if (text.isBlank()) return null
        // Positive offsets delay lyrics; negative offsets advance them.
        val offset = (OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L)
            .coerceIn(-86_400_000L, 86_400_000L)

        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<LyricLine>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val stamps = TIME.findAll(line).filter { match ->
                match.groupValues[2].toLongOrNull()?.let { it in 0L..59L } == true
            }.toList()
            if (stamps.isEmpty()) {
                val textOnly = TIME.replace(line, "").replace(WORD_TAG, "").trim()
                if (textOnly.isNotEmpty() && !META.matches(line)) plain.add(LyricLine(-1L, textOnly))
            } else {
                val lyricText = line.substring(stamps.last().range.last + 1)
                val content = WORD_TAG.replace(lyricText, "").trim()
                val words = WORD_TAG.findAll(lyricText).mapIndexed { index, match ->
                    val start = match.range.last + 1
                    val end = WORD_TAG.findAll(lyricText).drop(index + 1).firstOrNull()?.range?.first ?: lyricText.length
                    val wordText = lyricText.substring(start, end)
                    LyricWord((wordTime(match) + offset).coerceAtLeast(0L), wordText)
                }.filter { it.text.isNotEmpty() }.toList()
                for (m in stamps) timed.add(
                    LyricLine((toMs(m) + offset).coerceAtLeast(0L), content, words)
                )
            }
        }
        return when {
            timed.isNotEmpty() -> Lyrics(timed.sortedBy { it.timeMs }, synced = true)
            plain.isNotEmpty() -> Lyrics(plain, synced = false)
            else -> null
        }
    }

    private fun wordTime(m: MatchResult): Long {
        val min = m.groupValues[1].toLongOrNull() ?: 0L
        val sec = m.groupValues[2].toLongOrNull() ?: 0L
        val fraction = m.groupValues[3]
        val ms = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return min * 60_000L + sec * 1000L + ms
    }

    private fun toMs(m: MatchResult): Long = wordTime(
        object : MatchResult {
            override val value: String get() = m.value
            override val groups: MatchGroupCollection get() = m.groups
            override val groupValues: List<String> get() = listOf("", m.groupValues[1], m.groupValues[2], m.groupValues[3])
            override val range: IntRange get() = m.range
            override fun next(): MatchResult? = null
        }
    )
}
