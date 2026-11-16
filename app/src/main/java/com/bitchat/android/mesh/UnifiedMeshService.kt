package com.bitchat.android.mesh

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.lan.LanController
import com.bitchat.android.linked.LinkCommand
import com.bitchat.android.linked.LinkController
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.wearlink.SecondaryMeshService
import com.bitchat.android.wearlink.WearLinkController
import com.bitchat.android.wifiaware.WifiAwareController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Feature-facing mesh service that hides local transport selection from the rest of the app.
 *
 * BLE remains the canonical origin for broadcast packets when it is enabled so existing BLE mesh
 * behavior and bridge semantics stay intact. Addressed Noise traffic is routed over whichever
 * local transport already has the peer/session, falling back to a connected transport handshake.
 */
class UnifiedMeshService(
    private val context: Context,
    private val bluetooth: BluetoothMeshService
) : MeshService, BluetoothMeshDelegate {

    companion object {
        private const val TAG = "UnifiedMeshService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val powerManager = PowerManager.getInstance(context.applicationContext)
    private var announcementJob: Job? = null

    @Volatile
    private var radiosSuppressed: Boolean = false

    fun setRadiosSuppressed(suppressed: Boolean) {
        if (radiosSuppressed == suppressed) return
        radiosSuppressed = suppressed
        if (suppressed) {
            try { bluetooth.setBleTransportEnabled(false) } catch (_: Exception) { }
            try { WifiAwareController.stop() } catch (_: Exception) { }
            try { LanController.stop() } catch (_: Exception) { }
        } else {
            try { bluetooth.setBleTransportEnabled(isBleEnabled()) } catch (_: Exception) { }
            try { WifiAwareController.startIfPossible() } catch (_: Exception) { }
            try { LanController.startIfPossible(context) } catch (_: Exception) { }
            refreshDelegates()
        }
    }

    override val myPeerID: String
        get() = bluetooth.myPeerID

    override var delegate: MeshDelegate? = null
        set(value) {
            field = value
            refreshDelegates()
        }

    fun refreshDelegates() {
        try { bluetooth.delegate = if (delegate != null) this else null } catch (_: Exception) { }
        try { wifiService()?.delegate = if (delegate != null) this else null } catch (_: Exception) { }
    }

    override fun startServices() {
        try { LinkController.startIfNeeded(context) } catch (e: Exception) {
            Log.w(TAG, "Failed to start linked devices controller: ${e.message}")
        }
        if (!radiosSuppressed && isBleEnabled()) {
            try { bluetooth.startServices() } catch (e: Exception) {
                Log.w(TAG, "Failed to start BLE transport: ${e.message}")
            }
        } else {
            try { bluetooth.setBleTransportEnabled(false) } catch (_: Exception) { }
        }
        if (!radiosSuppressed) {
            try { WifiAwareController.startIfPossible() } catch (e: Exception) {
                Log.w(TAG, "Failed to start Wi-Fi Aware transport: ${e.message}")
            }
            // No-op unless the user turned on the local Wi-Fi mesh and granted its permission.
            try { LanController.startIfPossible(context) } catch (e: Exception) {
                Log.w(TAG, "Failed to start local Wi-Fi transport: ${e.message}")
            }
        }
        try { WearLinkController.startIfPossible(context) } catch (e: Exception) {
            Log.w(TAG, "Failed to start watch link transport: ${e.message}")
        }
        startAnnouncementScheduler()
        refreshDelegates()
    }

    override fun stopServices() {
        announcementJob?.cancel()
        announcementJob = null
        try { bluetooth.stopServices() } catch (_: Exception) { }
        try { WifiAwareController.stop() } catch (_: Exception) { }
        try { WearLinkController.stop() } catch (_: Exception) { }
        try { LanController.stop() } catch (_: Exception) { }
    }

    private fun startAnnouncementScheduler() {
        if (announcementJob?.isActive == true) return
        announcementJob = serviceScope.launch {
            powerManager.profile
                .map { profile ->
                    profile.meshAnnouncementIntervalMs to profile.hasDirectPeers
                }
                .distinctUntilChanged()
                .collectLatest { (intervalMs, hasRecipients) ->
                    if (!hasRecipients) return@collectLatest
                    // Connection-specific paths already send an immediate announce. Begin the
                    // periodic cadence after the configured interval to avoid a transition burst.
                    while (isActive) {
                        delay(intervalMs)
                        if (powerManager.profile.value.hasDirectPeers) sendBroadcastAnnounce()
                    }
                }
            }
    }

    override fun sendMessage(content: String, mentions: List<String>, channel: String?) {
        if (LinkController.forward(LinkCommand.SendPublic(null, content, mentions, channel))) return
        when {
            isBleEnabled() -> bluetooth.sendMessage(content, mentions, channel)
            else -> wifiService()?.sendMessage(content, mentions, channel)
        }
    }

    override fun sendPrivateMessage(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String?
    ) {
        val id = messageID ?: java.util.UUID.randomUUID().toString()
        if (LinkController.forward(LinkCommand.SendPrivate(id, content, recipientPeerID, recipientNickname))) return
        when {
            isBleReady(recipientPeerID) -> bluetooth.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
            isWifiReady(recipientPeerID) -> wifiService()?.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
            isBleConnected(recipientPeerID) || (isBleEnabled() && !isWifiConnected(recipientPeerID)) ->
                bluetooth.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
            else -> wifiService()?.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
        }
    }

    override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        if (LinkController.forward(LinkCommand.ReadReceipt(messageID, recipientPeerID))) return
        when {
            isBleReady(recipientPeerID) -> bluetooth.sendReadReceipt(messageID, recipientPeerID, readerNickname)
            isWifiReady(recipientPeerID) -> wifiService()?.sendReadReceipt(messageID, recipientPeerID, readerNickname)
        }
    }

    override fun sendFavoriteNotification(peerID: String, isFavorite: Boolean) {
        if (LinkController.forward(LinkCommand.SetFavorite(peerID, isFavorite))) return
        val myNpub = try {
            com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub
        } catch (_: Exception) {
            null
        }
        val content = FavoriteControlMessage.encode(isFavorite, myNpub)
        val nickname = getPeerNicknames()[peerID] ?: peerID
        if (hasEstablishedSession(peerID)) {
            sendPrivateMessage(content, peerID, nickname, java.util.UUID.randomUUID().toString())
        }
    }

    override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        when {
            isBleReady(peerID) -> bluetooth.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
            isWifiReady(peerID) -> wifiService()?.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
        }
    }

    override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        when {
            isBleReady(peerID) -> bluetooth.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
            isWifiReady(peerID) -> wifiService()?.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
        }
    }

    override fun sendFileBroadcast(file: BitchatFilePacket) {
        if (LinkController.forward(
                LinkCommand.SendFile(java.util.UUID.randomUUID().toString(), null, file.fileName, file.mimeType, kindForMime(file.mimeType)),
                file.content
            )
        ) return
        when {
            isBleEnabled() -> bluetooth.sendFileBroadcast(file)
            else -> wifiService()?.sendFileBroadcast(file)
        }
    }

    override fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) {
        if (LinkController.forward(
                LinkCommand.SendFile(java.util.UUID.randomUUID().toString(), recipientPeerID, file.fileName, file.mimeType, kindForMime(file.mimeType)),
                file.content
            )
        ) return
        when {
            isBleReady(recipientPeerID) -> bluetooth.sendFilePrivate(recipientPeerID, file)
            isWifiReady(recipientPeerID) -> wifiService()?.sendFilePrivate(recipientPeerID, file)
            isBleConnected(recipientPeerID) || (isBleEnabled() && !isWifiConnected(recipientPeerID)) ->
                bluetooth.sendFilePrivate(recipientPeerID, file)
            else -> wifiService()?.sendFilePrivate(recipientPeerID, file)
        }
    }

    override fun sendVoiceFrame(recipientPeerID: String?, payload: ByteArray) {
        if (recipientPeerID == null) {
            when {
                isBleEnabled() -> bluetooth.sendVoiceFrame(null, payload)
                else -> wifiService()?.sendVoiceFrame(null, payload)
            }
            return
        }
        when {
            isBleReady(recipientPeerID) -> bluetooth.sendVoiceFrame(recipientPeerID, payload)
            isWifiReady(recipientPeerID) -> wifiService()?.sendVoiceFrame(recipientPeerID, payload)
            isBleConnected(recipientPeerID) || (isBleEnabled() && !isWifiConnected(recipientPeerID)) ->
                bluetooth.sendVoiceFrame(recipientPeerID, payload)
            else -> wifiService()?.sendVoiceFrame(recipientPeerID, payload)
        }
    }

    override fun prepareFilePrivate(
        recipientPeerID: String,
        file: BitchatFilePacket,
        transferId: String,
        allowLegacyFallback: Boolean
    ): PrivateMediaPreparation {
        return when {
            isBleReady(recipientPeerID) -> bluetooth.prepareFilePrivate(
                recipientPeerID,
                file,
                transferId,
                allowLegacyFallback
            )
            isWifiReady(recipientPeerID) -> wifiService()?.prepareFilePrivate(
                recipientPeerID,
                file,
                transferId,
                allowLegacyFallback
            ) ?: PrivateMediaPreparation.Rejected("Wi-Fi Aware transport is unavailable")
            isBleConnected(recipientPeerID) || (isBleEnabled() && !isWifiConnected(recipientPeerID)) ->
                bluetooth.prepareFilePrivate(
                    recipientPeerID,
                    file,
                    transferId,
                    allowLegacyFallback
                )
            else -> wifiService()?.prepareFilePrivate(
                recipientPeerID,
                file,
                transferId,
                allowLegacyFallback
            ) ?: PrivateMediaPreparation.Rejected("No local transport is available for this peer")
        }
    }

    override fun cancelFileTransfer(transferId: String): Boolean {
        val bleCancelled = try { bluetooth.cancelFileTransfer(transferId) } catch (_: Exception) { false }
        val wifiCancelled = try { wifiService()?.cancelFileTransfer(transferId) == true } catch (_: Exception) { false }
        return bleCancelled || wifiCancelled
    }

    override fun sendBroadcastAnnounce() {
        if (radiosSuppressed || LinkController.isFollowing()) return
        if (isBleEnabled()) {
            try { bluetooth.sendBroadcastAnnounce() } catch (_: Exception) { }
        }
        try { wifiService()?.sendBroadcastAnnounce() } catch (_: Exception) { }
    }

    override fun sendAnnouncementToPeer(peerID: String) {
        when {
            isBleConnected(peerID) || (isBleEnabled() && !isWifiConnected(peerID)) -> bluetooth.sendAnnouncementToPeer(peerID)
            else -> wifiService()?.sendAnnouncementToPeer(peerID)
        }
    }

    override fun getPeerNicknames(): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        LinkController.mirrorPeers.value?.forEach { merged[it.peerID] = it.nickname }
        try { merged.putAll(wifiService()?.getPeerNicknames().orEmpty()) } catch (_: Exception) { }
        try { merged.putAll(bluetooth.getPeerNicknames()) } catch (_: Exception) { }
        return merged
    }

    override fun getPeerRSSI(): Map<String, Int> {
        val merged = linkedMapOf<String, Int>()
        try { merged.putAll(wifiService()?.getPeerRSSI().orEmpty()) } catch (_: Exception) { }
        try { merged.putAll(bluetooth.getPeerRSSI()) } catch (_: Exception) { }
        return merged
    }

    override fun getActivePeerCount(): Int {
        return mergedPeerIDs().filter { it != myPeerID }.distinct().size
    }

    override fun hasEstablishedSession(peerID: String): Boolean {
        val mirrored = LinkController.mirrorPeers.value?.firstOrNull { it.peerID == peerID }
        if (mirrored != null && mirrored.hasSession) return true
        return isBleReady(peerID) || isWifiReady(peerID)
    }

    override fun getSessionState(peerID: String): NoiseSession.NoiseSessionState {
        val mirrored = LinkController.mirrorPeers.value?.firstOrNull { it.peerID == peerID }
        if (mirrored != null && mirrored.hasSession) return NoiseSession.NoiseSessionState.Established
        val bleState = try { bluetooth.getSessionState(peerID) } catch (_: Exception) { NoiseSession.NoiseSessionState.Uninitialized }
        val wifiState = try { wifiService()?.getSessionState(peerID) } catch (_: Exception) { null }
        return when {
            bleState is NoiseSession.NoiseSessionState.Established -> bleState
            wifiState is NoiseSession.NoiseSessionState.Established -> wifiState
            bleState is NoiseSession.NoiseSessionState.Handshaking -> bleState
            wifiState is NoiseSession.NoiseSessionState.Handshaking -> wifiState
            bleState !is NoiseSession.NoiseSessionState.Uninitialized -> bleState
            wifiState != null -> wifiState
            else -> bleState
        }
    }

    override fun initiateNoiseHandshake(peerID: String) {
        when {
            isBleConnected(peerID) -> bluetooth.initiateNoiseHandshake(peerID)
            isWifiConnected(peerID) -> wifiService()?.initiateNoiseHandshake(peerID)
            isBleEnabled() -> bluetooth.initiateNoiseHandshake(peerID)
            else -> wifiService()?.initiateNoiseHandshake(peerID)
        }
    }

    override fun getPeerFingerprint(peerID: String): String? {
        return try { bluetooth.getPeerFingerprint(peerID) } catch (_: Exception) { null }
            ?: try { wifiService()?.getPeerFingerprint(peerID) } catch (_: Exception) { null }
    }

    override fun getPeerInfo(peerID: String): PeerInfo? {
        val mirrored = LinkController.mirrorPeers.value?.firstOrNull { it.peerID == peerID }
        if (mirrored != null) {
            val noiseKey = mirrored.noiseKeyHex?.let { hex ->
                if (hex.length % 2 == 0) runCatching {
                    ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                }.getOrNull() else null
            }
            return PeerInfo(
                id = mirrored.peerID,
                nickname = mirrored.nickname,
                isConnected = mirrored.connected,
                isDirectConnection = mirrored.connected,
                noisePublicKey = noiseKey,
                signingPublicKey = null,
                isVerifiedNickname = false,
                lastSeen = System.currentTimeMillis()
            )
        }
        val ble = try { bluetooth.getPeerInfo(peerID) } catch (_: Exception) { null }
        val wifi = try { wifiService()?.getPeerInfo(peerID) } catch (_: Exception) { null }
        return when {
            ble?.isConnected == true && hasEstablishedSessionOnBluetooth(peerID) -> ble
            wifi?.isConnected == true && wifiService()?.hasEstablishedSession(peerID) == true -> wifi
            ble?.isConnected == true -> ble
            wifi?.isConnected == true -> wifi
            else -> ble ?: wifi
        }
    }

    override fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean {
        val bleUpdated = try {
            bluetooth.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)
        } catch (_: Exception) {
            false
        }
        val wifiUpdated = try {
            wifiService()?.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified) == true
        } catch (_: Exception) {
            false
        }
        return bleUpdated || wifiUpdated
    }

    override fun getIdentityFingerprint(): String = bluetooth.getIdentityFingerprint()

    override fun getStaticNoisePublicKey(): ByteArray? {
        return bluetooth.getStaticNoisePublicKey() ?: wifiService()?.getStaticNoisePublicKey()
    }

    override fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return hasEstablishedSession(peerID)
    }

    override fun getEncryptedPeers(): List<String> {
        val encrypted = linkedSetOf<String>()
        try { encrypted.addAll(bluetooth.getEncryptedPeers()) } catch (_: Exception) { }
        try { encrypted.addAll(wifiService()?.getEncryptedPeers().orEmpty()) } catch (_: Exception) { }
        mergedPeerIDs().filterTo(encrypted) { hasEstablishedSession(it) }
        return encrypted.toList()
    }

    override fun getDeviceAddressForPeer(peerID: String): String? {
        return try { bluetooth.getDeviceAddressForPeer(peerID) } catch (_: Exception) { null }
            ?: try { wifiService()?.getDeviceAddressForPeer(peerID) } catch (_: Exception) { null }
    }

    override fun getDeviceAddressToPeerMapping(): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        try { merged.putAll(wifiService()?.getDeviceAddressToPeerMapping().orEmpty()) } catch (_: Exception) { }
        try { merged.putAll(bluetooth.getDeviceAddressToPeerMapping()) } catch (_: Exception) { }
        return merged
    }

    override fun printDeviceAddressesForPeers(): String {
        return buildString {
            appendLine(bluetooth.printDeviceAddressesForPeers())
            wifiService()?.let {
                appendLine()
                appendLine(it.printDeviceAddressesForPeers())
            }
        }
    }

    override fun getDebugStatus(): String {
        return buildString {
            appendLine("=== Unified Mesh Service Debug Status ===")
            appendLine("My Peer ID: $myPeerID")
            appendLine("Merged Peers: ${mergedPeerIDs().joinToString(", ")}")
            appendLine()
            appendLine(bluetooth.getDebugStatus())
            wifiService()?.let {
                appendLine()
                appendLine(it.getDebugStatus())
            }
        }
    }

    override fun clearAllInternalData() {
        try { bluetooth.clearAllInternalData() } catch (_: Exception) { }
        try { wifiService()?.clearAllInternalData() } catch (_: Exception) { }
    }

    override fun clearAllEncryptionData(): Boolean {
        val bluetoothCleared = try { bluetooth.clearAllEncryptionData() } catch (_: Exception) { false }
        val wifiCleared = try { wifiService()?.clearAllEncryptionData() ?: true } catch (_: Exception) { false }
        return bluetoothCleared && wifiCleared
    }

    override fun didReceiveMessage(message: BitchatMessage) {
        delegate?.didReceiveMessage(message)
    }

    override fun didUpdatePeerList(peers: List<String>) {
        delegate?.didUpdatePeerList(mergedPeerIDs().ifEmpty { peers.distinct() })
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        delegate?.didReceiveChannelLeave(channel, fromPeer)
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        delegate?.didReceiveDeliveryAck(messageID, recipientPeerID)
    }

    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        delegate?.didReceiveReadReceipt(messageID, recipientPeerID)
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        delegate?.didReceiveVerifyChallenge(peerID, payload, timestampMs)
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        delegate?.didReceiveVerifyResponse(peerID, payload, timestampMs)
    }

    override fun didResolvePrivateMediaPolicy(peerID: String) {
        delegate?.didResolvePrivateMediaPolicy(peerID)
    }

    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return delegate?.decryptChannelMessage(encryptedContent, channel)
    }

    override fun getNickname(): String? = delegate?.getNickname()

    override fun isFavorite(peerID: String): Boolean = delegate?.isFavorite(peerID) ?: false

    private fun mergedPeerIDs(): List<String> {
        val ids = linkedSetOf<String>()
        try { ids.addAll(com.bitchat.android.services.AppStateStore.peers.value) } catch (_: Exception) { }
        LinkController.mirrorPeers.value?.forEach { ids.add(it.peerID) }
        try { ids.addAll(bluetooth.getPeerNicknames().keys) } catch (_: Exception) { }
        try { ids.addAll(wifiService()?.getPeerNicknames()?.keys.orEmpty()) } catch (_: Exception) { }
        return ids.toList()
    }

    private fun kindForMime(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "image"
        mimeType.startsWith("audio/") -> "audio"
        else -> "file"
    }

    /**
     * Wi-Fi Aware, the local Wi-Fi mesh and the paired-watch link, presented as one secondary
     * transport so the BLE-versus-other selection below stays a two-way choice. See [SecondaryMeshService].
     */
    private val secondaryTransports = SecondaryMeshService(
        wifi = { WifiAwareController.getService() },
        wear = { WearLinkController.getService() },
        lan = { LanController.getService() }
    )

    private fun wifiService(): MeshService? {
        return try {
            secondaryTransports.takeIf { it.hasAny() }?.also { service ->
                if (delegate != null && service.delegate !== this) {
                    service.delegate = this
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isBleEnabled(): Boolean {
        return try {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().bleEnabled.value
        } catch (_: Exception) {
            try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        }
    }

    private fun isBleConnected(peerID: String): Boolean {
        return try { bluetooth.getPeerInfo(peerID)?.isConnected == true } catch (_: Exception) { false }
    }

    private fun isWifiConnected(peerID: String): Boolean {
        return try { wifiService()?.getPeerInfo(peerID)?.isConnected == true } catch (_: Exception) { false }
    }

    private fun isBleReady(peerID: String): Boolean {
        return isBleConnected(peerID) && hasEstablishedSessionOnBluetooth(peerID)
    }

    private fun isWifiReady(peerID: String): Boolean {
        return try {
            val wifi = wifiService()
            wifi?.getPeerInfo(peerID)?.isConnected == true && wifi.hasEstablishedSession(peerID)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasEstablishedSessionOnBluetooth(peerID: String): Boolean {
        return try { bluetooth.hasEstablishedSession(peerID) } catch (_: Exception) { false }
    }
}
