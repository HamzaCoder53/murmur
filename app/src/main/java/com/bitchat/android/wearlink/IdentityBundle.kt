package com.bitchat.android.wearlink

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Everything a second device needs to act as the same Murmur/Bitchat user: the Noise identity,
 * both Ed25519 signing keys, the Nostr (internet) identity, favorites and verified contacts.
 *
 * Bitchat keeps these in two encrypted preference files. The bundle copies their stored string
 * values verbatim, so no key is re-encoded and the receiving device's own encryption at rest
 * protects them once written. The bundle itself only ever travels over the Wear OS link between
 * the user's own paired devices, and only after the user approves it on the phone.
 */
object IdentityBundle {
    private const val VERSION = 1

    /** Encrypted prefs file written by SecureIdentityStateManager. */
    const val IDENTITY_PREFS = "bitchat_identity"

    /** Encrypted prefs file written by EncryptionService (packet-signing Ed25519 key). */
    const val CRYPTO_PREFS = "bitchat_crypto_secure"

    val IDENTITY_STRING_KEYS = listOf(
        "static_private_key",
        "static_public_key",
        "signing_private_key",
        "signing_public_key",
        "nostr_private_key",
        "nostr_device_seed",
        "favorite_relationships",
        "favorite_peerid_index"
    )
    val IDENTITY_SET_KEYS = listOf("verified_fingerprints")
    val CRYPTO_STRING_KEYS = listOf("ed25519_signing_private_key")

    /** Keys without which the identity is unusable. */
    private val REQUIRED_KEYS = listOf("static_private_key", "static_public_key")

    data class Contents(
        val nickname: String,
        val identityStrings: Map<String, String>,
        val identitySets: Map<String, Set<String>>,
        val cryptoStrings: Map<String, String>
    ) {
        /** Mesh peer ID this identity presents: first 16 hex chars of SHA-256(static public key). */
        val peerID: String?
            get() = identityStrings["static_public_key"]?.let { encoded ->
                val publicKey = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                MessageDigest.getInstance("SHA-256").digest(publicKey)
                    .joinToString("") { "%02x".format(it) }
                    .take(16)
            }
    }

    // --- Pure encoding (unit-tested) ---

    fun encode(contents: Contents): ByteArray {
        val json = JSONObject()
        json.put("v", VERSION)
        json.put("nickname", contents.nickname)
        json.put("identity", JSONObject(contents.identityStrings))
        json.put("identitySets", JSONObject().apply {
            contents.identitySets.forEach { (k, v) -> put(k, JSONArray(v.toList())) }
        })
        json.put("crypto", JSONObject(contents.cryptoStrings))
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /** Returns null for anything malformed, from a newer version, or missing the Noise key. */
    fun decode(data: ByteArray): Contents? = try {
        val json = JSONObject(String(data, Charsets.UTF_8))
        if (json.optInt("v", -1) != VERSION) {
            null
        } else {
            val identity = json.getJSONObject("identity").toStringMap()
                .filterKeys { it in IDENTITY_STRING_KEYS }
            val sets = json.optJSONObject("identitySets")?.let { obj ->
                obj.keys().asSequence().filter { it in IDENTITY_SET_KEYS }.associateWith { key ->
                    val array = obj.getJSONArray(key)
                    (0 until array.length()).map { array.getString(it) }.toSet()
                }
            }.orEmpty()
            val crypto = json.getJSONObject("crypto").toStringMap()
                .filterKeys { it in CRYPTO_STRING_KEYS }
            if (REQUIRED_KEYS.any { identity[it].isNullOrBlank() }) {
                null
            } else {
                Contents(json.optString("nickname", ""), identity, sets, crypto)
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun JSONObject.toStringMap(): Map<String, String> =
        keys().asSequence().associateWith { getString(it) }

    // --- Device storage ---

    private fun open(context: Context, name: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Read this device's identity. Null if it has no Noise identity yet. */
    fun read(context: Context, nickname: String): Contents? {
        val app = context.applicationContext
        val identity = open(app, IDENTITY_PREFS)
        val crypto = open(app, CRYPTO_PREFS)
        val contents = Contents(
            nickname = nickname,
            identityStrings = IDENTITY_STRING_KEYS.mapNotNull { k -> identity.getString(k, null)?.let { k to it } }.toMap(),
            identitySets = IDENTITY_SET_KEYS.mapNotNull { k -> identity.getStringSet(k, null)?.let { k to it.toSet() } }.toMap(),
            cryptoStrings = CRYPTO_STRING_KEYS.mapNotNull { k -> crypto.getString(k, null)?.let { k to it } }.toMap()
        )
        return contents.takeIf { c -> REQUIRED_KEYS.all { !c.identityStrings[it].isNullOrBlank() } }
    }

    /**
     * Replace this device's identity with [contents]. Everything else in both files (cached peer
     * keys, sessions state) belonged to the old identity and is cleared. The app must restart
     * afterwards: running services hold the old keys in memory.
     */
    fun replace(context: Context, contents: Contents): Boolean {
        val app = context.applicationContext
        val identityOk = open(app, IDENTITY_PREFS).edit().clear().apply {
            contents.identityStrings.forEach { (k, v) -> putString(k, v) }
            contents.identitySets.forEach { (k, v) -> putStringSet(k, v) }
        }.commit()
        val cryptoOk = open(app, CRYPTO_PREFS).edit().clear().apply {
            contents.cryptoStrings.forEach { (k, v) -> putString(k, v) }
        }.commit()
        return identityOk && cryptoOk
    }

    /** Erase this device's identity entirely; a fresh one is generated on next start. */
    fun erase(context: Context): Boolean {
        val app = context.applicationContext
        val identityOk = open(app, IDENTITY_PREFS).edit().clear().commit()
        val cryptoOk = open(app, CRYPTO_PREFS).edit().clear().commit()
        return identityOk && cryptoOk
    }
}
