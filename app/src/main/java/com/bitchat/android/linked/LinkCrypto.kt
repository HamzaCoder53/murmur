package com.bitchat.android.linked

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitives for the linked-device channel. Only JDK crypto (HMAC-SHA256, HKDF, AES-256-GCM), so
 * the channel adds no dependency and runs unchanged in JVM tests.
 */
object LinkCrypto {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 32
    const val GCM_TAG_BITS = 128

    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { part ->
            // Length-prefixed, so ("ab","c") and ("a","bc") never authenticate the same bytes.
            mac.update(ByteBuffer.allocate(4).putInt(part.size).array())
            mac.update(part)
        }
        return mac.doFinal()
    }

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = extractMac.doFinal(ikm)
        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            expandMac.update(previous)
            expandMac.update(info.toByteArray(Charsets.UTF_8))
            expandMac.update(counter.toByte())
            previous = expandMac.doFinal()
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

/**
 * Seals and opens the frames of one authenticated link session.
 *
 * Each direction has its own key, and every frame carries an 8-byte counter that is also the GCM
 * nonce. A receiver accepts only the next counter, so replayed, reordered, dropped-and-resent or
 * reflected frames all fail closed.
 */
class FrameCipher(sendKey: ByteArray, receiveKey: ByteArray) {
    private val sendSpec = SecretKeySpec(sendKey.copyOf(), "AES")
    private val receiveSpec = SecretKeySpec(receiveKey.copyOf(), "AES")
    private var sendCounter = 0L
    private var receiveCounter = 0L

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        val counter = sendCounter++
        val nonce = nonceFor(counter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sendSpec, GCMParameterSpec(LinkCrypto.GCM_TAG_BITS, nonce))
        val sealed = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(8 + sealed.size).putLong(counter).put(sealed).array()
    }

    /** Returns null for anything that is not the next authentic frame. */
    @Synchronized
    fun open(frame: ByteArray): ByteArray? {
        if (frame.size < 8 + 16) return null
        val counter = ByteBuffer.wrap(frame, 0, 8).long
        if (counter != receiveCounter) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, receiveSpec, GCMParameterSpec(LinkCrypto.GCM_TAG_BITS, nonceFor(counter)))
            val plain = cipher.doFinal(frame, 8, frame.size - 8)
            receiveCounter++
            plain
        } catch (_: Exception) {
            null
        }
    }

    private fun nonceFor(counter: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
}
