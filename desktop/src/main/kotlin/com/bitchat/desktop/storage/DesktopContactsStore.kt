package com.bitchat.desktop.storage

import android.content.Context
import com.bitchat.android.favorites.FavoritesChangeListener
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.desktop.ui.ContactsPort
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent [ContactsPort].
 *
 * Favorites live in the shared [FavoritesPersistenceService] (the same records the phone keeps,
 * stored through the encrypted preference shim), keyed by the peer's Noise static key. The mesh
 * peer ID is derived from that key, so the favorite set survives restarts and peer-ID lookups.
 * A favorite set on a peer whose Noise key is not known yet is kept by peer ID in this store's
 * sealed file until the key is learned.
 *
 * Verified fingerprints are kept in the same sealed file (AES-256-GCM, OS key store key).
 */
class DesktopContactsStore(
    context: Context,
    private val sealed: SealedFile,
    private val noiseKeyFor: (peerID: String) -> ByteArray?,
) : ContactsPort, AutoCloseable {

    private val lock = Any()
    private var pendingFavoritePeerIDs: Set<String> = emptySet()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    override val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _verified = MutableStateFlow<Set<String>>(emptySet())
    override val verifiedFingerprints: StateFlow<Set<String>> = _verified.asStateFlow()

    private val listener = object : FavoritesChangeListener {
        override fun onFavoriteChanged(noiseKeyHex: String) = refreshFavorites()
        override fun onAllCleared() = refreshFavorites()
    }

    init {
        FavoritesPersistenceService.initialize(context)
        sealed.read()?.let { bytes ->
            runCatching {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                _verified.value = o.optJSONArray("verified").strings().map { it.lowercase() }.toSet()
                pendingFavoritePeerIDs = o.optJSONArray("pendingFavorites").strings().toSet()
            }
        }
        FavoritesPersistenceService.shared.addListener(listener)
        refreshFavorites()
    }

    override fun setFavorite(peerID: String, nickname: String, favorite: Boolean) {
        val key = noiseKeyFor(peerID)
        synchronized(lock) {
            if (key != null) {
                FavoritesPersistenceService.shared.updateFavoriteStatus(key, nickname, favorite)
                pendingFavoritePeerIDs = pendingFavoritePeerIDs - peerID
            } else {
                // Clear any stored relationship found by peer ID, then keep the choice locally.
                FavoritesPersistenceService.shared.getFavoriteStatus(peerID)?.let {
                    FavoritesPersistenceService.shared.updateFavoriteStatus(it.peerNoisePublicKey, nickname, favorite)
                }
                pendingFavoritePeerIDs =
                    if (favorite) pendingFavoritePeerIDs + peerID else pendingFavoritePeerIDs - peerID
            }
            save()
        }
        refreshFavorites()
    }

    override fun setVerified(fingerprint: String, verified: Boolean) {
        val fp = fingerprint.lowercase()
        synchronized(lock) {
            _verified.value = if (verified) _verified.value + fp else _verified.value - fp
            save()
        }
    }

    /** Moves favorites set before the peer's Noise key was known into the shared service. */
    fun promotePending(peerID: String, nickname: String) {
        if (peerID !in synchronized(lock) { pendingFavoritePeerIDs }) return
        if (noiseKeyFor(peerID) == null) return
        setFavorite(peerID, nickname, true)
    }

    fun refreshFavorites() {
        val shared = runCatching { FavoritesPersistenceService.shared.getOurFavorites() }.getOrDefault(emptyList())
        val ids = shared.mapNotNull { rel ->
            rel.peerNoisePublicKey.takeIf { it.size == 32 }?.let(ContactIdentityResolver::peerIdForNoiseKey)
        }.toSet()
        _favorites.value = ids + synchronized(lock) { pendingFavoritePeerIDs }
    }

    override fun close() {
        runCatching { FavoritesPersistenceService.shared.removeListener(listener) }
    }

    private fun save() {
        val o = JSONObject()
            .put("v", 1)
            .put("verified", JSONArray(_verified.value.sorted()))
            .put("pendingFavorites", JSONArray(pendingFavoritePeerIDs.sorted()))
        sealed.write(o.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val FILE_NAME = "contacts.sealed"
        const val KEY_ALIAS = "skein_contacts"
        private const val PURPOSE = "skein-contacts-v1"

        fun open(context: Context, dataDir: File, keyFor: (String) -> ByteArray, noiseKeyFor: (String) -> ByteArray?) =
            DesktopContactsStore(context, SealedFile(File(dataDir, FILE_NAME), keyFor(KEY_ALIAS), PURPOSE), noiseKeyFor)

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }
    }
}
