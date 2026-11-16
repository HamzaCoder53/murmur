package com.bitchat.android.sync

import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounces and batches outgoing internet sync operations into self-addressed NIP-17 gift wraps.
 */
class InternetSyncPublisher(
    private val isSyncEnabled: () -> Boolean,
    private val deviceIdProvider: () -> String,
    private val identityProvider: () -> NostrIdentity?,
    private val publishGiftWrap: (NostrEvent) -> Unit,
    private val resolveContact: (String) -> ContactDirectory.ContactResolution? = {
        runCatching { ContactDirectory.resolve(it) }.getOrNull()
    },
    private val myNicknameProvider: () -> String? = { null },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val maxOpsPerEvent: Int = InternetSyncProtocol.MAX_OPS_PER_EVENT,
    private val maxPayloadBytes: Int = InternetSyncProtocol.MAX_PAYLOAD_BYTES,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "InternetSyncPublisher"
        const val DEFAULT_DEBOUNCE_MS = 2_000L
    }

    private val lock = Any()
    private val pendingOps = mutableListOf<InternetSyncProtocol.SyncOp>()
    private var flushJob: Job? = null

    fun enqueueMessage(peerID: String, message: BitchatMessage, isRead: Boolean) {
        if (!isSyncEnabled()) return
        if (message.sender == "system") return

        val resolution = resolveContact(peerID)
        val canonicalConv = resolution?.conversationID
            ?: ContactDirectory.canonicalConversationId(peerID)
        val nostrHex = message.senderNostrPubkey?.let(ContactIdentityResolver::nostrPubkeyHex)
            ?: resolution?.nostrPubkey?.let(ContactIdentityResolver::nostrPubkeyHex)
        val noiseHex = resolution?.noiseKeyHex
        val peer = resolution?.meshPeerID
            ?: peerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }

        val direction = determineDirection(message)
        val delivery = InternetSyncProtocol.encodeDeliveryStatus(message.deliveryStatus)
        val op = InternetSyncProtocol.SyncOp.MsgOp(
            id = message.id,
            conv = canonicalConv,
            peer = peer,
            noiseKeyHex = noiseHex,
            nostrPubkeyHex = nostrHex,
            sender = message.sender,
            content = InternetSyncProtocol.contentForSync(message),
            timestampMs = message.timestamp.time.coerceAtLeast(1L),
            direction = direction,
            deliveryState = delivery.state,
            statusActor = delivery.actor,
            statusAtMs = delivery.atMs,
            failureReason = delivery.reason,
            isRead = isRead || direction == InternetSyncProtocol.Direction.OUT
        )
        enqueueOp(op)
    }

    fun enqueueMessageRead(conversationID: String, messageID: String) {
        if (!isSyncEnabled()) return
        if (messageID.isBlank()) return
        val resolution = if (conversationID.isNotBlank()) resolveContact(conversationID) else null
        val conv = resolution?.conversationID
            ?: conversationID.takeIf { it.isNotBlank() }?.let(ContactDirectory::canonicalConversationId)
            ?: "_"
        val op = InternetSyncProtocol.SyncOp.ReadOp(
            conv = conv,
            peer = resolution?.meshPeerID,
            noiseKeyHex = resolution?.noiseKeyHex,
            nostrPubkeyHex = resolution?.nostrPubkey?.let(ContactIdentityResolver::nostrPubkeyHex),
            messageIds = listOf(messageID),
            markAll = false,
            timestampMs = clockMs().coerceAtLeast(1L)
        )
        enqueueOp(op)
    }

    fun enqueueConversationRead(conversationID: String) {
        if (!isSyncEnabled()) return
        if (conversationID.isBlank()) return
        val resolution = resolveContact(conversationID)
        val conv = resolution?.conversationID ?: ContactDirectory.canonicalConversationId(conversationID)
        val op = InternetSyncProtocol.SyncOp.ReadOp(
            conv = conv,
            peer = resolution?.meshPeerID,
            noiseKeyHex = resolution?.noiseKeyHex,
            nostrPubkeyHex = resolution?.nostrPubkey?.let(ContactIdentityResolver::nostrPubkeyHex),
            messageIds = emptyList(),
            markAll = true,
            timestampMs = clockMs().coerceAtLeast(1L)
        )
        enqueueOp(op)
    }

    fun clearPending() {
        synchronized(lock) {
            pendingOps.clear()
            flushJob?.cancel()
            flushJob = null
        }
    }

    fun flushNow(): Int {
        val opsToPublish: List<InternetSyncProtocol.SyncOp>
        synchronized(lock) {
            flushJob?.cancel()
            flushJob = null
            if (!isSyncEnabled()) {
                pendingOps.clear()
                return 0
            }
            if (pendingOps.isEmpty()) return 0
            opsToPublish = pendingOps.toList()
            pendingOps.clear()
        }

        val identity = identityProvider()
        if (identity == null) {
            Log.w(TAG, "Cannot publish internet sync: Nostr identity unavailable")
            return 0
        }

        val deviceId = deviceIdProvider()
        val payloads = InternetSyncProtocol.batchOps(
            deviceId = deviceId,
            ops = opsToPublish,
            maxOps = maxOpsPerEvent,
            maxBytes = maxPayloadBytes
        )

        var published = 0
        payloads.forEach { payload ->
            try {
                val wraps = NostrProtocol.createPrivateMessage(
                    content = payload,
                    recipientPubkey = identity.publicKeyHex,
                    senderIdentity = identity
                )
                wraps.forEach { wrap ->
                    publishGiftWrap(wrap)
                    published++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to publish sync gift wrap: ${e.message}")
            }
        }
        return published
    }

    private fun enqueueOp(op: InternetSyncProtocol.SyncOp) {
        synchronized(lock) {
            if (!isSyncEnabled()) return
            pendingOps.add(op)
            if (flushJob?.isActive != true) {
                flushJob = scope.launch {
                    if (debounceMs > 0L) {
                        delay(debounceMs)
                    }
                    flushNow()
                }
            }
        }
    }

    private fun determineDirection(message: BitchatMessage): InternetSyncProtocol.Direction {
        return when (message.deliveryStatus) {
            is DeliveryStatus.Sending,
            is DeliveryStatus.Sent,
            is DeliveryStatus.PartiallyDelivered,
            is DeliveryStatus.Failed -> InternetSyncProtocol.Direction.OUT
            else -> {
                val myNick = myNicknameProvider()?.takeIf { it.isNotBlank() }
                if (myNick != null && message.sender == myNick) {
                    InternetSyncProtocol.Direction.OUT
                } else {
                    InternetSyncProtocol.Direction.IN
                }
            }
        }
    }
}
