package com.app.musicplayer.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SingleByteRangeTest {
    @Test fun parsesClosedRange() {
        assertEquals(SingleByteRange(10, 19), SingleByteRange.parse("bytes=10-19", 100))
    }
    @Test fun parsesOpenEndedRange() {
        assertEquals(SingleByteRange(90, 99), SingleByteRange.parse("bytes=90-", 100))
    }
    @Test fun parsesSuffixRange() {
        assertEquals(SingleByteRange(90, 99), SingleByteRange.parse("bytes=-10", 100))
        assertEquals(SingleByteRange(0, 99), SingleByteRange.parse("bytes=-500", 100))
    }
    @Test fun clampsEndToFileLength() {
        assertEquals(SingleByteRange(90, 99), SingleByteRange.parse("bytes=90-500", 100))
    }
    @Test fun rejectsMalformedAndUnsatisfiableRanges() {
        listOf("items=0-1", "bytes=", "bytes=-0", "bytes=100-", "bytes=9-2",
            "bytes=1-2,4-5", "bytes=abc-def", "bytes=--2").forEach {
            assertNull("$it should be rejected", SingleByteRange.parse(it, 100))
        }
        assertNull(SingleByteRange.parse("bytes=0-1", 0))
    }
}
