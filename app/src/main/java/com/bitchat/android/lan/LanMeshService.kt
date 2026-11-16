package com.bitchat.android.lan

import android.content.Context
import android.util.Log
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.mesh.DirectLinkAnnouncementPolicy
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.mesh.PrivateMediaPreparation
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Local Wi-Fi mesh: a transport beside BLE, Wi-Fi Aware and the watch link that reaches Skein
 * peers on the same Wi-Fi network, with or without internet. Built like the Wi-Fi Aware and watch
 * link services: its own [MeshCore] over a [LanTransport], registered with
 * [TransportBridgeService] so LAN peers and BLE peers relay for each other.
 *
 * LAN peers are ordinary mesh peers. Announcements, signatures and Noise sessions are validated by
 * MeshCore exactly as for BLE; being on the same network grants nothing.
 */
class LanMeshService(private val context: Context) : MeshService, TransportBridgeService.TransportLayer {

    private companion object {
        const val TAG = "LanMeshService"
        const val ID = LanTransport.TRANSPORT_ID
        val MAX_TTL: UByte = AppConstants.MESSAGE_TTL_HOPS
    }

    // Same persisted identity as the BLE stack; Noise sessions are kept per transport, exactly as
    // the Wi-Fi Aware service does.
    private val encryptionService = EncryptionService(context)
    override val myPeerID: String = encryptionService.getIdentityFingerprint().take(16)

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val transport = LanTransport(serviceScope)
    private val network = LanNetworkManager(context, serviceScope, transport)
    private val meshCore: MeshCore

    @Volatile
    private var isActive = false

    @Volatile
    private var greetJob: Job? = null

    // Posts DM notifications when a LAN message arrives with no UI attached.
    private val serviceNotificationManager = com.bitchat.android.ui.NotificationManager(
        context.applicationContext,
        androidx.core.app.NotificationManagerCompat.from(context.applicationContext)
    )

    override var delegate: MeshDelegate? = null
        set(value) {
            field = value
            meshCore.delegate = value
            meshCore.refreshPeerList()
        }

