package com.app.musicplayer.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {
    @Test fun parsesBomAndHundredthSecondTimestamps() {
        val parsed = Lrc.parse("\uFEFF[00:01.25]First line\n[00:02.5]Second line")!!
        assertTrue(parsed.synced)
        assertEquals(1_250L, parsed.lines[0].timeMs)
        assertEquals(2_500L, parsed.lines[1].timeMs)
    }

    @Test fun positiveOffsetDelaysLyrics() {
        val parsed = Lrc.parse("[offset:+100]\n[00:01.00]Hello")!!
        assertEquals(1_100L, parsed.lines.single().timeMs)
    }

    @Test fun negativeOffsetAdvancesLyricsAndClampsAtZero() {
        val parsed = Lrc.parse("[offset:-250]\n[00:01.00]Later\n[00:00.10]Early")!!
        assertEquals(listOf(0L, 750L), parsed.lines.map { it.timeMs })
    }

    @Test fun multipleTimestampsOnOneLineAreExpandedAndSorted() {
        val parsed = Lrc.parse("[00:02.00][00:01.00]Chorus")!!
        assertEquals(listOf(1_000L, 2_000L), parsed.lines.map { it.timeMs })
        assertEquals(listOf("Chorus", "Chorus"), parsed.lines.map { it.text })
    }

    @Test fun rejectsInvalidSecondsInsteadOfCreatingBadSyncTimes() {
        val parsed = Lrc.parse("[00:61.00]Visible lyric")!!
        assertFalse(parsed.synced)
        assertEquals("Visible lyric", parsed.lines.single().text)
    }

    @Test fun plainLyricsIgnoreMetadataAndWordTimingTags() {
        val parsed = Lrc.parse("[ar:Artist]\n[ti:Title]\nFirst <00:01.20> line\nSecond line")!!
        assertFalse(parsed.synced)
        assertEquals(listOf("First  line", "Second line"), parsed.lines.map { it.text })
    }

    @Test fun preservesMillisecondPrecisionForThreeDigitFractions() {
        val parsed = Lrc.parse("[00:01.237]Precise")!!
        assertEquals(1_237L, parsed.lines.single().timeMs)
    }

    @Test fun clampsExtremeOffsetsToOneDay() {
        val parsed = Lrc.parse("[offset:+999999999]Bounded\n[00:00.00]Line")!!
        assertEquals(86_400_000L, parsed.lines.single().timeMs)
    }

    @Test fun emptyOrMetadataOnlyLyricsReturnNull() {
        assertNull(Lrc.parse("  \n"))
        assertNull(Lrc.parse("[ar:Artist]\n[ti:Title]"))
    }
}
