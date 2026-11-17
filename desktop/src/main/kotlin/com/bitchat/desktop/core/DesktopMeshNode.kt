package com.bitchat.desktop.core

import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.mesh.DirectLinkAnnouncementPolicy
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.mesh.FragmentManager
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Implemented by transports that map peers to physical links. The node binds a peer to the link
 * it was heard on only after a validated, full-TTL (direct, unrelayed) announce, so a spoofed or
 * relayed packet header can never redirect traffic for a peer ID.
 */
interface DirectLinkBinder {
    fun bindPeer(linkAddress: String, peerID: String): Boolean
}

/**
 * One desktop mesh endpoint: the shared [MeshCore] (routing, signatures, Noise, fragmentation,
 * gossip sync) over one [MeshTransport], built the same way as the phone's LanMeshService.
 * Transports feed received packets into [receive]. UI state is exposed as immutable StateFlows.
 */
class DesktopMeshNode(
    val identity: DesktopIdentity,
    private val transport: MeshTransport,
    private val store: ConversationStore = ConversationStore.None,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val maxTtl: UByte = AppConstants.MESSAGE_TTL_HOPS

    private val _publicMessages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val publicMessages: StateFlow<List<BitchatMessage>> = _publicMessages.asStateFlow()

    private val _privateMessages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    /** Every private message, both directions, in arrival order. */
    val privateMessages: StateFlow<List<BitchatMessage>> = _privateMessages.asStateFlow()

    private val _privateConversations = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    /** Private messages keyed by the conversation partner's peer ID (sender or recipient). */
    val privateConversations: StateFlow<Map<String, List<BitchatMessage>>> = _privateConversations.asStateFlow()

    private val _incomingPrivate = MutableSharedFlow<BitchatMessage>(extraBufferCapacity = 64)
    /** Private messages received from peers (never our own sends); drives notifications. */
    val incomingPrivate: SharedFlow<BitchatMessage> = _incomingPrivate.asSharedFlow()

    private val stateLock = Any()

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
                    val bound = (transport as? DirectLinkBinder)?.bindPeer(obs.relayAddress, obs.peerID) ?: true
                    if (bound) {
                        core.setDirectConnection(obs.peerID, true)
                        core.gossipSyncManager.scheduleInitialSyncToPeer(obs.peerID, 1_000)
                    }
                }
            },
            announcementNicknameProvider = { identity.nickname },
            leavePayloadProvider = { identity.nickname.toByteArray(Charsets.UTF_8) },
        ),
    )

    init {
        core.delegate = object : MeshDelegate {
            override fun didReceiveMessage(message: BitchatMessage) {
                if (message.isPrivate) {
                    val partner = message.senderPeerID ?: return
                    if (appendPrivate(partner, message)) _incomingPrivate.tryEmit(message)
                } else {
                    appendPublic(message)
                }
            }

            override fun didUpdatePeerList(peers: List<String>) {
                _peers.value = peers
            }

            override fun didReceiveChannelLeave(channel: String, fromPeer: String) = Unit
            override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
                updateStatus(messageID) { current ->
                    // Never downgrade a read receipt to delivered.
                    if (current is DeliveryStatus.Read) null
                    else DeliveryStatus.Delivered(peerNickname(recipientPeerID) ?: recipientPeerID, Date())
                }
            }

            override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
                updateStatus(messageID) { DeliveryStatus.Read(peerNickname(recipientPeerID) ?: recipientPeerID, Date()) }
            }

            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
            override fun getNickname(): String = identity.nickname
            override fun isFavorite(peerID: String): Boolean = try {
                FavoritesPersistenceService.shared.getFavoriteStatus(peerID)?.isFavorite == true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Entry point for transports: one decoded packet received over [linkAddress]. */
    fun receive(packet: BitchatPacket, senderPeerID: String, linkAddress: String) {
        core.processIncoming(packet, senderPeerID, linkAddress, linkAddress)
    }

    /** For transports that fragment large packets the same way the phone does. */
    val fragmentManager: FragmentManager get() = core.fragmentManager

    /** A transport lost its last link to [peerID]; it is no longer directly connected. */
    fun onPeerLinkLost(peerID: String) {
        core.setDirectConnection(peerID, false)
    }

    fun start() {
        restoreSaved()
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
            deliveryStatus = DeliveryStatus.Sent,
        )
        appendPrivate(recipientPeerID, local)
        core.sendPrivateMessage(text, recipientPeerID, recipientNickname, local.id)
    }

    /** Adds messages restored from a backup to [conversationPeerID]. Returns how many were new. */
    fun importPrivate(conversationPeerID: String, messages: List<BitchatMessage>): Int =
        messages.count { appendPrivate(conversationPeerID, it) }

    fun knownPrivateMessageIds(): Set<String> =
        synchronized(stateLock) { _privateMessages.value.mapTo(HashSet()) { it.id } }

    /** Forgets every private conversation, in memory and on disk. */
    fun clearPrivate() {
        synchronized(stateLock) {
            _privateConversations.value = emptyMap()
            _privateMessages.value = emptyList()
        }
        persist()
    }

    private fun appendPublic(message: BitchatMessage) {
        var added = false
        _publicMessages.update { list ->
            added = false
            if (list.any { it.id == message.id }) list
            else {
                added = true
                (list + message).takeLast(MAX_MESSAGES)
            }
        }
        if (added) persist()
    }

    private fun appendPrivate(conversationPeerID: String, message: BitchatMessage): Boolean {
        synchronized(stateLock) {
            if (_privateMessages.value.any { it.id == message.id }) return false
            val map = _privateConversations.value.toMutableMap()
            map[conversationPeerID] = ((map[conversationPeerID] ?: emptyList()) + message).takeLast(MAX_MESSAGES)
            _privateConversations.value = map
            _privateMessages.value = (_privateMessages.value + message).takeLast(MAX_MESSAGES)
        }
        persist()
        return true
    }

    private fun updateStatus(messageID: String, next: (DeliveryStatus?) -> DeliveryStatus?) {
        synchronized(stateLock) {
            var changed = false
            val conv = _privateConversations.value.mapValues { (_, list) ->
                list.map { m ->
                    if (m.id != messageID || m.senderPeerID != identity.peerID) return@map m
                    val status = next(m.deliveryStatus) ?: return@map m
                    changed = true
                    m.copy(deliveryStatus = status)
                }
            }
            if (!changed) return
            _privateConversations.value = conv
            val byId = conv.values.flatten().associateBy { it.id }
            _privateMessages.value = _privateMessages.value.map { byId[it.id] ?: it }
        }
        persist()
    }

    private fun restoreSaved() {
        val saved = try { store.load() } catch (_: Exception) { return }
        synchronized(stateLock) {
            _publicMessages.value = (saved.publicMessages + _publicMessages.value)
                .distinctBy { it.id }.takeLast(MAX_MESSAGES)
            val map = _privateConversations.value.toMutableMap()
            saved.privateConversations.forEach { (peer, list) ->
                map[peer] = (list + (map[peer] ?: emptyList())).distinctBy { it.id }.takeLast(MAX_MESSAGES)
            }
            _privateConversations.value = map
            _privateMessages.value = map.values.flatten().sortedBy { it.timestamp }.takeLast(MAX_MESSAGES)
        }
    }

    /** Writes the current conversations now (or erases them when history saving is off). */
    fun persistNow() = persist()

    private fun persist() {
        try {
            synchronized(stateLock) {
                store.save(ConversationStore.Snapshot(_publicMessages.value, _privateConversations.value))
            }
        } catch (_: Exception) {
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

        fun overLoopback(
            identity: DesktopIdentity,
            hub: LoopbackHub,
            store: ConversationStore = ConversationStore.None,
        ): Pair<DesktopMeshNode, LoopbackTransport> {
            val transport = hub.endpoint(identity.peerID)
            val node = DesktopMeshNode(identity, transport, store)
            transport.listener = LoopbackTransport.Listener(node::receive)
            return node to transport
        }
    }
}
