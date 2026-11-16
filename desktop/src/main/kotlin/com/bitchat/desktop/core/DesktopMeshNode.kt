package com.bitchat.desktop.core

import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.mesh.DirectLinkAnnouncementPolicy
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.util.AppConstants
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One desktop mesh endpoint: the shared [MeshCore] (routing, signatures, Noise, fragmentation,
 * gossip sync) over one [MeshTransport], built the same way as the phone's LanMeshService.
 * Transports feed received packets into [receive]. UI state is exposed as immutable StateFlows.
 */
class DesktopMeshNode(
    val identity: DesktopIdentity,
    private val transport: MeshTransport,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val maxTtl: UByte = AppConstants.MESSAGE_TTL_HOPS

    private val _publicMessages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val publicMessages: StateFlow<List<BitchatMessage>> = _publicMessages.asStateFlow()

    private val _privateMessages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val privateMessages: StateFlow<List<BitchatMessage>> = _privateMessages.asStateFlow()

    private val _peers = MutableStateFlow<List<String>>(emptyList())
    val peers: StateFlow<List<String>> = _peers.asStateFlow()

    val peerID: String get() = identity.peerID

    init {
        FavoritesPersistenceService.initialize(identity.context)
    }

    private val core: MeshCore = MeshCore(
        context = identity.context,
        scope = scope,
        transport = transport,
        encryptionService = identity.encryptionService,
        myPeerID = identity.peerID,
        maxTtl = maxTtl,
        sharedGossipManager = null,
        gossipConfigProvider = object : GossipSyncManager.ConfigProvider {
            override fun seenCapacity(): Int = 500
            override fun gcsMaxBytes(): Int = 400
            override fun gcsTargetFpr(): Double = 0.01
        },
        hooks = MeshCore.Hooks(
            onAnnounceProcessed = { routed, _ ->
                // Only a validated full-TTL announce marks a peer as directly connected.
                DirectLinkAnnouncementPolicy.observationFor(routed, maxTtl)?.let { obs ->
                    core.setDirectConnection(obs.peerID, true)
                    core.gossipSyncManager.scheduleInitialSyncToPeer(obs.peerID, 1_000)
                }
            },
            announcementNicknameProvider = { identity.nickname },
            leavePayloadProvider = { identity.nickname.toByteArray(Charsets.UTF_8) },
        ),
    )

    init {
        core.delegate = object : MeshDelegate {
            override fun didReceiveMessage(message: BitchatMessage) {
                if (message.isPrivate) appendPrivate(message) else appendPublic(message)
            }

            override fun didUpdatePeerList(peers: List<String>) {
                _peers.value = peers
            }

            override fun didReceiveChannelLeave(channel: String, fromPeer: String) = Unit
            override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) = Unit
            override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) = Unit
            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
            override fun getNickname(): String = identity.nickname
            override fun isFavorite(peerID: String): Boolean = false
        }
    }

    /** Entry point for transports: one decoded packet received over [linkAddress]. */
    fun receive(packet: BitchatPacket, senderPeerID: String, linkAddress: String) {
        core.processIncoming(packet, senderPeerID, linkAddress, linkAddress)
    }

    fun start() {
        core.startCore()
        core.sendBroadcastAnnounce()
        core.gossipSyncManager.scheduleInitialSync(1_500)
    }

    fun announce() = core.sendBroadcastAnnounce()

    fun sendPublic(text: String) {
        if (text.isEmpty()) return
        val local = BitchatMessage(
            id = UUID.randomUUID().toString(),
            sender = identity.nickname,
            content = text,
            timestamp = Date(),
            isPrivate = false,
            senderPeerID = identity.peerID,
        )
        appendPublic(local)
        core.sendMessage(text)
    }

    fun sendPrivate(text: String, recipientPeerID: String, recipientNickname: String) {
        if (text.isEmpty()) return
        val local = BitchatMessage(
            id = UUID.randomUUID().toString(),
            sender = identity.nickname,
            content = text,
            timestamp = Date(),
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = identity.peerID,
        )
        appendPrivate(local)
        core.sendPrivateMessage(text, recipientPeerID, recipientNickname)
    }

    private fun appendPublic(message: BitchatMessage) {
        _publicMessages.update { list ->
            if (list.any { it.id == message.id }) list else (list + message).takeLast(MAX_MESSAGES)
        }
    }

    private fun appendPrivate(message: BitchatMessage) {
        _privateMessages.update { list ->
            if (list.any { it.id == message.id }) list else (list + message).takeLast(MAX_MESSAGES)
        }
    }

    fun initiateHandshake(peerID: String) = core.initiateNoiseHandshake(peerID)

    fun hasEstablishedSession(peerID: String): Boolean = core.hasEstablishedSession(peerID)

    fun peerNickname(peerID: String): String? = core.getPeerNickname(peerID)

    fun stop() {
        try { core.sendLeaveAnnouncement() } catch (_: Exception) { }
        core.stopCore()
        core.shutdown()
        scope.cancel()
    }

    companion object {
        private const val MAX_MESSAGES = 1_000

        fun overLoopback(identity: DesktopIdentity, hub: LoopbackHub): Pair<DesktopMeshNode, LoopbackTransport> {
            val transport = hub.endpoint(identity.peerID)
            val node = DesktopMeshNode(identity, transport)
            transport.listener = LoopbackTransport.Listener(node::receive)
            return node to transport
        }
    }
}
