package com.bitchat.android.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Any failure to open a `.skein` stream: wrong key, damaged or tampered data, unsupported file. */
class SkeinFormatException(
    message: String,
    cause: Throwable? = null,
    /** The very first chunk failed: almost always a wrong passphrase or key. */
    val wrongKey: Boolean = false
) : IOException(message, cause)

/**
 * Outer layer of the `.skein` format: a plaintext header followed by a chunked AES-256-GCM stream.
 *
 * ```
 * header (39 bytes, also the AEAD associated data of every chunk)
 *   "SKEIN1"            6  magic
 *   version             1  = 1
 *   kdf                 1  1 = PBKDF2-HMAC-SHA256(passphrase), 2 = raw 256-bit key (transfer)
 *   iterations          4  big-endian, 0 for raw keys
 *   salt               16  random per file
 *   nonce prefix        7  random per file
 *   chunk size          4  plaintext bytes per chunk (65536)
 * chunks, repeated
 *   flags|length        4  high bit = final chunk, low 31 bits = ciphertext length
 *   ciphertext          n  AES-GCM(fileKey, nonce = prefix || counter(4) || final(1), aad = header)
 * ```
 * fileKey = HKDF-SHA256(ikm = PBKDF2 output or raw key, salt, info = "skein archive v1").
 *
 * Every chunk is authenticated before any of its bytes are released; the chunk counter in the
 * nonce stops reordering, the final flag in the nonce stops truncation, and bytes after the
 * final chunk are rejected. A wrong key fails on the first chunk.
 */
object SkeinCrypto {
    val MAGIC = "SKEIN1".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val KDF_PBKDF2_SHA256 = 1
    const val KDF_RAW_KEY = 2
    const val PBKDF2_ITERATIONS = 310_000
    /** Refuse files claiming more work than this (denial of service) or less than the minimum. */
    const val MAX_PBKDF2_ITERATIONS = 5_000_000
    const val MIN_PASSPHRASE_LENGTH = 8
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val NONCE_PREFIX_BYTES = 7
    const val CHUNK_SIZE = 64 * 1024
    const val HEADER_BYTES = 6 + 1 + 1 + 4 + SALT_BYTES + NONCE_PREFIX_BYTES + 4
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val FINAL_FLAG = 0x8000_0000.toInt()
    private val HKDF_INFO = "skein archive v1".toByteArray(Charsets.US_ASCII)

    private val random = SecureRandom()

    /** How the archive key is obtained. */
    sealed interface KeySource {
        /** A user passphrase, stretched with PBKDF2. */
        class Passphrase(val passphrase: CharArray, val iterations: Int = PBKDF2_ITERATIONS) : KeySource

        /** A random 256-bit key (one-time transfer). */
        class RawKey(val key: ByteArray) : KeySource {
            init { require(key.size == KEY_BYTES) { "Raw key must be 32 bytes" } }
        }
    }

    data class Header(
        val kdf: Int,
        val iterations: Int,
        val salt: ByteArray,
        val noncePrefix: ByteArray,
        val chunkSize: Int
    ) {
        fun encode(): ByteArray = ByteBuffer.allocate(HEADER_BYTES).apply {
            put(MAGIC)
            put(VERSION.toByte())
            put(kdf.toByte())
            putInt(iterations)
            put(salt)
            put(noncePrefix)
            putInt(chunkSize)
        }.array()

        companion object {
            fun decode(bytes: ByteArray): Header {
                if (bytes.size != HEADER_BYTES) throw SkeinFormatException("Not a Skein backup")
                val buffer = ByteBuffer.wrap(bytes)
                val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
                if (!magic.contentEquals(MAGIC)) throw SkeinFormatException("Not a Skein backup")
                val version = buffer.get().toInt() and 0xFF
                if (version != VERSION) throw SkeinFormatException("Unsupported backup version $version")
                val kdf = buffer.get().toInt() and 0xFF
                val iterations = buffer.int
                val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
                val prefix = ByteArray(NONCE_PREFIX_BYTES).also { buffer.get(it) }
                val chunkSize = buffer.int
                when (kdf) {
                    KDF_PBKDF2_SHA256 -> if (iterations !in PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS) {
                        throw SkeinFormatException("Unsupported key derivation settings")
                    }
                    KDF_RAW_KEY -> if (iterations != 0) throw SkeinFormatException("Unsupported key settings")
                    else -> throw SkeinFormatException("Unsupported key derivation")
                }
                if (chunkSize != CHUNK_SIZE) throw SkeinFormatException("Unsupported chunk size")
                return Header(kdf, iterations, salt, prefix, chunkSize)
            }
        }
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    /** Opens an encrypting stream over [out]; the header is written immediately. Closing closes [out]. */
    fun encrypt(out: OutputStream, keySource: KeySource): OutputStream {
        val header = when (keySource) {
            is KeySource.Passphrase -> Header(
                KDF_PBKDF2_SHA256, keySource.iterations, randomBytes(SALT_BYTES),
                randomBytes(NONCE_PREFIX_BYTES), CHUNK_SIZE
            )
            is KeySource.RawKey -> Header(
                KDF_RAW_KEY, 0, randomBytes(SALT_BYTES), randomBytes(NONCE_PREFIX_BYTES), CHUNK_SIZE
            )
        }
        val headerBytes = header.encode()
        val key = fileKey(header, keySource)
        out.write(headerBytes)
        return EncryptingOutputStream(out, key, header.noncePrefix, headerBytes)
    }

    /**
     * Reads the header from [input] and returns a stream of authenticated plaintext. Throws
     * [SkeinFormatException] for a non-Skein file or a key source of the wrong kind; reading throws
     * it on a wrong key, tampering, or truncation.
     */
    fun decrypt(input: InputStream, keySource: KeySource): InputStream {
        val headerBytes = ByteArray(HEADER_BYTES)
        try {
            DataInputStream(input).readFully(headerBytes)
        } catch (e: EOFException) {
            throw SkeinFormatException("Not a Skein backup", e)
        }
        val header = Header.decode(headerBytes)
        val expectedKdf = if (keySource is KeySource.Passphrase) KDF_PBKDF2_SHA256 else KDF_RAW_KEY
        if (header.kdf != expectedKdf) {
            throw SkeinFormatException(
                if (header.kdf == KDF_RAW_KEY) "This file belongs to a phone-to-phone transfer"
                else "This file needs a passphrase"
            )
        }
        return DecryptingInputStream(input, fileKey(header, keySource), header.noncePrefix, headerBytes)
    }

    /** Peeks the KDF kind of a header without a key, for UI hints. Null if not a Skein file. */
    fun readHeader(bytes: ByteArray): Header? = try { Header.decode(bytes) } catch (_: Exception) { null }

    private fun fileKey(header: Header, keySource: KeySource): ByteArray {
        val ikm = when (keySource) {
            is KeySource.Passphrase -> pbkdf2(keySource.passphrase, header.salt, header.iterations)
            is KeySource.RawKey -> keySource.key.copyOf()
        }
        return try { hkdf(ikm, header.salt, HKDF_INFO, KEY_BYTES) } finally { ikm.fill(0) }
    }

    fun pbkdf2(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            previous = hmacSha256(prk, previous, info, byteArrayOf(counter.toByte()))
            out.write(previous)
            counter++
        }
        prk.fill(0)
        return out.toByteArray().copyOf(length)
    }

