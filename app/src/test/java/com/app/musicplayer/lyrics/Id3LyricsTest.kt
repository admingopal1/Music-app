package com.app.musicplayer.lyrics

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Id3LyricsTest {
    @Test fun readsUsltFromId3v23() {
        val tag = id3Tag(version = 3, payload = usltPayload("Hello from v2.3"))
        assertEquals("Hello from v2.3", Id3Lyrics.read(ByteArrayInputStream(tag)))
    }

    @Test fun readsUsltFromId3v24() {
        val tag = id3Tag(version = 4, payload = usltPayload("Hello from v2.4"))
        assertEquals("Hello from v2.4", Id3Lyrics.read(ByteArrayInputStream(tag)))
    }

    @Test fun ignoresNonId3AndTruncatedHeaders() {
        assertNull(Id3Lyrics.read(ByteArrayInputStream("not an id3 tag".toByteArray())))
        assertNull(Id3Lyrics.read(ByteArrayInputStream(byteArrayOf(0x49, 0x44, 0x33))))
    }

    @Test fun rejectsFrameSizesLargerThanTheRemainingTag() {
        val payload = usltPayload("x")
        val tag = id3Tag(version = 3, payload = payload, declaredFrameSize = Int.MAX_VALUE)
        assertNull(Id3Lyrics.read(ByteArrayInputStream(tag)))
    }

    private fun usltPayload(text: String): ByteArray =
        byteArrayOf(3) + "eng".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + text.toByteArray(Charsets.UTF_8)

    private fun id3Tag(version: Int, payload: ByteArray, declaredFrameSize: Int = payload.size): ByteArray {
        val frameSize = if (version == 4) synchsafe(declaredFrameSize) else int32(declaredFrameSize)
        val frame = "USLT".toByteArray(Charsets.ISO_8859_1) + frameSize + byteArrayOf(0, 0) + payload
        val header = byteArrayOf(
            0x49, 0x44, 0x33, version.toByte(), 0, 0
        ) + synchsafe(frame.size)
        return header + frame
    }

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun int32(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )
}
