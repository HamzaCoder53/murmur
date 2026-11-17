package com.bitchat.desktop.core

import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.mesh.DirectLinkAnnouncementPolicy
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.util.AppConstants
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    /** How long a private message waits for a Noise session before it is marked failed. */
    private val pendingPrivateTimeoutMs: Long = PENDING_PRIVATE_TIMEOUT_MS,
) {
    /** A private message this node sent (or is waiting to send), with its recipient. */
    data class OutgoingPrivate(val recipientPeerID: String, val message: BitchatMessage)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val maxTtl: UByte = AppConstants.MESSAGE_TTL_HOPS

    private val _publicMessages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val publicMessages: StateFlow<List<BitchatMessage>> = _publicMessages.asStateFlow()

    private val _privateMessages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val privateMessages: StateFlow<List<BitchatMessage>> = _privateMessages.asStateFlow()

    private val _outgoingPrivate = MutableStateFlow<List<OutgoingPrivate>>(emptyList())
    /** Own private messages, keyed by the same message ID that goes on the wire (receipts update them). */
    val outgoingPrivate: StateFlow<List<OutgoingPrivate>> = _outgoingPrivate.asStateFlow()

    private val _peers = MutableStateFlow<List<String>>(emptyList())
    val peers: StateFlow<List<String>> = _peers.asStateFlow()

    private val _peerStateRevision = MutableStateFlow(0L)
    /**
     * Bumped whenever per-peer state that has no flow of its own may have changed: a Noise session
     * was established or lost, or an announce updated a peer's nickname.
     */
    val peerStateRevision: StateFlow<Long> = _peerStateRevision.asStateFlow()

    /** Message IDs per recipient waiting for a Noise session. Guarded by [pendingLock]. */
    private val pendingPrivate = HashMap<String, MutableList<String>>()
    private val pendingLock = Any()

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
                bumpPeerState()
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
        identity.encryptionService.onSessionEstablished = { peerID ->
            flushPending(peerID)
            bumpPeerState()
        }
        identity.encryptionService.onSessionLost = { bumpPeerState() }
        core.delegate = object : MeshDelegate {
            override fun didReceiveMessage(message: BitchatMessage) {
                if (message.isPrivate) appendPrivate(message) else appendPublic(message)
            }

            override fun didUpdatePeerList(peers: List<String>) {
                _peers.value = peers
            }

            override fun didReceiveChannelLeave(channel: String, fromPeer: String) = Unit
            override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
                updateOutgoing(messageID) { current ->
                    if (current is DeliveryStatus.Read) current
                    else DeliveryStatus.Delivered(peerNickname(recipientPeerID) ?: recipientPeerID.take(8), Date())
                }
            }

            override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
                updateOutgoing(messageID) {
                    DeliveryStatus.Read(peerNickname(recipientPeerID) ?: recipientPeerID.take(8), Date())
                }
            }
            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
            override fun getNickname(): String = identity.nickname
            override fun isFavorite(peerID: String): Boolean =
                try { FavoritesPersistenceService.shared.getFavoriteStatus(peerID)?.isFavorite == true } catch (_: Exception) { false }
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

    /**
     * Sends a private message. With an established Noise session it goes out at once; otherwise
     * it is queued as [DeliveryStatus.Sending], a handshake is started, and it is sent when the
     * session is established or marked [DeliveryStatus.Failed] after [pendingPrivateTimeoutMs].
     * Returns the message ID used both locally and on the wire, or null for empty text.
     */
    fun sendPrivate(text: String, recipientPeerID: String, recipientNickname: String): String? {
        if (text.isEmpty() || recipientPeerID.isEmpty()) return null
        val id = UUID.randomUUID().toString().uppercase()
        var queued = false
        var startHandshake = false
        synchronized(pendingLock) {
            val ready = core.hasEstablishedSession(recipientPeerID)
            val local = BitchatMessage(
                id = id,
                sender = identity.nickname,
                content = text,
                timestamp = Date(),
                isPrivate = true,
                recipientNickname = recipientNickname,
                senderPeerID = identity.peerID,
                deliveryStatus = if (ready) DeliveryStatus.Sent else DeliveryStatus.Sending,
            )
            _outgoingPrivate.update { (it + OutgoingPrivate(recipientPeerID, local)).takeLast(MAX_MESSAGES) }
            if (ready) {
                core.sendPrivateMessage(text, recipientPeerID, recipientNickname, id)
            } else {
                val queue = pendingPrivate.getOrPut(recipientPeerID) { mutableListOf() }
                startHandshake = queue.isEmpty()
                queue += id
                queued = true
            }
        }
        if (queued) {
            if (startHandshake) core.initiateNoiseHandshake(recipientPeerID)
            scope.launch {
                delay(pendingPrivateTimeoutMs)
                expirePending(recipientPeerID, id)
            }
        }
        return id
    }

    /** True while at least one private message to [peerID] waits for a session. */
    fun hasPendingPrivate(peerID: String): Boolean = synchronized(pendingLock) { !pendingPrivate[peerID].isNullOrEmpty() }

    private fun flushPending(peerID: String) {
        synchronized(pendingLock) {
            val ids = pendingPrivate.remove(peerID) ?: return
            for (id in ids) {
                val out = _outgoingPrivate.value.firstOrNull { it.message.id == id } ?: continue
                core.sendPrivateMessage(
                    out.message.content,
                    peerID,
                    out.message.recipientNickname ?: peerID.take(8),
                    id,
                )
                updateOutgoing(id) { DeliveryStatus.Sent }
            }
        }
    }

    private fun expirePending(peerID: String, id: String) {
        synchronized(pendingLock) {
            val queue = pendingPrivate[peerID] ?: return
            if (!queue.remove(id)) return
            if (queue.isEmpty()) pendingPrivate.remove(peerID)
            updateOutgoing(id) { DeliveryStatus.Failed("No secure session") }
        }
        bumpPeerState()
    }

    private fun updateOutgoing(id: String, change: (DeliveryStatus?) -> DeliveryStatus) {
        _outgoingPrivate.update { list ->
            list.map { if (it.message.id == id) it.copy(message = it.message.copy(deliveryStatus = change(it.message.deliveryStatus))) else it }
        }
    }

    private fun bumpPeerState() = _peerStateRevision.update { it + 1 }

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

    /** Full Noise static-key fingerprint (64 hex) of a peer once its key is known, else null. */
    fun peerFingerprint(peerID: String): String? = core.getPeerFingerprint(peerID)

    /** The peer's Noise static public key, if an announce or handshake has revealed it. */
    fun peerNoisePublicKey(peerID: String): ByteArray? = core.getPeerInfo(peerID)?.noisePublicKey

    fun stop() {
        try { core.sendLeaveAnnouncement() } catch (_: Exception) { }
        core.stopCore()
        core.shutdown()
        scope.cancel()
    }

    companion object {
        const val MAX_MESSAGES = 1_000
        const val PENDING_PRIVATE_TIMEOUT_MS = 30_000L

        fun overLoopback(
            identity: DesktopIdentity,
            hub: LoopbackHub,
            pendingPrivateTimeoutMs: Long = PENDING_PRIVATE_TIMEOUT_MS,
        ): Pair<DesktopMeshNode, LoopbackTransport> {
            val transport = hub.endpoint(identity.peerID)
            val node = DesktopMeshNode(identity, transport, pendingPrivateTimeoutMs)
            transport.listener = LoopbackTransport.Listener(node::receive)
            return node to transport
        }
    }
}
