package com.bitchat.android.lan

/**
 * The live LAN connections, keyed by link address, with the mesh peer learned on each.
 *
 * A link address ("lan:<n>") names one TCP connection and is never reused, so it doubles as the
 * exact ingress link generation MeshCore asks for. The table enforces the connection cap; it holds
 * no sockets itself, only whatever value the transport attaches.
 */
class LanPeerTable<T>(val maxConnections: Int = DEFAULT_MAX_CONNECTIONS) {

    companion object {
        const val DEFAULT_MAX_CONNECTIONS = 8
        private const val PREFIX = "lan:"

        fun isLinkAddress(value: String?): Boolean = value != null && value.startsWith(PREFIX)
    }

    class Entry<T>(
        val linkAddress: String,
        /** Remote IP as text, used only to avoid dialing a host we already reach. Never logged. */
        val remoteHost: String?,
        val value: T
    ) {
        @Volatile
        var peerID: String? = null
            internal set
    }

    init {
        require(maxConnections > 0) { "maxConnections must be positive" }
    }

    private val entries = LinkedHashMap<String, Entry<T>>()
    private var nextLinkNumber = 1L

    val size: Int @Synchronized get() = entries.size

    @Synchronized
    fun isFull(): Boolean = entries.size >= maxConnections

    /** Adds a connection and returns its new link address, or null when the table is full. */
    @Synchronized
    fun add(remoteHost: String?, value: T): String? {
        if (entries.size >= maxConnections) return null
        val address = PREFIX + nextLinkNumber++
        entries[address] = Entry(address, remoteHost, value)
        return address
    }

    @Synchronized
    fun remove(linkAddress: String): Entry<T>? = entries.remove(linkAddress)

    @Synchronized
    fun get(linkAddress: String): Entry<T>? = entries[linkAddress]

    /** Records that [peerID] sits directly on the other end of [linkAddress]. */
    @Synchronized
    fun bind(linkAddress: String, peerID: String): Boolean {
        val entry = entries[linkAddress] ?: return false
        entry.peerID = peerID
        return true
    }

    @Synchronized
    fun peerFor(linkAddress: String): String? = entries[linkAddress]?.peerID

    /** The newest connection bound to [peerID], if any. */
    @Synchronized
    fun linkForPeer(peerID: String): String? =
        entries.values.lastOrNull { it.peerID == peerID }?.linkAddress

    @Synchronized
    fun hasHost(remoteHost: String): Boolean = entries.values.any { it.remoteHost == remoteHost }

    @Synchronized
    fun snapshot(): List<Entry<T>> = entries.values.toList()

    /** linkAddress -> peerID for every bound connection. */
    @Synchronized
    fun peerMapping(): Map<String, String> {
        val mapping = LinkedHashMap<String, String>()
        for (entry in entries.values) {
            entry.peerID?.let { mapping[entry.linkAddress] = it }
        }
        return mapping
    }
}
