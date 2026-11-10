package com.app.musicplayer.share

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for app-to-app transfers.
 * Ephemeral ECDH (P-256) per connection, so a Wi-Fi eavesdropper who sees the PIN still can't read files.
 * The PIN is mixed into the HKDF salt so only a device that knows the PIN derives the same key.
 * Keys travel hex-encoded. Payload is framed as `[len:int][nonce:12][AES-256-GCM ciphertext+tag]` chunks of up to [CHUNK] plaintext bytes.
 */
object SecureChannel {
    const val CHUNK = 256 * 1024
    private const val OVERHEAD = 4 + 12 + 16
    private val rnd = SecureRandom()

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun encodePub(kp: KeyPair): String = hex(kp.public.encoded)

    /** Derives the shared 256-bit AES key from our key pair, the peer's public key and the PIN. */
    fun deriveKey(mine: KeyPair, peerPubHex: String, pin: String): ByteArray {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unhex(peerPubHex)))
        val ka = KeyAgreement.getInstance("ECDH").apply { init(mine.private); doPhase(peer, true) }
        return hkdf(ka.generateSecret(), salt = ("MusicShare|" + pin).toByteArray(), info = "file-transfer-v2".toByteArray())
    }

    /** Size of the encrypted body for [plain] bytes of payload. */
    fun encryptedLength(plain: Long): Long {
        if (plain <= 0) return 0
        val chunks = (plain + CHUNK - 1) / CHUNK
        return plain + chunks * OVERHEAD
    }

    /** Encrypts [plain] bytes read from [input] into [out]; [onChunk] gets the running plaintext count. */
    fun encrypt(key: ByteArray, input: InputStream, out: OutputStream, plain: Long, onChunk: (Long) -> Unit = {}) {
        val buf = ByteArray(CHUNK)
        var done = 0L
        val dos = java.io.DataOutputStream(out)
        while (done < plain) {
            val want = minOf(CHUNK.toLong(), plain - done).toInt()
            var got = 0
            while (got < want) {
                val n = input.read(buf, got, want - got)
                if (n < 0) throw EOFException("File ended early")
                got += n
            }
            val nonce = ByteArray(12).also { rnd.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
            val ct = c.doFinal(buf, 0, got)
            dos.writeInt(ct.size); dos.write(nonce); dos.write(ct)
            done += got
            onChunk(done)
        }
        dos.flush()
    }

    /**
     * Reads one encrypted frame and returns its plaintext, or null at a clean end of stream.
     * Throws if the frame was tampered with (GCM tag mismatch).
     */
    fun readFrame(key: ByteArray, input: DataInputStream): ByteArray? {
        val len = try { input.readInt() } catch (e: EOFException) { return null }
        require(len in 16..(CHUNK + 16)) { "Bad frame" }
        val nonce = ByteArray(12).also { input.readFully(it) }
        val ct = ByteArray(len).also { input.readFully(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
        return c.doFinal(ct)
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(ikm)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        return mac.doFinal(info + byteArrayOf(1))
    }

    fun sha256Hex(d: MessageDigest): String = d.digest().joinToString("") { "%02x".format(it) }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