    init {
        // The BLE stack owns the shared gossip manager; make sure it exists before reading it.
        MeshServiceHolder.getOrCreate(context)
        val sharedGossip = MeshServiceHolder.sharedGossipSyncManager
        encryptionService.onSessionEstablished = { peerID ->
            try {
                com.bitchat.android.services.MessageRouter.tryGetInstance()?.onSessionEstablished(peerID)
            } catch (_: Exception) { }
        }
        meshCore = MeshCore(
            context = context.applicationContext,
            scope = serviceScope,
            transport = transport,
            encryptionService = encryptionService,
            myPeerID = myPeerID,
            maxTtl = MAX_TTL,
            sharedGossipManager = sharedGossip,
            gossipConfigProvider = object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity(): Int = 500
                override fun gcsMaxBytes(): Int = 400
                override fun gcsTargetFpr(): Double = 0.01
            },
            hooks = MeshCore.Hooks(
                onMessageReceived = { message -> handleMessageReceived(message) },
                onAnnounceProcessed = { routed, _ ->
                    routed.peerID?.let { peerID ->
                        // Only a validated full-TTL announce binds a peer to a LAN connection.
                        DirectLinkAnnouncementPolicy.observationFor(routed, MAX_TTL)?.let { obs ->
                            if (transport.bindPeer(obs.relayAddress, obs.peerID)) {
                                meshCore.setDirectConnection(obs.peerID, true)
                            }
                        }
                        try {
                            meshCore.gossipSyncManager.scheduleInitialSyncToPeer(peerID, 1_000)
                        } catch (_: Exception) { }
                    }
                },
                announcementNicknameProvider = {
                    try {
                        com.bitchat.android.services.NicknameProvider.getNickname(context, myPeerID)
                    } catch (_: Exception) {
                        null
                    }
                },
                leavePayloadProvider = {
                    (delegate?.getNickname() ?: myPeerID).toByteArray(Charsets.UTF_8)
                }
            )
        )
        transport.attachFragmentManager(meshCore.fragmentManager)
        transport.listener = object : LanTransport.Listener {
            override fun onPacket(packet: BitchatPacket, peerID: String, linkAddress: String) {
                meshCore.processIncoming(packet, peerID, linkAddress, linkAddress)
            }

            override fun onLinkUp(linkAddress: String) {
                scheduleGreeting()
            }

            override fun onPeerUnreachable(peerID: String) {
                meshCore.setDirectConnection(peerID, false)
                try { meshCore.refreshPeerList() } catch (_: Exception) { }
                removeIfStillGone(peerID)
            }
        }
    }

    /**
     * A new neighbour only treats us as direct after a full-TTL announce on its connection, so
     * greet shortly after links come up, coalescing a burst of new connections into one announce.
     */
    private fun scheduleGreeting() {
        if (!isActive || greetJob?.isActive == true) return
        greetJob = serviceScope.launch {
            delay(300)
            meshCore.sendBroadcastAnnounce()
            // Catch up on public messages sent while the peer was away.
            try { meshCore.gossipSyncManager.scheduleInitialSync(1_500) } catch (_: Exception) { }
        }
    }

    /** Same grace period a Bluetooth disconnect gets before the peer is removed. */
    private fun removeIfStillGone(peerID: String) {
        serviceScope.launch {
            delay(AppConstants.Mesh.PEER_DISCONNECT_GRACE_MS)
            if (transport.getDeviceAddressForPeer(peerID) == null) {
                try { meshCore.removePeer(peerID) } catch (_: Exception) { }
            }
        }
    }

    fun isRunning(): Boolean = isActive

    private fun handleMessageReceived(message: BitchatMessage): Boolean {
        // Same admission rule as BLE and Wi-Fi Aware: a message rejected during panic or as a
        // duplicate must not surface.
        if (!com.bitchat.android.services.IncomingMessageAdmission.admitToAppState(message)) return false

        if (delegate == null && message.isPrivate) {
            try {
                val senderPeerID = message.senderPeerID
                if (senderPeerID != null) {
                    val nick = try { meshCore.getPeerNickname(senderPeerID) } catch (_: Exception) { null }
                        ?: senderPeerID
                    val preview = com.bitchat.android.ui.NotificationTextUtils.buildPrivateMessagePreview(message)
                    serviceNotificationManager.setAppBackgroundState(true)
                    serviceNotificationManager.showPrivateMessageNotification(senderPeerID, nick, preview)
                }
            } catch (_: Exception) { }
        }
        return true
    }

    // --- TransportBridgeService.TransportLayer: traffic arriving from the other transports ---

    override fun send(packet: RoutedPacket) {
        meshCore.sendFromBridge(packet)
    }

    override suspend fun sendAndReport(packet: RoutedPacket): Boolean =
        meshCore.sendFromBridgeAndReport(packet)

    override fun sendToPeer(peerID: String, packet: BitchatPacket) {
        transport.sendPacketToPeer(peerID, packet)
    }

    // --- Lifecycle ---

    override fun startServices() {
        if (isActive) return
        isActive = true
        TransportBridgeService.register(ID, this)
        meshCore.startCore()
        MeshServiceHolder.startSharedGossip(ID)
        network.start()
        Log.i(TAG, "Local Wi-Fi mesh started")
    }

    override fun stopServices() {
        if (!isActive) return
        isActive = false
        greetJob?.cancel()
        greetJob = null
        TransportBridgeService.unregister(ID)
        MeshServiceHolder.stopSharedGossip(ID)
        AppStateStore.clearTransportPeers(ID)
        AppStateStore.clearTransportDirectPeers(ID)
        try { meshCore.sendLeaveAnnouncement() } catch (_: Exception) { }
        // A stopped instance is discarded, so tear everything down once the leave has gone out.
        serviceScope.launch {
            delay(200)
            network.stop()
            meshCore.stopCore()
            meshCore.shutdown()
            serviceScope.cancel()
        }
        Log.i(TAG, "Local Wi-Fi mesh stopped")
    }

    // --- MeshService: straight delegation to this transport's MeshCore ---

    override fun sendMessage(content: String, mentions: List<String>, channel: String?) =
        meshCore.sendMessage(content, mentions, channel)

    override fun sendPrivateMessage(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String?
    ) = meshCore.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)

    override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) =
        meshCore.sendReadReceipt(messageID, recipientPeerID, readerNickname)

    override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) =
        meshCore.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)

    override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) =
        meshCore.sendVerifyResponse(peerID, noiseKeyHex, nonceA)

    override fun sendFileBroadcast(file: BitchatFilePacket) = meshCore.sendFileBroadcast(file)

    override fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) =
        meshCore.sendFilePrivate(recipientPeerID, file)

    override fun sendVoiceFrame(recipientPeerID: String?, payload: ByteArray) =
        meshCore.sendVoiceFrame(recipientPeerID, payload)

    override fun prepareFilePrivate(
        recipientPeerID: String,
        file: BitchatFilePacket,
        transferId: String,
        allowLegacyFallback: Boolean
    ): PrivateMediaPreparation =
        meshCore.prepareFilePrivate(recipientPeerID, file, transferId, allowLegacyFallback)

    override fun cancelFileTransfer(transferId: String): Boolean = meshCore.cancelFileTransfer(transferId)

    override fun sendBroadcastAnnounce() = meshCore.sendBroadcastAnnounce()

    override fun sendAnnouncementToPeer(peerID: String) = meshCore.sendAnnouncementToPeer(peerID)

    override fun getPeerNicknames(): Map<String, String> = meshCore.getPeerNicknames()

    override fun getPeerRSSI(): Map<String, Int> = meshCore.getPeerRSSI()

    override fun getActivePeerCount(): Int = meshCore.getActivePeerCount()

    override fun hasEstablishedSession(peerID: String): Boolean = meshCore.hasEstablishedSession(peerID)

    override fun getSessionState(peerID: String) = meshCore.getSessionState(peerID)

    override fun initiateNoiseHandshake(peerID: String) = meshCore.initiateNoiseHandshake(peerID)

    override fun getPeerFingerprint(peerID: String): String? = meshCore.getPeerFingerprint(peerID)

    override fun getPeerInfo(peerID: String): PeerInfo? = meshCore.getPeerInfo(peerID)

    override fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean = meshCore.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)

    override fun getIdentityFingerprint(): String = meshCore.getIdentityFingerprint()

    override fun getStaticNoisePublicKey(): ByteArray? = meshCore.getStaticNoisePublicKey()

    override fun shouldShowEncryptionIcon(peerID: String): Boolean = meshCore.shouldShowEncryptionIcon(peerID)

    override fun getEncryptedPeers(): List<String> = meshCore.getEncryptedPeers()

    override fun getDeviceAddressForPeer(peerID: String): String? = meshCore.getDeviceAddressForPeer(peerID)

    override fun getDeviceAddressToPeerMapping(): Map<String, String> =
        meshCore.getDeviceAddressToPeerMapping()

    override fun printDeviceAddressesForPeers(): String =
        getDeviceAddressToPeerMapping().entries.joinToString("\n") { "${it.key} -> ${it.value}" }

    override fun getDebugStatus(): String = meshCore.getDebugStatus(
        transportInfo = transport.getTransportDebugInfo(),
        deviceMap = getDeviceAddressToPeerMapping(),
        title = "Local Wi-Fi Mesh Debug Status"
    )

    override fun clearAllInternalData() = meshCore.clearAllInternalData()

    override fun clearAllEncryptionData(): Boolean = meshCore.clearAllEncryptionData()
}
