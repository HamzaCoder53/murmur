package com.bitchat.desktop.storage

import android.util.Log
import com.bitchat.desktop.shim.OwnerOnlyFiles
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One owner-only file sealed with AES-256-GCM: `SKS1 | 96-bit random nonce | ciphertext+tag`,
 * with [purpose] bound as associated data so a file cannot be swapped for another store's file.
 * The key comes from the OS credential store (Keychain on macOS, DPAPI on Windows) through
 * [com.bitchat.desktop.platform.SecureKeyStore]. A file that fails authentication reads as absent
 * (fail closed). Writes are atomic (temp file + move).
 */
class SealedFile(
    val file: File,
    key: ByteArray,
    private val purpose: String,
) {
    init {
        require(key.size == 32) { "key must be 256 bits" }
    }

    private val keySpec = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    fun read(): ByteArray? {
        if (!file.exists()) return null
        return try {
            val stored = file.readBytes()
            require(stored.size > MAGIC.size + NONCE_BYTES + TAG_BITS / 8) { "truncated" }
            require(stored.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "bad header" }
            val nonce = stored.copyOfRange(MAGIC.size, MAGIC.size + NONCE_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
            val offset = MAGIC.size + NONCE_BYTES
            cipher.doFinal(stored, offset, stored.size - offset)
        } catch (e: Exception) {
            Log.w(TAG, "Sealed $purpose store unreadable; treating as empty (${e.javaClass.simpleName})")
            null
        }
    }

    fun write(plain: ByteArray) {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        OwnerOnlyFiles.writeAtomically(file, MAGIC + nonce + cipher.doFinal(plain))
    }

    fun delete(): Boolean = !file.exists() || file.delete()

    private companion object {
        const val TAG = "SealedFile"
        val MAGIC = "SKS1".toByteArray(Charsets.US_ASCII)
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