    private fun nonce(prefix: ByteArray, counter: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter).put((if (last) 1 else 0).toByte()).array()

    private class EncryptingOutputStream(
        out: OutputStream,
        key: ByteArray,
        private val prefix: ByteArray,
        private val aad: ByteArray
    ) : FilterOutputStream(out) {
        private val keySpec = SecretKeySpec(key, "AES")
        private val buffer = ByteArray(CHUNK_SIZE)
        private var filled = 0
        private var counter = 0
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Stream closed")
            var offset = off
            var remaining = len
            while (remaining > 0) {
                // Only emit a full chunk once more data arrives: the last chunk must carry the
                // final flag, and it is known to be last only at close().
                if (filled == CHUNK_SIZE) emitChunk(last = false)
                val n = minOf(remaining, CHUNK_SIZE - filled)
                System.arraycopy(b, offset, buffer, filled, n)
                filled += n
                offset += n
                remaining -= n
            }
        }

        private fun emitChunk(last: Boolean) {
            if (counter == Int.MAX_VALUE) throw IOException("Backup too large")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce(prefix, counter, last)))
            cipher.updateAAD(aad)
            val ct = cipher.doFinal(buffer, 0, filled)
            out.write(ByteBuffer.allocate(4).putInt(ct.size or (if (last) FINAL_FLAG else 0)).array())
            out.write(ct)
            counter++
            filled = 0
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                emitChunk(last = true)
                buffer.fill(0)
                out.flush()
            } finally {
                out.close()
            }
        }
    }

    private class DecryptingInputStream(
        private val input: InputStream,
        key: ByteArray,
        private val prefix: ByteArray,
        private val aad: ByteArray
    ) : InputStream() {
        private val keySpec = SecretKeySpec(key, "AES")
        private val data = DataInputStream(input)
        private var chunk = ByteArray(0)
        private var position = 0
        private var counter = 0
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position >= chunk.size) {
                if (finished) return -1
                nextChunk()
            }
            val n = minOf(len, chunk.size - position)
            System.arraycopy(chunk, position, b, off, n)
            position += n
            return n
        }

        override fun available(): Int = chunk.size - position

        private fun nextChunk() {
            val word = try {
                data.readInt()
            } catch (e: EOFException) {
                throw SkeinFormatException("Backup is incomplete", e)
            }
            val last = word and FINAL_FLAG != 0
            val length = word and FINAL_FLAG.inv()
            if (length < TAG_BYTES || length > CHUNK_SIZE + TAG_BYTES) {
                throw SkeinFormatException("Backup is damaged")
            }
            val ct = ByteArray(length)
            try {
                data.readFully(ct)
            } catch (e: EOFException) {
                throw SkeinFormatException("Backup is incomplete", e)
            }
            chunk = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce(prefix, counter, last)))
                cipher.updateAAD(aad)
                cipher.doFinal(ct)
            } catch (e: GeneralSecurityException) {
                throw SkeinFormatException(
                    if (counter == 0) "Wrong passphrase or damaged backup" else "Backup is damaged or was modified",
                    e,
                    wrongKey = counter == 0
                )
            }
            position = 0
            counter++
            if (last) {
                finished = true
                if (input.read() != -1) throw SkeinFormatException("Unexpected data after the end of the backup")
            }
        }

        override fun close() = input.close()
    }
}
