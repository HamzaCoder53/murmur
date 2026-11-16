package com.bitchat.desktop.core

import android.content.Context
import com.bitchat.android.crypto.EncryptionService
import java.security.SecureRandom

/**
 * The local Skein identity on desktop: the same Noise static key + Ed25519 signing key the phone
 * keeps (shared EncryptionService / SecureIdentityStateManager, persisted through the encrypted
 * preference shim), so the peer ID is derived exactly as on Android: the first 16 hex characters
 * (8 bytes) of SHA-256(Noise static public key).
 */
class DesktopIdentity private constructor(
    val context: Context,
    val encryptionService: EncryptionService,
) {
    val fingerprint: String = encryptionService.getIdentityFingerprint()
    val peerID: String = fingerprint.take(PEER_ID_HEX_CHARS)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var nickname: String
        get() = prefs.getString(KEY_NICKNAME, null) ?: DEFAULT_NICKNAME
        set(value) {
            val trimmed = value.trim().take(MAX_NICKNAME_CHARS)
            if (trimmed.isNotEmpty()) prefs.edit().putString(KEY_NICKNAME, trimmed).commit()
        }

    companion object {
        const val PEER_ID_HEX_CHARS = 16
        private const val PREFS_NAME = "skein_desktop"
        private const val KEY_NICKNAME = "nickname"
        private const val MAX_NICKNAME_CHARS = 32
        private const val DEFAULT_NICKNAME = "anon"

        /** Loads the persisted identity under [context], creating keys and a nickname on first run. */
        fun loadOrCreate(context: Context): DesktopIdentity {
            val identity = DesktopIdentity(context, EncryptionService(context))
            if (identity.prefs.getString(KEY_NICKNAME, null) == null) {
                // Same shape as Bitchat's default ("anon" + 4 digits); no host or user data.
                identity.nickname = "anon" + (1000 + SecureRandom().nextInt(9000))
            }
            return identity
        }
    }
}
