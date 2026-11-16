package com.bitchat.android.wearlink

import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.mesh.PrivateMediaPreparation
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.noise.NoiseSession

/**
 * Presents the non-BLE transports (Wi-Fi Aware, the local Wi-Fi mesh and the watch link) to
 * UnifiedMeshService as one secondary [MeshService].
 *
 * UnifiedMeshService was written to choose between exactly two transports, BLE and "the other
 * one". Folding the watch link in here keeps that file's per-peer selection logic untouched: this
 * class answers each per-peer question from whichever secondary transport actually reaches the
 * peer, and fans out or merges everything else.
 *
 * Start and stop are no-ops: each transport is started and stopped by its own controller.
 */
class SecondaryMeshService(
    private val wifi: () -> MeshService?,
    private val wear: () -> MeshService?,
    private val lan: () -> MeshService? = { null }
) : MeshService {

    /** The watch link first: a peer bound to it is one hop away and always reachable there. */
    private fun all(): List<MeshService> = listOfNotNull(wear(), wifi(), lan())

    fun hasAny(): Boolean = wear() != null || wifi() != null || lan() != null

    /**
     * The transport to use for [peerID]: one with a live session, else one that at least sees the
     * peer connected, else any. Session and peer-info queries for a peer must all be answered by
     * the same transport, so every per-peer method goes through here.
     */
    private fun forPeer(peerID: String): MeshService? {
        val services = all()
        return services.firstOrNull { it.isConnectedTo(peerID) && it.hasEstablishedSession(peerID) }
            ?: services.firstOrNull { it.isConnectedTo(peerID) }
            ?: wifi()
            ?: lan()
            ?: wear()
    }

    private fun MeshService.isConnectedTo(peerID: String): Boolean =
        try { getPeerInfo(peerID)?.isConnected == true } catch (_: Exception) { false }

    /** Broadcast-style sends start on one transport; the bridge carries them to the rest. */
    private fun origin(): MeshService? = wifi() ?: lan() ?: wear()

    override val myPeerID: String
        get() = all().firstOrNull()?.myPeerID.orEmpty()

    /**
     * Reads as the shared delegate only when every running transport has it, so a transport that
     * started later shows up as a mismatch and gets the delegate assigned.
     */
    override var delegate: MeshDelegate?
        get() = all().map { it.delegate }.distinct().singleOrNull()
        set(value) {
            all().forEach { if (it.delegate !== value) it.delegate = value }
        }

    override fun startServices() = Unit

    override fun stopServices() = Unit

    override fun sendMessage(content: String, mentions: List<String>, channel: String?) {
        origin()?.sendMessage(content, mentions, channel)
    }

    override fun sendPrivateMessage(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String?
    ) {
        forPeer(recipientPeerID)?.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
    }

    override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        forPeer(recipientPeerID)?.sendReadReceipt(messageID, recipientPeerID, readerNickname)
    }

    override fun sendDeliveryAck(messageID: String, recipientPeerID: String) {
        forPeer(recipientPeerID)?.sendDeliveryAck(messageID, recipientPeerID)
    }

    override fun sendFavoriteNotification(peerID: String, isFavorite: Boolean) {
        forPeer(peerID)?.sendFavoriteNotification(peerID, isFavorite)
    }

    override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        forPeer(peerID)?.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
    }

    override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        forPeer(peerID)?.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
    }

    override fun sendFileBroadcast(file: BitchatFilePacket) {
        origin()?.sendFileBroadcast(file)
    }

    override fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) {
        forPeer(recipientPeerID)?.sendFilePrivate(recipientPeerID, file)
    }

    override fun sendVoiceFrame(recipientPeerID: String?, payload: ByteArray) {
        val target = if (recipientPeerID == null) origin() else forPeer(recipientPeerID)
        target?.sendVoiceFrame(recipientPeerID, payload)
    }

    override fun prepareFilePrivate(
        recipientPeerID: String,
        file: BitchatFilePacket,
        transferId: String,
        allowLegacyFallback: Boolean
    ): PrivateMediaPreparation =
        forPeer(recipientPeerID)?.prepareFilePrivate(recipientPeerID, file, transferId, allowLegacyFallback)
            ?: PrivateMediaPreparation.Rejected("No secondary transport is available for this peer")

    override fun cancelFileTransfer(transferId: String): Boolean =
        all().map { it.cancelFileTransfer(transferId) }.any { it }

    /** Every transport announces for itself: a neighbour only counts a full-TTL announce as direct. */
    override fun sendBroadcastAnnounce() {
        all().forEach { it.sendBroadcastAnnounce() }
    }

    override fun sendAnnouncementToPeer(peerID: String) {
        forPeer(peerID)?.sendAnnouncementToPeer(peerID)
    }

    override fun getPeerNicknames(): Map<String, String> =
        all().asReversed().fold(LinkedHashMap()) { merged, service -> merged.apply { putAll(service.getPeerNicknames()) } }

    override fun getPeerRSSI(): Map<String, Int> =
        all().asReversed().fold(LinkedHashMap()) { merged, service -> merged.apply { putAll(service.getPeerRSSI()) } }

    override fun getActivePeerCount(): Int = getPeerNicknames().keys.count { it != myPeerID }

    override fun hasEstablishedSession(peerID: String): Boolean =
        forPeer(peerID)?.hasEstablishedSession(peerID) == true

    override fun getSessionState(peerID: String): NoiseSession.NoiseSessionState =
        forPeer(peerID)?.getSessionState(peerID) ?: NoiseSession.NoiseSessionState.Uninitialized

    override fun initiateNoiseHandshake(peerID: String) {
        forPeer(peerID)?.initiateNoiseHandshake(peerID)
    }

    override fun getPeerFingerprint(peerID: String): String? =
        all().firstNotNullOfOrNull { it.getPeerFingerprint(peerID) }

    override fun getPeerInfo(peerID: String): PeerInfo? =
        forPeer(peerID)?.getPeerInfo(peerID) ?: all().firstNotNullOfOrNull { it.getPeerInfo(peerID) }

    override fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean = all()
        .map { it.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified) }
        .any { it }

    override fun getIdentityFingerprint(): String = all().firstOrNull()?.getIdentityFingerprint().orEmpty()

    override fun getStaticNoisePublicKey(): ByteArray? =
        all().firstNotNullOfOrNull { it.getStaticNoisePublicKey() }

    override fun shouldShowEncryptionIcon(peerID: String): Boolean =
        all().any { it.shouldShowEncryptionIcon(peerID) }

    override fun getEncryptedPeers(): List<String> = all().flatMap { it.getEncryptedPeers() }.distinct()

    override fun getDeviceAddressForPeer(peerID: String): String? =
        all().firstNotNullOfOrNull { it.getDeviceAddressForPeer(peerID) }

    override fun getDeviceAddressToPeerMapping(): Map<String, String> =
        all().asReversed().fold(LinkedHashMap()) { merged, service ->
            merged.apply { putAll(service.getDeviceAddressToPeerMapping()) }
        }

    override fun printDeviceAddressesForPeers(): String =
        all().map { it.printDeviceAddressesForPeers() }.filter { it.isNotBlank() }.joinToString("\n")

    override fun getDebugStatus(): String = all().joinToString("\n\n") { it.getDebugStatus() }

    override fun clearAllInternalData() {
        all().forEach { it.clearAllInternalData() }
    }

    /** Every transport must clear; none running counts as cleared. */
    override fun clearAllEncryptionData(): Boolean =
        all().map { it.clearAllEncryptionData() }.all { it }
}
