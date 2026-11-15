package com.bitchat.watch.mesh

import com.bitchat.android.model.DeliveryStatus

/**
 * Keeps private messages the watch sent until the recipient confirms delivery, and decides when
 * to send them again.
 *
 * Bitchat sends a private message once. If the link drops at that moment (phone switched off, peer
 * walked away) it is lost. The outbox resends it, with the same message ID, whenever the recipient
 * is reachable again; the receiver discards duplicates by ID and acknowledges each copy, so a
 * resend can never show twice.
 *
 * Pure logic: the caller supplies the clock, delivery state and reachability, and performs sends.
 */
class DmOutbox(
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = MAX_ENTRIES
) {
    companion object {
        const val MAX_ENTRIES = 100
        const val EXPIRY_MS = 24 * 60 * 60_000L
        const val FIRST_RETRY_MS = 15_000L
        const val MAX_RETRY_MS = 5 * 60_000L
    }

    data class Entry(
        val messageID: String,
        val peerID: String,
        val recipientNickname: String,
        val content: String,
        val createdAtMs: Long,
        val lastAttemptMs: Long,
        val attempts: Int
    )

    /** What the caller should do for one entry right now. */
    sealed interface Action {
        val entry: Entry

        /** A session exists: send the message again. */
        data class Resend(override val entry: Entry) : Action

        /** The peer is reachable but has no session: start one; the next pass resends. */
        data class Handshake(override val entry: Entry) : Action

        /** Too old to deliver; mark it failed. */
        data class Expire(override val entry: Entry) : Action
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    val size: Int get() = synchronized(lock) { entries.size }

    fun track(messageID: String, peerID: String, recipientNickname: String, content: String) {
        synchronized(lock) {
            val t = now()
            entries[messageID] = Entry(messageID, peerID, recipientNickname, content, t, t, 1)
            while (entries.size > maxEntries) {
                entries.remove(entries.keys.first())
            }
        }
    }

    fun pending(): List<Entry> = synchronized(lock) { entries.values.toList() }

    /**
     * Work out what to do with every entry.
     *
     * @param statusOf current delivery state of a message, or null once it is gone from the chat
     * @param isReachable whether a peer is currently in range (directly or through the mesh)
     * @param hasSession whether an encrypted session with the peer is established
     * @param urgentPeers peers that just reappeared; their entries skip the backoff
     */
    fun due(
        statusOf: (String) -> DeliveryStatus?,
        isReachable: (String) -> Boolean,
        hasSession: (String) -> Boolean,
        urgentPeers: Set<String> = emptySet()
    ): List<Action> = synchronized(lock) {
        val t = now()
        val actions = ArrayList<Action>()
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            when (val status = statusOf(entry.messageID)) {
                null, is DeliveryStatus.Delivered, is DeliveryStatus.Read,
                is DeliveryStatus.Failed, is DeliveryStatus.PartiallyDelivered -> {
                    // Delivered, given up on elsewhere, or the conversation was deleted.
                    iterator.remove()
                    continue
                }
                else -> Unit
            }
            if (t - entry.createdAtMs > EXPIRY_MS) {
                iterator.remove()
                actions += Action.Expire(entry)
                continue
            }
            if (!isReachable(entry.peerID)) continue
            val urgent = entry.peerID in urgentPeers
            if (!urgent && t - entry.lastAttemptMs < backoffMs(entry.attempts)) continue
            actions += if (hasSession(entry.peerID)) Action.Resend(entry) else Action.Handshake(entry)
        }
        actions
    }

    /** Record that [action] was carried out, so the backoff applies to the next attempt. */
    fun attempted(action: Action) {
        if (action !is Action.Resend) return
        synchronized(lock) {
            val current = entries[action.entry.messageID] ?: return
            entries[current.messageID] = current.copy(lastAttemptMs = now(), attempts = current.attempts + 1)
        }
    }

    fun clear() = synchronized(lock) { entries.clear() }

    fun remove(messageID: String) = synchronized(lock) { entries.remove(messageID) }

    /** 15 s, 30 s, 60 s ... capped at 5 minutes. */
    internal fun backoffMs(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 10)
        return (FIRST_RETRY_MS shl shift).coerceAtMost(MAX_RETRY_MS)
    }
}
