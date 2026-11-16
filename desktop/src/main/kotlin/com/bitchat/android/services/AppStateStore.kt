package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop variant (same FQN) of the phone's process-wide state store, limited to the members the
 * mirrored mesh, favorites, sync and Nostr code call. The phone store persists conversations
 * through ConversationRepository (android.database.sqlite + Android Keystore), which this stage
 * deliberately does not shim; messages here live in memory only. The UI-parity stage replaces
 * this with either the real AppStateStore over a JDBC-backed repository or a shared interface.
 * See docs/desktop-architecture.md ("Excluded: conversation persistence").
 */
object AppStateStore {
    private val seenMessageIds = LinkedHashSet<String>()
    private val peerIdsByTransport = mutableMapOf<String, Set<String>>()
    private val directPeerIdsByTransport = mutableMapOf<String, Set<String>>()

    private val _nickname = MutableStateFlow("")
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    private val _peers = MutableStateFlow<List<String>>(emptyList())
    val peers: StateFlow<List<String>> = _peers.asStateFlow()

    private val _directPeers = MutableStateFlow<Set<String>>(emptySet())
    val directPeers: StateFlow<Set<String>> = _directPeers.asStateFlow()

    private val _publicMessages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val publicMessages: StateFlow<List<BitchatMessage>> = _publicMessages.asStateFlow()

    private val _privateMessages = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    val privateMessages: StateFlow<Map<String, List<BitchatMessage>>> = _privateMessages.asStateFlow()

    private val _readPrivateMessageIDs = MutableStateFlow<Set<String>>(emptySet())
    val readPrivateMessageIDs: StateFlow<Set<String>> = _readPrivateMessageIDs.asStateFlow()

    fun setNickname(nickname: String) {
        _nickname.value = nickname
    }

    fun setTransportPeers(transportId: String, ids: List<String>) = synchronized(this) {
        peerIdsByTransport[transportId] = ids.toSet()
        _peers.value = peerIdsByTransport.values.flatten().distinct().sorted()
    }

    fun setTransportDirectPeers(transportId: String, ids: Collection<String>) = synchronized(this) {
        directPeerIdsByTransport[transportId] = ids.toSet()
        _directPeers.value = directPeerIdsByTransport.values.flatten().toSet()
    }

    fun getDirectPeers(): Set<String> = _directPeers.value

    fun hasSeenMessage(messageID: String): Boolean = synchronized(this) { messageID in seenMessageIds }

    fun addPublicMessage(msg: BitchatMessage) = synchronized(this) {
        if (!seenMessageIds.add(msg.id)) return
        _publicMessages.value = (_publicMessages.value + msg).takeLast(MAX_PUBLIC_TIMELINE_MESSAGES)
    }

    fun addPrivateMessage(peerID: String, msg: BitchatMessage, forceRead: Boolean = false): Boolean = synchronized(this) {
        if (!seenMessageIds.add(msg.id)) return false
        val map = _privateMessages.value.toMutableMap()
        map[peerID] = (map[peerID] ?: emptyList()) + msg
        _privateMessages.value = map
        if (forceRead) _readPrivateMessageIDs.value = _readPrivateMessageIDs.value + msg.id
        true
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun addPrivateMessageDurably(
        peerID: String,
        msg: BitchatMessage,
        forceRead: Boolean = false,
        syncToInternet: Boolean = true,
    ): Boolean = addPrivateMessage(peerID, msg, forceRead)

    fun updatePrivateMessageStatus(messageID: String, status: DeliveryStatus) = synchronized(this) {
        var changed = false
        val updated = _privateMessages.value.mapValues { (_, list) ->
            list.map { m ->
                if (m.id == messageID && m.deliveryStatus != status) {
                    changed = true
                    m.copy(deliveryStatus = status)
                } else m
            }
        }
        if (changed) _privateMessages.value = updated
    }

    /** Alias canonicalization needs the persisted conversation index; nothing to merge in memory. */
    fun canonicalizePrivateChats() = Unit

    @Suppress("UNUSED_PARAMETER")
    fun markPrivateMessageRead(messageID: String, syncToInternet: Boolean = true) = synchronized(this) {
        _readPrivateMessageIDs.value = _readPrivateMessageIDs.value + messageID
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun setPrivateConversationRead(
        conversationID: String,
        isRead: Boolean,
        syncToInternet: Boolean = true,
    ): Boolean = synchronized(this) {
        val ids = _privateMessages.value[conversationID]?.map { it.id } ?: return false
        _readPrivateMessageIDs.value =
            if (isRead) _readPrivateMessageIDs.value + ids else _readPrivateMessageIDs.value - ids.toSet()
        true
    }

    /** Test/panic hook: forget everything held in memory. */
    fun clearAll() = synchronized(this) {
        seenMessageIds.clear()
        peerIdsByTransport.clear()
        directPeerIdsByTransport.clear()
        _peers.value = emptyList()
        _directPeers.value = emptySet()
        _publicMessages.value = emptyList()
        _privateMessages.value = emptyMap()
        _readPrivateMessageIDs.value = emptySet()
    }

    const val MAX_PUBLIC_TIMELINE_MESSAGES = 1_000
}
