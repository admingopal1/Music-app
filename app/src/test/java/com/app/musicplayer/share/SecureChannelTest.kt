package com.app.musicplayer.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.Random

class SecureChannelTest {
    private fun keys(pinA: String = "123456", pinB: String = "123456"): Pair<ByteArray, ByteArray> {
        val a = SecureChannel.newKeyPair()
        val b = SecureChannel.newKeyPair()
        return SecureChannel.deriveKey(a, SecureChannel.encodePub(b), pinA) to SecureChannel.deriveKey(b, SecureChannel.encodePub(a), pinB)
    }

    private fun roundTrip(size: Int) {
        val (ka, kb) = keys()
        val data = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }
        val out = ByteArrayOutputStream()
        SecureChannel.encrypt(ka, ByteArrayInputStream(data), out, size.toLong())
        assertEquals(SecureChannel.encryptedLength(size.toLong()), out.size().toLong())
        val din = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val got = ByteArrayOutputStream()
        while (true) got.write(SecureChannel.readFrame(kb, din) ?: break)
        assertArrayEquals(data, got.toByteArray())
    }

    @Test fun bothSidesDeriveTheSameKey() {
        val (a, b) = keys()
        assertArrayEquals(a, b)
        assertEquals(32, a.size)
    }

    @Test fun wrongPinGivesDifferentKey() {
        val (a, b) = keys("123456", "654321")
        assertFalse(a.contentEquals(b))
    }

    @Test fun roundTripsSmallExactAndMultiChunkPayloads() {
        roundTrip(1)
        roundTrip(SecureChannel.CHUNK)
        roundTrip(SecureChannel.CHUNK * 2 + 777)
    }

    @Test fun emptyStreamEndsCleanly() {
        val (k, _) = keys()
        assertNull(SecureChannel.readFrame(k, DataInputStream(ByteArrayInputStream(ByteArray(0)))))
    }

    @Test fun tamperedCiphertextIsRejected() {
        val (ka, kb) = keys()
        val out = ByteArrayOutputStream()
        SecureChannel.encrypt(ka, ByteArrayInputStream(ByteArray(1000)), out, 1000)
        val bytes = out.toByteArray().also { it[40] = (it[40].toInt() xor 1).toByte() }
        try {
            SecureChannel.readFrame(kb, DataInputStream(ByteArrayInputStream(bytes)))
            fail("tampered frame accepted")
        } catch (e: Exception) {
            // expected: GCM tag mismatch
        }
    }

    @Test fun hexRoundTrip() {
        val b = byteArrayOf(0, 1, 127, -128, -1)
        assertArrayEquals(b, SecureChannel.unhex(SecureChannel.hex(b)))
    }
}
