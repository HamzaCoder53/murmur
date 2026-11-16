package com.bitchat.watch.ui

import android.content.Context
import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.favorites.FavoritesChangeListener
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.watch.mesh.WearMeshService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class FavoriteIndicator {
    None,
    FavoritedUs,
    Favorite
}

data class WearPeerIdentitySnapshot(
    val isFavorite: Boolean,
    val theyFavoritedUs: Boolean,
    val favoriteIndicator: FavoriteIndicator,
    val fingerprint: String?,
    val isVerified: Boolean
)

fun favoriteIndicator(
    isFavorite: Boolean,
    theyFavoritedUs: Boolean
): FavoriteIndicator = when {
    isFavorite -> FavoriteIndicator.Favorite
    theyFavoritedUs -> FavoriteIndicator.FavoritedUs
    else -> FavoriteIndicator.None
}

/**
 * Process-wide bridge between the shared identity stores and the Watch Compose UI.
 *
 * Favorites are keyed by the authenticated Noise public key, while screens navigate with the
 * short mesh peer ID. The shared persistence service resolves that mapping and notifies this
 * object whenever either side changes the relationship.
 */
object WearPeerIdentityState : FavoritesChangeListener {
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    @Volatile
    private var initialized = false

    private lateinit var identityManager: SecureIdentityStateManager

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            FavoritesPersistenceService.initialize(context.applicationContext)
            identityManager = SecureIdentityStateManager(context.applicationContext)
            FavoritesPersistenceService.shared.addListener(this)
            initialized = true
        }
        publishChange()
    }

    fun snapshot(peerID: String, mesh: WearMeshService?): WearPeerIdentitySnapshot {
        check(initialized) { "WearPeerIdentityState must be initialized by the application" }
        val relationship = relationship(peerID, mesh)
        val fingerprint = mesh?.getPeerFingerprint(peerID)
            ?: relationship?.peerNoisePublicKey?.let(identityManager::generateFingerprint)
        val isVerified = fingerprint != null &&
            identityManager.getVerifiedFingerprints().any {
                it.equals(fingerprint, ignoreCase = true)
            }
        val isFavorite = relationship?.isFavorite == true
        val theyFavoritedUs = relationship?.theyFavoritedUs == true
        return WearPeerIdentitySnapshot(
            isFavorite = isFavorite,
            theyFavoritedUs = theyFavoritedUs,
            favoriteIndicator = favoriteIndicator(isFavorite, theyFavoritedUs),
            fingerprint = fingerprint,
            isVerified = isVerified
        )
    }

    fun setFavorite(
        peerID: String,
        isFavorite: Boolean,
        mesh: WearMeshService
    ): Boolean {
        check(initialized) { "WearPeerIdentityState must be initialized by the application" }
        if (mesh.codeLinkFollowing) {
            // Linked by code and following the phone: the phone favorites.
            val keyHex = com.bitchat.watch.link.CodeLink.mirrorPeers.value
                ?.firstOrNull { it.peerID == peerID }?.noiseKeyHex ?: return false
            val key = com.bitchat.android.services.ContactIdentityResolver.bytesFromHex(keyHex) ?: return false
            if (!com.bitchat.watch.link.CodeLink.forward(
                    com.bitchat.android.linked.LinkCommand.SetFavorite(peerID, isFavorite)
                )
            ) return false
            FavoritesPersistenceService.shared.updateFavoriteStatus(
                noisePublicKey = key,
                nickname = mesh.getPeerNickname(peerID) ?: peerID.take(8),
                isFavorite = isFavorite
            )
            return true
        }
        if (mesh.isMirrored) {
            // Linked and next to the phone: the phone favorites (it is the one on the mesh).
            val key = mesh.linkedClient.noiseKey(peerID) ?: return false
            if (!mesh.linkedClient.setFavorite(peerID, isFavorite)) return false
            FavoritesPersistenceService.shared.updateFavoriteStatus(
                noisePublicKey = key,
                nickname = mesh.getPeerNickname(peerID) ?: peerID.take(8),
                isFavorite = isFavorite
            )
            return true
        }
        val peerInfo = mesh.getPeerInfo(peerID) ?: return false
        val noisePublicKey = peerInfo.noisePublicKey ?: return false
        val nickname = mesh.getPeerNickname(peerID)
            ?: peerInfo.nickname.takeIf(String::isNotBlank)
            ?: peerID.take(8)

        FavoritesPersistenceService.shared.updateFavoriteStatus(
            noisePublicKey = noisePublicKey,
            nickname = nickname,
            isFavorite = isFavorite
        )
        mesh.sendFavoriteNotification(peerID, isFavorite)
        return true
    }

    fun setVerified(
        peerID: String,
        verified: Boolean,
        mesh: WearMeshService?
    ): Boolean {
        check(initialized) { "WearPeerIdentityState must be initialized by the application" }
        val fingerprint = snapshot(peerID, mesh).fingerprint ?: return false
        identityManager.setVerifiedFingerprint(fingerprint.lowercase(), verified)
        publishChange()
        return true
    }

    fun myFingerprint(mesh: WearMeshService?): String? = mesh?.getIdentityFingerprint()

    override fun onFavoriteChanged(noiseKeyHex: String) {
        publishChange()
    }

    override fun onAllCleared() {
        publishChange()
    }

    private fun relationship(
        peerID: String,
        mesh: WearMeshService?
    ): FavoriteRelationship? {
        FavoritesPersistenceService.shared.getFavoriteStatus(peerID)?.let { return it }
        val noisePublicKey = mesh?.getPeerInfo(peerID)?.noisePublicKey
            ?: mesh?.linkedClient?.noiseKey(peerID)
            ?: return null
        return FavoritesPersistenceService.shared.getFavoriteStatus(noisePublicKey)
    }

    private fun publishChange() {
        _revision.update { it + 1L }
    }
}
