package com.app.musicplayer.lyrics

import java.io.InputStream
import java.nio.charset.Charset

/** Reads the unsynchronised lyrics frame (USLT) from an MP3's ID3v2.3/2.4 tag. */
object Id3Lyrics {

    fun read(input: InputStream): String? {
        val header = ByteArray(10)
        if (!readFully(input, header)) return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        val version = header[3].toInt()
        if (version != 3 && version != 4) return null
        val flags = header[5].toInt()
        val size = synchsafe(header, 6)
        if (size <= 0 || size > 4_000_000) return null

        var tag = ByteArray(size)
        if (!readFully(input, tag)) return null
        if (flags and 0x80 != 0) tag = removeUnsync(tag)

        var pos = 0
        if (flags and 0x40 != 0 && tag.size >= 4) {      // extended header
            val ext = if (version == 4) synchsafe(tag, 0) else int32(tag, 0) + 4
            pos = ext.coerceAtLeast(0)
        }
        while (pos + 10 <= tag.size) {
            if (tag[pos].toInt() == 0) break
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            val frameSize = if (version == 4) synchsafe(tag, pos + 4) else int32(tag, pos + 4)
            val start = pos + 10
            if (frameSize <= 0 || start + frameSize > tag.size) break
            if (id == "USLT") return decodeUslt(tag, start, frameSize)
            pos = start + frameSize
        }
        return null
    }

    private fun decodeUslt(b: ByteArray, start: Int, size: Int): String? {
        if (size < 5) return null
        val enc = b[start].toInt()
        var p = start + 4                      // skip encoding byte + 3-byte language
        val end = start + size
        val wide = enc == 1 || enc == 2
        // skip the content descriptor (null terminated)
        while (p < end) {
            if (wide) {
                if (p + 1 < end && b[p].toInt() == 0 && b[p + 1].toInt() == 0) { p += 2; break }
                p += 2
            } else {
                if (b[p].toInt() == 0) { p += 1; break }
                p += 1
            }
        }
        if (p >= end) return null
        val cs: Charset = when (enc) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(b, p, end - p, cs).trim('\u0000').takeIf { it.isNotBlank() }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun synchsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun int32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun removeUnsync(src: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(src.size)
        var i = 0
        while (i < src.size) {
            out.write(src[i].toInt())
            if (src[i] == 0xFF.toByte() && i + 1 < src.size && src[i + 1].toInt() == 0) i++   // FF 00 -> FF
            i++
        }
        return out.toByteArray()
    }
}
