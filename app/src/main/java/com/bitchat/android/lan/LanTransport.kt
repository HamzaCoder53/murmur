package com.bitchat.android.lan

import android.util.Log
import com.bitchat.android.mesh.FragmentManager
import com.bitchat.android.mesh.FragmentingPacketSender
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mesh transport over TCP connections to Skein peers on the same Wi-Fi network.
 *
 * Owns framing, the per-connection reader and writer, the connection cap and the link-to-peer
 * bindings. It knows nothing about discovery or Android networking: [LanNetworkManager] hands it
 * connected [LanLink]s through [attach].
 *
 * A LAN peer gets no more trust than a BLE peer. Every packet goes to MeshCore, which applies the
 * usual signature, announcement and Noise checks; this class only rejects bytes that are not
 * well-formed packets.
 */
class LanTransport(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    maxConnections: Int = LanPeerTable.DEFAULT_MAX_CONNECTIONS,
    private val outboundQueueCapacity: Int = DEFAULT_OUTBOUND_QUEUE,
    private val now: () -> Long = System::currentTimeMillis
) : MeshTransport {

    companion object {
        private const val TAG = "LanTransport"
        const val TRANSPORT_ID = "LAN"
        const val DEFAULT_OUTBOUND_QUEUE = 512

        /** A new connection must deliver a valid packet within this time or be dropped. */
        const val HANDSHAKE_TIMEOUT_MS = 20_000

        /** After the first packet, a link silent for this long (no keepalive either) is dead. */
        const val IDLE_READ_TIMEOUT_MS = 120_000

        /** The writer sends a keepalive frame after this long without other output. */
        const val KEEPALIVE_INTERVAL_MS = 45_000L
    }

    interface Listener {
        fun onPacket(packet: BitchatPacket, peerID: String, linkAddress: String)

        /** A connection was accepted into the table. */
        fun onLinkUp(linkAddress: String) = Unit

        /** A peer's last LAN connection closed. */
        fun onPeerUnreachable(peerID: String) = Unit
    }

    private inner class Connection(val link: LanLink) {
        lateinit var linkAddress: String
        val outbound = Channel<ByteArray>(outboundQueueCapacity)
        val closed = AtomicBoolean(false)
        @Volatile var reader: Job? = null
        @Volatile var writer: Job? = null
    }

    override val id: String = TRANSPORT_ID

    @Volatile
    var listener: Listener? = null

    @Volatile
    private var sender: FragmentingPacketSender? = null

    private val table = LanPeerTable<Connection>(maxConnections)

    val connectionCount: Int get() = table.size

    fun isFull(): Boolean = table.isFull()

    /** True if a connection to [remoteHost] (an IP address as text) is already open. */
    fun hasConnectionTo(remoteHost: String): Boolean = table.hasHost(remoteHost)

    /** MeshCore owns the fragment manager but needs its transport first, so attach it afterwards. */
    fun attachFragmentManager(fragmentManager: FragmentManager) {
        sender = FragmentingPacketSender(scope, fragmentManager, TAG)
    }

    /**
     * Takes ownership of [link] and starts reading and writing on it. Returns the new link address,
     * or null (with the link closed) when the connection cap is reached.
     */
    fun attach(link: LanLink, remoteHost: String?): String? {
        val connection = Connection(link)
        val address = table.add(remoteHost, connection) ?: run {
            Log.d(TAG, "Connection cap reached; refusing LAN link")
            link.close()
            return null
        }
        connection.linkAddress = address
        try { link.setReadTimeout(HANDSHAKE_TIMEOUT_MS) } catch (_: Exception) { }
        connection.writer = scope.launch(ioDispatcher) { writeLoop(connection) }
        connection.reader = scope.launch(ioDispatcher) { readLoop(connection) }
        try { listener?.onLinkUp(address) } catch (e: Exception) {
            Log.w(TAG, "Link-up handler failed: ${e.message}")
        }
        return address
    }

    /** Record that [peerID] is the device directly on the other end of [linkAddress]. */
    fun bindPeer(linkAddress: String, peerID: String): Boolean = table.bind(linkAddress, peerID)

    /** Closes every connection, e.g. when Wi-Fi goes away or the transport stops. */
    fun closeAll() {
        table.snapshot().forEach { disconnect(it.value) }
    }

    override fun broadcastPacket(routed: RoutedPacket): Boolean {
        // Never hand a packet back to the connection it arrived on.
        val targets = table.snapshot()
            .filter { it.linkAddress != routed.relayAddress }
            .map { it.value }
        return sendTo(targets, routed)
    }

    override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        val address = table.linkForPeer(peerID) ?: return false
        val connection = table.get(address)?.value ?: return false
        return sendTo(listOf(connection), RoutedPacket(packet))
    }

    override fun sendPacketToLink(
        relayAddress: String,
        ingressLinkID: String,
        packet: BitchatPacket
    ): Boolean {
        // Link addresses are per-connection and never reused, so they are their own generation.
        if (!LanPeerTable.isLinkAddress(relayAddress) || relayAddress != ingressLinkID) return false
        val connection = table.get(relayAddress)?.value ?: return false
        return sendTo(listOf(connection), RoutedPacket(packet))
    }

    override fun cancelTransfer(transferId: String): Boolean =
        sender?.cancelTransfer(transferId) ?: false

    override fun getDeviceAddressForPeer(peerID: String): String? = table.linkForPeer(peerID)

    override fun getDeviceAddressToPeerMapping(): Map<String, String> = table.peerMapping()

    override fun getTransportDebugInfo(): String =
        "Local Wi-Fi: connections=${table.size}/${table.maxConnections} boundPeers=${table.peerMapping().size}"

    private fun sendTo(connections: List<Connection>, routed: RoutedPacket): Boolean {
        if (connections.isEmpty()) return false
        val fragmentingSender = sender ?: return false
        return fragmentingSender.send(routed, "local Wi-Fi") { single ->
            val data = single.packet.toBinaryData() ?: return@send false
            val frame = LanFrameCodec.encode(data) ?: run {
                Log.w(TAG, "Packet of ${data.size} bytes exceeds the LAN frame limit")
                return@send false
            }
            var accepted = false
            for (connection in connections) {
                if (enqueue(connection, frame)) accepted = true
            }
            accepted
        }
    }

    private fun enqueue(connection: Connection, frame: ByteArray): Boolean {
        if (connection.closed.get()) return false
        if (connection.outbound.trySend(frame).isSuccess) return true
        // A peer that stopped reading must not hold a slot or memory indefinitely.
        Log.w(TAG, "Outbound queue full; dropping LAN link")
        disconnect(connection)
        return false
    }

    private suspend fun writeLoop(connection: Connection) {
        try {
            val output = connection.link.output
            while (!connection.closed.get()) {
                val next = withTimeoutOrNull(KEEPALIVE_INTERVAL_MS) {
                    connection.outbound.receiveCatching()
                }
                val frame = when {
                    next == null -> LanFrameCodec.KEEPALIVE
                    next.isClosed -> break
                    else -> next.getOrNull() ?: break
                }
                output.write(frame)
                output.flush()
            }
        } catch (e: Exception) {
            if (!connection.closed.get()) Log.d(TAG, "LAN write failed: ${e.javaClass.simpleName}")
        } finally {
            disconnect(connection)
        }
    }

    private fun readLoop(connection: Connection) {
        try {
            val input = DataInputStream(BufferedInputStream(connection.link.input))
            val connectedAt = now()
            var handshakeDone = false
            while (!connection.closed.get()) {
                val frame = LanFrameCodec.readFrame(input) ?: break
                if (frame.isEmpty()) {
                    // Keepalives cannot hold a slot open for a peer that never sends a packet.
                    if (!handshakeDone && now() - connectedAt > HANDSHAKE_TIMEOUT_MS) {
                        throw LanFrameException("No packet within the handshake window")
                    }
                    continue
                }
                val packet = try {
                    BitchatPacket.fromBinaryData(frame)
                } catch (_: Exception) {
                    null
                } ?: throw LanFrameException("Undecodable packet")
                if (!handshakeDone) {
                    handshakeDone = true
                    try { connection.link.setReadTimeout(IDLE_READ_TIMEOUT_MS) } catch (_: Exception) { }
                }
                val peerID = packet.senderID.toHexString().take(16)
                try {
                    listener?.onPacket(packet, peerID, connection.linkAddress)
                } catch (e: Exception) {
                    Log.w(TAG, "Packet handler failed: ${e.message}")
                }
            }
        } catch (e: LanFrameException) {
            Log.w(TAG, "Dropping LAN link: ${e.message}")
        } catch (e: Exception) {
            if (!connection.closed.get()) Log.d(TAG, "LAN read ended: ${e.javaClass.simpleName}")
        } finally {
            disconnect(connection)
        }
    }

    private fun disconnect(connection: Connection) {
        if (!connection.closed.compareAndSet(false, true)) return
        connection.outbound.close()
        connection.link.close()
        connection.reader?.cancel()
        connection.writer?.cancel()
        val entry = table.remove(connection.linkAddress) ?: return
        val peerID = entry.peerID ?: return
        if (table.linkForPeer(peerID) == null) {
            try {
                listener?.onPeerUnreachable(peerID)
            } catch (e: Exception) {
                Log.w(TAG, "Peer-unreachable handler failed: ${e.message}")
            }
        }
    }
}
