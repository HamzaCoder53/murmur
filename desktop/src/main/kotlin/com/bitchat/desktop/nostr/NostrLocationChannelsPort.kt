package com.bitchat.desktop.nostr

import android.content.Context
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrFilter
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.desktop.ui.ChatMessage
import com.bitchat.desktop.ui.ConversationId
import com.bitchat.desktop.ui.DeliveryState
import com.bitchat.desktop.ui.LocationChannelInfo
import com.bitchat.desktop.ui.LocationChannelsPort
import com.bitchat.desktop.ui.normalizeGeohash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Date

/**
 * [LocationChannelsPort] backed by [DesktopNostrRelayClient] and the mirrored [NostrProtocol] /
 * [NostrIdentityBridge] for ephemeral kind-20000 geohash channels.
 */
class NostrLocationChannelsPort(
    private val context: Context,
    private val myNickname: () -> String,
    private val relayClient: DesktopNostrRelayClient,
    private val scope: CoroutineScope,
) : LocationChannelsPort {

    private val _channels = MutableStateFlow<List<LocationChannelInfo>>(emptyList())
    override val channels: StateFlow<List<LocationChannelInfo>> = _channels.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val seenIds = LinkedHashSet<String>()

    override fun join(geohash: String) {
        val norm = normalizeGeohash(geohash) ?: return
        if (_channels.value.any { it.geohash == norm }) return
        _channels.update { it + LocationChannelInfo(norm, participants = 0) }
        val sinceSec = (System.currentTimeMillis() / 1000L) - 3_600L
        relayClient.subscribe(subIdFor(norm), NostrFilter.geohashEphemeral(norm, since = sinceSec)) { ev ->
            onGeohashEvent(norm, ev)
        }
    }

    override fun leave(geohash: String) {
        val norm = normalizeGeohash(geohash) ?: return
        _channels.update { it.filterNot { ch -> ch.geohash == norm } }
        relayClient.unsubscribe(subIdFor(norm))
    }

    override fun send(geohash: String, text: String) {
        val norm = normalizeGeohash(geohash) ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        scope.launch {
            val identity = NostrIdentityBridge.deriveIdentity(forGeohash = norm, context = context)
            val nick = myNickname()
            val event = NostrProtocol.createEphemeralGeohashEvent(
                content = clean,
                geohash = norm,
                senderIdentity = identity,
                nickname = nick,
            )
            val msg = ChatMessage(
                id = event.id,
                conversation = ConversationId.Location(norm),
                senderPeerID = identity.publicKeyHex.take(16),
                senderNickname = nick,
                text = clean,
                timestamp = Date(event.createdAt.toLong() * 1000L),
                isMine = true,
                delivery = DeliveryState.SENT,
            )
            appendMessage(msg)
            relayClient.publish(event)
        }
    }

    internal fun onGeohashEvent(geohash: String, event: NostrEvent) {
        val senderNick = event.tags.firstOrNull { it.size >= 2 && it[0] == "n" }?.get(1)
            ?: event.pubkey.take(8)
        val msg = ChatMessage(
            id = event.id,
            conversation = ConversationId.Location(geohash),
            senderPeerID = event.pubkey.take(16),
            senderNickname = senderNick,
            text = event.content,
            timestamp = Date(event.createdAt.toLong() * 1000L),
            isMine = false,
        )
        appendMessage(msg)
    }

    private fun appendMessage(msg: ChatMessage) {
        synchronized(seenIds) {
            if (!seenIds.add(msg.id)) return
        }
        _messages.update { (it + msg).takeLast(MAX_MESSAGES) }
    }

    private fun subIdFor(geohash: String): String = "geo-$geohash"

    companion object {
        private const val MAX_MESSAGES = 1_000
    }
}
