package com.bitchat.android.sync

import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import java.util.Date

/**
 * Applies incoming self-addressed internet sync events to local conversation state.
 *
 * Rejects malformed payloads, payloads not signed by our own Nostr pubkey, and echoes from
 * this same device. Deduplicates by message ID so 48h NIP-17 lookback replays are idempotent.
 */
class InternetSyncReceiver(
    private val isSyncEnabled: () -> Boolean,
    private val localDeviceIdProvider: () -> String,
    private val myNicknameProvider: () -> String?,
    private val hasSeenMessage: (String) -> Boolean,
    private val insertPrivateMessage: suspend (conversationID: String, message: BitchatMessage, forceRead: Boolean) -> Boolean,
    private val markMessageRead: (String) -> Unit,
    private val markConversationRead: suspend (String) -> Boolean,
    private val registerNostrAlias: (alias: String, pubkeyHex: String) -> Unit = { alias, pubkey ->
        GeohashAliasRegistry.put(alias, pubkey)
    },
    private val resolveConversationId: (
        conv: String,
        peer: String?,
        noiseKeyHex: String?,
        nostrPubkeyHex: String?
    ) -> String = ::defaultResolveConversationId
) {
    companion object {
        private const val TAG = "InternetSyncReceiver"
        private const val MAX_SEEN_IDS = 4_000

        fun defaultResolveConversationId(
            conv: String,
            peer: String?,
            noiseKeyHex: String?,
            nostrPubkeyHex: String?
        ): String {
            noiseKeyHex
                ?.let(ContactIdentityResolver::bytesFromHex)
                ?.takeIf { it.size == 32 }
                ?.let { return ContactIdentityResolver.contactConversationIdForNoiseKey(it) }

            if (ContactIdentityResolver.isContactConversationId(conv)) {
                return conv.lowercase()
            }

            nostrPubkeyHex
                ?.let(ContactIdentityResolver::nostrPubkeyHex)
                ?.let { pubHex ->
                    val alias = "nostr_${pubHex.take(16)}"
                    val canonical = ContactDirectory.canonicalConversationId(alias)
                    if (ContactIdentityResolver.isContactConversationId(canonical)) {
                        return canonical
                    }
                    return canonical
                }

            peer?.takeIf { it.isNotBlank() }?.let {
                return ContactDirectory.canonicalConversationId(it)
            }

            return ContactDirectory.canonicalConversationId(conv)
        }
    }

    private val seenIdsLock = Any()
    private val seenMessageOrder = ArrayDeque<String>()
    private val seenMessageSet = HashSet<String>()

    suspend fun onSyncPayload(
        payload: String,
        rumorTimestampSec: Int,
        senderPubkeyHex: String,
        ownPubkeyHex: String
    ): Boolean {
        if (!isSyncEnabled()) return false
        if (!senderPubkeyHex.equals(ownPubkeyHex, ignoreCase = true)) {
            Log.w(TAG, "Ignoring sync event not signed by own Nostr identity")
            return false
        }

        val envelope = InternetSyncProtocol.decode(payload) ?: run {
            Log.w(TAG, "Rejected malformed internet sync payload")
            return false
        }

        if (envelope.deviceId == localDeviceIdProvider()) {
            return false
        }

        var appliedAny = false
        for (op in envelope.ops) {
            when (op) {
                is InternetSyncProtocol.SyncOp.MsgOp -> {
                    if (applyMsgOp(op, rumorTimestampSec)) {
                        appliedAny = true
                    }
                }
                is InternetSyncProtocol.SyncOp.ReadOp -> {
                    if (applyReadOp(op)) {
                        appliedAny = true
                    }
                }
            }
        }
        return appliedAny
    }

    private suspend fun applyMsgOp(
        op: InternetSyncProtocol.SyncOp.MsgOp,
        rumorTimestampSec: Int
    ): Boolean {
        if (hasSeenMessage(op.id) || isLocallySeen(op.id)) {
            return false
        }

        op.nostrPubkeyHex
            ?.let(ContactIdentityResolver::nostrPubkeyHex)
            ?.let { pubHex ->
                registerNostrAlias("nostr_${pubHex.take(16)}", pubHex)
            }

        val conversationID = resolveConversationId(
            op.conv,
            op.peer,
            op.noiseKeyHex,
            op.nostrPubkeyHex
        )

        val isOutgoing = op.direction == InternetSyncProtocol.Direction.OUT
        val senderName = if (isOutgoing) {
            myNicknameProvider()?.takeIf { it.isNotBlank() } ?: op.sender
        } else {
            op.sender
        }

        val timestampMs = if (op.timestampMs > 0L) op.timestampMs else rumorTimestampSec * 1000L
        val status = InternetSyncProtocol.decodeDeliveryStatus(
            state = op.deliveryState,
            actor = op.statusActor,
            atMs = op.statusAtMs,
            reason = op.failureReason
        )

        val message = BitchatMessage(
            id = op.id,
            sender = senderName,
            content = op.content,
            timestamp = Date(timestampMs),
            isRelay = false,
            isPrivate = true,
            senderPeerID = if (isOutgoing) null else conversationID,
            deliveryStatus = status,
            senderNostrPubkey = op.nostrPubkeyHex
        )

        val forceRead = isOutgoing || op.isRead
        val inserted = insertPrivateMessage(conversationID, message, forceRead)
        if (inserted) {
            rememberLocallySeen(op.id)
        }
        return inserted
    }

    private suspend fun applyReadOp(op: InternetSyncProtocol.SyncOp.ReadOp): Boolean {
        var applied = false
        if (op.markAll && op.conv != "_") {
            val conversationID = resolveConversationId(
                op.conv,
                op.peer,
                op.noiseKeyHex,
                op.nostrPubkeyHex
            )
            if (markConversationRead(conversationID)) {
                applied = true
            }
        }
        op.messageIds.forEach { messageId ->
            markMessageRead(messageId)
            applied = true
        }
        return applied
    }

    private fun isLocallySeen(id: String): Boolean = synchronized(seenIdsLock) {
        id in seenMessageSet
    }

    private fun rememberLocallySeen(id: String) {
        synchronized(seenIdsLock) {
            if (!seenMessageSet.add(id)) return
            seenMessageOrder.addLast(id)
            while (seenMessageOrder.size > MAX_SEEN_IDS) {
                val oldest = seenMessageOrder.removeFirst()
                seenMessageSet.remove(oldest)
            }
        }
    }
}
