package com.bitchat.desktop.nostr

import android.content.Context
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.nostr.NostrEmbeddedBitChat
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrFilter
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.desktop.core.DesktopMeshNode
import java.util.Date
import java.util.UUID

/**
 * NIP-17 Nostr private messaging bridge for desktop:
 * - Subscribes to `kind:1059` gift-wraps addressed to this device's Nostr pubkey.
 * - Unwraps incoming NIP-17 messages (and embedded Bitchat DM/ACK/receipt payloads) into [DesktopMeshNode].
 * - Sends NIP-17 private messages and read/delivery receipts when a peer has a known Nostr pubkey.
 */
class NostrDirectMessageBridge(
    private val context: Context,
    private val node: DesktopMeshNode,
    private val relayClient: DesktopNostrRelayClient,
    private val identityOverride: NostrIdentity? = null,
) {
    private val subId = "dm-${UUID.randomUUID().toString().take(8)}"

    @Volatile
    private var started = false

    fun currentIdentity(): NostrIdentity? =
        identityOverride ?: runCatching { NostrIdentityBridge.getCurrentNostrIdentity(context) }.getOrNull()

    fun start() {
        if (started) return
        val id = currentIdentity() ?: return
        started = true
        val sinceSec = (System.currentTimeMillis() / 1000L) - LOOKBACK_SECONDS
        relayClient.subscribe(subId, NostrFilter.giftWrapsFor(id.publicKeyHex, since = sinceSec)) { event ->
            handleGiftWrap(event, id)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        relayClient.unsubscribe(subId)
    }

    fun sendPrivateMessage(recipientPeerID: String, text: String, messageID: String = UUID.randomUUID().toString()): Boolean {
        val id = currentIdentity() ?: return false
        val recipientNpub = FavoritesPersistenceService.shared.findNostrPubkeyForPeerID(recipientPeerID) ?: return false
        val embedded = NostrEmbeddedBitChat.encodePMForNostr(
            content = text,
            messageID = messageID,
            recipientPeerID = recipientPeerID,
            senderPeerID = node.peerID,
        ) ?: return false
        val events = NostrProtocol.createPrivateMessage(
            content = embedded,
            recipientPubkey = recipientNpub,
            senderIdentity = id,
        )
        events.forEach(relayClient::publish)
        return events.isNotEmpty()
    }

    fun sendReadReceipt(recipientPeerID: String, messageID: String): Boolean {
        val id = currentIdentity() ?: return false
        val recipientNpub = FavoritesPersistenceService.shared.findNostrPubkeyForPeerID(recipientPeerID) ?: return false
        val embedded = NostrEmbeddedBitChat.encodeAckForNostr(
            type = com.bitchat.android.model.NoisePayloadType.READ_RECEIPT,
            messageID = messageID,
            recipientPeerID = recipientPeerID,
            senderPeerID = node.peerID,
        ) ?: return false
        val events = NostrProtocol.createPrivateMessage(
            content = embedded,
            recipientPubkey = recipientNpub,
            senderIdentity = id,
        )
        events.forEach(relayClient::publish)
        return events.isNotEmpty()
    }

    internal fun handleGiftWrap(event: NostrEvent, recipientIdentity: NostrIdentity? = currentIdentity()): Boolean {
        val id = recipientIdentity ?: return false
        val unwrapped = NostrProtocol.decryptPrivateMessage(event, id) ?: return false
        val (content, senderPubkey, timestampSec) = unwrapped
        val embedded = decodeEmbeddedPacket(content)
        if (embedded != null) {
            val senderPeerID = embedded.senderID.joinToString("") { "%02x".format(it) }
            val senderNick = node.peerNickname(senderPeerID) ?: senderPeerID.take(8)
            val noisePayload = com.bitchat.android.model.NoisePayload.decode(embedded.payload)
            if (noisePayload != null && noisePayload.type == com.bitchat.android.model.NoisePayloadType.PRIVATE_MESSAGE) {
                val pm = com.bitchat.android.model.PrivateMessagePacket.decode(noisePayload.data)
                if (pm != null) {
                    val msg = BitchatMessage(
                        id = pm.messageID,
                        sender = senderNick,
                        content = pm.content,
                        timestamp = Date(timestampSec.toLong() * 1000L),
                        isPrivate = true,
                        senderPeerID = senderPeerID,
                    )
                    node.restore(senderPeerID, msg, read = false)
                    return true
                }
            }
        }
        val peerKey = FavoritesPersistenceService.shared.findPeerIDForNostrPubkey(senderPubkey) ?: senderPubkey.take(16)
        val nick = node.peerNickname(peerKey) ?: peerKey.take(8)
        val msg = BitchatMessage(
            id = event.id,
            sender = nick,
            content = content,
            timestamp = Date(timestampSec.toLong() * 1000L),
            isPrivate = true,
            senderPeerID = peerKey,
        )
        node.restore(peerKey, msg, read = false)
        return true
    }

    private fun decodeEmbeddedPacket(content: String): com.bitchat.android.protocol.BitchatPacket? {
        if (!content.startsWith("bitchat1:")) return null
        val raw = runCatching {
            android.util.Base64.decode(content.removePrefix("bitchat1:"), android.util.Base64.URL_SAFE)
        }.getOrNull() ?: return null
        return com.bitchat.android.protocol.BitchatPacket.fromBinaryData(raw)
    }

    companion object {
        private const val LOOKBACK_SECONDS = 24 * 3600L
    }
}
