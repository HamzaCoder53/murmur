package com.bitchat.android.linked

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus

/** What this device does on the mesh right now. */
sealed interface LinkRole {
    /** No linked device reachable: run the mesh alone with the shared identity, keep a backlog. */
    object Standalone : LinkRole

    /** Linked devices reachable and this one leads: run the mesh for all of them. */
    object Leader : LinkRole

    /** A linked device with a smaller device ID is reachable: radios off, mirror [leaderId]. */
    data class Follower(val leaderId: String) : LinkRole
}

/**
 * Leader rule: among devices that reach each other directly, the one with the smallest device ID
 * leads. Each device ID is random per install and was exchanged authenticated at pairing.
 *
 * Why this rule: both ends compute it from the same authenticated inputs with no negotiation,
 * clock or tie (IDs are distinct), so two directly connected devices never both lead. It is stable
 * across restarts and reconnects, which avoids radio flapping, and needs no user setup. A device
 * that follows also relays: its own followers' commands go on to its leader, so chains still end at
 * exactly one radio.
 */
object LeaderElection {
    /**
     * Phones and tablets lead before watches: a watch's radio is weaker and its battery smaller,
     * so it mirrors whenever a linked phone is in reach. Watch device IDs carry
     * [LinkDeviceIds.WATCH_PREFIX]; among equals the smallest ID still wins.
     */
    private fun rank(id: String): String = (if (LinkDeviceIds.isWatch(id)) "1" else "0") + id

    fun roleFor(myDeviceId: String, reachable: Collection<String>): LinkRole {
        val others = reachable.filter { it != myDeviceId }
        if (others.isEmpty()) return LinkRole.Standalone
        val best = others.minBy(::rank)
        return if (rank(best) < rank(myDeviceId)) LinkRole.Follower(best) else LinkRole.Leader
    }
}

/** Device IDs: 32 hex characters; watches start with [WATCH_PREFIX], phones never do. */
object LinkDeviceIds {
    const val WATCH_PREFIX = "f7a7"

    fun isWatch(id: String): Boolean = id.startsWith(WATCH_PREFIX)

    fun generate(watch: Boolean, random: () -> String): String {
        while (true) {
            val id = random()
            if (watch) return WATCH_PREFIX + id.drop(WATCH_PREFIX.length)
            if (!isWatch(id)) return id
        }
    }
}

/**
 * Merges messages from a linked device into this one's history.
 *
 * Messages are deduplicated by ID. Read state is a union: once either device read a message it is
 * read on both. Delivery status keeps the furthest state either device saw, so a receipt that
 * reached only one device still shows on the other.
 */
object BacklogMerge {
    data class Plan(
        /** Not known here yet. */
        val add: List<LinkEntry>,
        /** Known, but the other device has a further delivery status (or media file). */
        val update: List<LinkEntry>,
        /** Known and unread here, read there. */
        val markRead: Set<String>
    )

    /** [known] maps message ID to (message, read) for what this device already has. */
    fun plan(known: Map<String, Pair<BitchatMessage, Boolean>>, incoming: List<LinkEntry>): Plan {
        val add = LinkedHashMap<String, LinkEntry>()
        val update = ArrayList<LinkEntry>()
        val markRead = LinkedHashSet<String>()
        incoming.forEach { entry ->
            val id = entry.message.id
            val local = known[id]
            if (local == null) {
                // The same message twice in one batch: keep the more advanced copy.
                val previous = add[id]
                add[id] = if (previous == null) entry else mergeEntry(previous, entry)
                return@forEach
            }
            val (message, read) = local
            if (rank(entry.message.deliveryStatus) > rank(message.deliveryStatus)) {
                update += entry.copy(message = message.copy(deliveryStatus = entry.message.deliveryStatus))
            }
            if (entry.read && !read) markRead += id
        }
        return Plan(add.values.toList(), update, markRead)
    }

    private fun mergeEntry(a: LinkEntry, b: LinkEntry): LinkEntry {
        val status = if (rank(b.message.deliveryStatus) > rank(a.message.deliveryStatus)) b.message.deliveryStatus
        else a.message.deliveryStatus
        return a.copy(message = a.message.copy(deliveryStatus = status), read = a.read || b.read)
    }

    /** Order of delivery states; a failure is only better than knowing nothing. */
    fun rank(status: DeliveryStatus?): Int = when (status) {
        null -> 0
        is DeliveryStatus.Failed -> 1
        is DeliveryStatus.Sending -> 2
        is DeliveryStatus.Sent, is DeliveryStatus.PartiallyDelivered -> 3
        is DeliveryStatus.Delivered -> 4
        is DeliveryStatus.Read -> 5
    }

    /** Favorites: per contact, the most recent change on either device wins. */
    fun mergeFavorites(local: List<LinkFavorite>, incoming: List<LinkFavorite>): List<LinkFavorite> {
        val mine = local.associateBy { it.noiseKeyHex }
        return incoming.filter { theirs ->
            val ours = mine[theirs.noiseKeyHex]
            if (ours == null) theirs.isFavorite else theirs.updatedAt > ours.updatedAt && theirs.isFavorite != ours.isFavorite
        }
    }
}
