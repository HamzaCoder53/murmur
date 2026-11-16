package com.bitchat.desktop.core

import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.mesh.DirectLinkAnnouncementPolicy
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.services.AppStateStore
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

/**
 * One desktop mesh endpoint: the shared [MeshCore] (routing, signatures, Noise, fragmentation,
 * gossip sync) over one [MeshTransport], built the same way as the phone's LanMeshService.
 * Transports feed received packets into [receive]. UI state is exposed as immutable StateFlows.
 *
 * This node is the single in-process message store for the window and linked devices: the UI
 * adapter and [com.bitchat.desktop.link.DesktopLinkedAppBridge] both read and write it. Saved
 * history is loaded into it at startup with [restore].
 */
class DesktopMeshNode(
    val identity: DesktopIdentity,
    private val transport: MeshTransport,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val maxTtl: UByte = AppConstants.MESSAGE_TTL_HOPS
    private val lock = Any()

    private val _publicMessages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val publicMessages: StateFlow<List<BitchatMessage>> = _publicMessages.asStateFlow()

    /** Private messages keyed by the other party's peer ID (incoming and outgoing). */
    private val _privateChats = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    val privateChats: StateFlow<Map<String, List<BitchatMessage>>> = _privateChats.asStateFlow()

    /** Every private message, flattened in arrival order (incoming and outgoing). */
    private val _privateMessages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val privateMessages: StateFlow<List<BitchatMessage>> = _privateMessages.asStateFlow()

    /** IDs of private messages known to be read (own messages, or imported as read). */
    private val _readPrivateIDs = MutableStateFlow<Set<String>>(emptySet())
    val readPrivateIDs: StateFlow<Set<String>> = _readPrivateIDs.asStateFlow()

    private val _peers = MutableStateFlow<List<String>>(emptyList())
    val peers: StateFlow<List<String>> = _peers.asStateFlow()

    private val _sessionVersion = MutableStateFlow(0)
    val sessionVersion: StateFlow<Int> = _sessionVersion.asStateFlow()

    private data class PendingPrivate(val id: String, val text: String, val recipientNickname: String)
    private val pendingPrivate = HashMap<String, MutableList<PendingPrivate>>()

    val peerID: String get() = identity.peerID

    /**
     * Receives every message this node shows (sent or received) with its private conversation
     * (the other peer's ID; null for public). The app points it at AppStateStore, the store the
     * UI, linked devices and backups share.
     */
    @Volatile
    var messageSink: ((BitchatMessage, String?) -> Unit)? = null

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
        val prevEstablished = identity.encryptionService.onSessionEstablished
        identity.encryptionService.onSessionEstablished = { peerID ->
            prevEstablished?.invoke(peerID)
            flushPendingPrivate(peerID)
            _sessionVersion.value++
        }
        val prevLost = identity.encryptionService.onSessionLost
        identity.encryptionService.onSessionLost = { peerID ->
            prevLost?.invoke(peerID)
            _sessionVersion.value++
        }
        core.delegate = object : MeshDelegate {
            override fun didReceiveMessage(message: BitchatMessage) {
                if (message.isPrivate) {
                    addPrivate(message.senderPeerID ?: message.sender, message, read = false)
                } else {
                    addPublic(message)
                }
            }

            override fun didUpdatePeerList(peers: List<String>) {
                _peers.value = peers
            }

            override fun didReceiveChannelLeave(channel: String, fromPeer: String) = Unit

            override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
                updateDeliveryStatus(messageID, DeliveryStatus.Delivered(nameFor(recipientPeerID), Date()))
            }

            override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
                updateDeliveryStatus(messageID, DeliveryStatus.Read(nameFor(recipientPeerID), Date()))
            }

            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
            override fun getNickname(): String = identity.nickname
            override fun isFavorite(peerID: String): Boolean = isFavoritePeer(peerID)
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

    /**
     * Changes the local nickname, keeps the process-wide store in step and announces the new name
     * right away so peers do not have to wait for the next periodic announce.
     */
    fun rename(nickname: String) {
        identity.nickname = nickname
        AppStateStore.setNickname(identity.nickname)
        announce()
    }

    /** [messageID] keeps a linked device's ID for its message, so every copy merges by ID. */
    fun sendPublic(
        text: String,
        messageID: String? = null,
        mentions: List<String> = emptyList(),
        channel: String? = null,
    ) {
        if (text.isEmpty()) return
        val local = BitchatMessage(
            id = messageID ?: UUID.randomUUID().toString(),
            sender = identity.nickname,
            content = text,
            timestamp = Date(),
            isPrivate = false,
            senderPeerID = identity.peerID,
            mentions = mentions.takeIf { it.isNotEmpty() },
            channel = channel,
        )
        addPublic(local)
        core.sendMessage(text, mentions, channel)
    }

    /**
     * Sends a private message. The local copy and the wire message share one ID so delivery acks
     * and read receipts from the recipient update the local copy's [DeliveryStatus].
     */
    fun sendPrivate(
        text: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String? = null,
    ) {
        if (text.isEmpty()) return
        val id = messageID ?: UUID.randomUUID().toString()
        val hasSession = hasEstablishedSession(recipientPeerID)
        val local = BitchatMessage(
            id = id,
            sender = identity.nickname,
            content = text,
            timestamp = Date(),
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = identity.peerID,
            deliveryStatus = if (hasSession) DeliveryStatus.Sent else DeliveryStatus.Sending,
        )
        addPrivate(recipientPeerID, local, read = true)
        if (hasSession) {
            core.sendPrivateMessage(text, recipientPeerID, recipientNickname, id)
        } else {
            synchronized(lock) {
                pendingPrivate.getOrPut(recipientPeerID) { mutableListOf() }
                    .add(PendingPrivate(id, text, recipientNickname))
            }
            core.initiateNoiseHandshake(recipientPeerID)
        }
    }

    private fun flushPendingPrivate(peerID: String) {
        val queued = synchronized(lock) { pendingPrivate.remove(peerID) } ?: return
        for (item in queued) {
            updateDeliveryStatus(item.id, DeliveryStatus.Sent)
            core.sendPrivateMessage(item.text, peerID, item.recipientNickname, item.id)
        }
    }

    fun sendReadReceipt(messageID: String, recipientPeerID: String) =
        core.sendReadReceipt(messageID, recipientPeerID, identity.nickname)

    /** Broadcasts [file] when [recipientPeerID] is null or blank, else sends it privately. */
    fun sendFile(recipientPeerID: String?, file: BitchatFilePacket) {
        if (recipientPeerID.isNullOrBlank()) core.sendFileBroadcast(file) else core.sendFilePrivate(recipientPeerID, file)
    }

    /** Loads saved or synced messages without sending anything. */
    fun restore(conversation: String?, message: BitchatMessage, read: Boolean = true) {
        if (conversation == null) addPublic(message) else addPrivate(conversation, message, read)
    }

    fun markPrivateRead(messageIDs: Collection<String>) = synchronized(lock) {
        _readPrivateIDs.value = _readPrivateIDs.value + messageIDs
    }

    private fun addPublic(message: BitchatMessage) {
        synchronized(lock) {
            val list = _publicMessages.value
            if (list.any { it.id == message.id }) return
            _publicMessages.value = (list + message).sortedBy { it.timestamp.time }.takeLast(MAX_MESSAGES)
        }
        messageSink?.invoke(message, null)
    }

    private fun addPrivate(peerID: String, message: BitchatMessage, read: Boolean) {
        synchronized(lock) {
            val flat = _privateMessages.value
            if (flat.any { it.id == message.id }) return
            _privateMessages.value = (flat + message).takeLast(MAX_MESSAGES)
            val chats = _privateChats.value.toMutableMap()
            chats[peerID] = ((chats[peerID] ?: emptyList()) + message)
                .sortedBy { it.timestamp.time }
                .takeLast(MAX_MESSAGES)
            _privateChats.value = chats
            if (read || message.senderPeerID == identity.peerID) {
                _readPrivateIDs.value = _readPrivateIDs.value + message.id
            }
        }
        messageSink?.invoke(message, peerID)
    }

    /** Applies a delivery update; a read receipt is never downgraded by a late delivery ack. */
    private fun updateDeliveryStatus(messageID: String, status: DeliveryStatus) = synchronized(lock) {
        fun upgrade(m: BitchatMessage): BitchatMessage {
            if (m.id != messageID || m.senderPeerID != identity.peerID) return m
            if (m.deliveryStatus is DeliveryStatus.Read && status !is DeliveryStatus.Read) return m
            return m.copy(deliveryStatus = status)
        }
        val flat = _privateMessages.value
        if (flat.none { it.id == messageID }) return@synchronized
        _privateMessages.value = flat.map(::upgrade)
        _privateChats.value = _privateChats.value.mapValues { (_, list) -> list.map(::upgrade) }
    }

    private fun nameFor(peerID: String): String = core.getPeerNickname(peerID) ?: peerID.take(8)

    /** Favorites are the phone's FavoritesPersistenceService records (by Noise key or peer ID). */
    fun isFavoritePeer(peerID: String): Boolean =
        try {
            FavoritesPersistenceService.shared.getFavoriteStatus(peerID)?.isFavorite == true
        } catch (_: Exception) {
            false
        }

    /** The peer's Noise static public key once its announce has been verified. */
    fun peerNoiseKey(peerID: String): ByteArray? = core.getPeerInfo(peerID)?.noisePublicKey

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
