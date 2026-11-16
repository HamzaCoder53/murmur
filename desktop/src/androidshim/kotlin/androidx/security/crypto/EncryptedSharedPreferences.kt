package androidx.security.crypto

import android.content.Context
import android.content.SharedPreferences
import com.bitchat.desktop.shim.FileSharedPreferences
import com.bitchat.desktop.shim.PreferenceCodec
import com.bitchat.desktop.shim.ShimRuntime
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stand-in for androidx.security.crypto.EncryptedSharedPreferences. The whole preference file
 * is sealed with AES-256-GCM (random 96-bit nonce, store name bound as associated data) using a
 * key from ShimRuntime.masterKeyProvider. Keys and values are therefore both confidential, which
 * covers what the shared code relies on (AES256_SIV keys + AES256_GCM values on Android).
 * A file that fails authentication is treated as empty (fail closed).
 */
object EncryptedSharedPreferences {
    enum class PrefKeyEncryptionScheme { AES256_SIV }
    enum class PrefValueEncryptionScheme { AES256_GCM }

    private val MAGIC = "SKP1".toByteArray(Charsets.US_ASCII)
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    private val cache = ConcurrentHashMap<String, SharedPreferences>()

    @JvmStatic
    fun create(
        context: Context,
        fileName: String,
        masterKey: MasterKey,
        @Suppress("UNUSED_PARAMETER") keyScheme: PrefKeyEncryptionScheme,
        @Suppress("UNUSED_PARAMETER") valueScheme: PrefValueEncryptionScheme,
    ): SharedPreferences {
        val file = File(context.prefsDir(), "$fileName.enc")
        return cache.getOrPut(file.canonicalPath) {
            val key = ShimRuntime.masterKeyProvider.getOrCreateKey(masterKey.alias)
            require(key.size == 32) { "master key must be 256 bits" }
            FileSharedPreferences(file, GcmCodec(key, fileName.toByteArray(Charsets.UTF_8)))
        }
    }

    /** Test hook: forget cached instances (for example after deleting a data directory). */
    fun resetCacheForTests() = cache.clear()

    private class GcmCodec(key: ByteArray, private val aad: ByteArray) : PreferenceCodec {
        private val keySpec = SecretKeySpec(key, "AES")
        private val random = SecureRandom()

        override fun encode(plain: ByteArray): ByteArray {
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            return MAGIC + nonce + cipher.doFinal(plain)
        }

        override fun decode(stored: ByteArray): ByteArray {
            require(stored.size > MAGIC.size + NONCE_BYTES + TAG_BITS / 8) { "truncated" }
            require(stored.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "bad header" }
            val nonce = stored.copyOfRange(MAGIC.size, MAGIC.size + NONCE_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            val offset = MAGIC.size + NONCE_BYTES
            return cipher.doFinal(stored, offset, stored.size - offset)
        }
    }
}
