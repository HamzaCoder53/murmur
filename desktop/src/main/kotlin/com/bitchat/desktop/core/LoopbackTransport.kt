package com.bitchat.desktop.core

import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.toHexString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process fake radio connecting several desktop nodes, used by tests and as the reference
 * shape for the real BLE/LAN transports. Every packet crosses the hub as wire bytes
 * (BinaryProtocol encode -> decode), so the full codec runs exactly as over Bluetooth.
 * Each endpoint receives on its own single thread, preserving per-link order like a GATT link.
 */
class LoopbackHub {
    private val endpoints = ConcurrentHashMap<String, LoopbackTransport>()
    private val nextLink = AtomicInteger(1)

    /** Creates an endpoint; [peerID] lets sendPacketToPeer find it (the real transports bind on announce). */
    fun endpoint(peerID: String): LoopbackTransport {
        val link = "loop-${nextLink.getAndIncrement()}"
        return LoopbackTransport(this, link, peerID).also { endpoints[link] = it }
    }

    internal fun deliver(from: LoopbackTransport, to: LoopbackTransport, bytes: ByteArray) {
        to.receive(bytes, fromLink = from.linkAddress)
    }

    internal fun others(self: LoopbackTransport): List<LoopbackTransport> =
        endpoints.values.filter { it !== self && it.isOpen }

    internal fun byPeer(peerID: String): LoopbackTransport? =
        endpoints.values.firstOrNull { it.peerID == peerID && it.isOpen }

    internal fun remove(endpoint: LoopbackTransport) {
        endpoints.remove(endpoint.linkAddress)
    }
}

class LoopbackTransport internal constructor(
    private val hub: LoopbackHub,
    val linkAddress: String,
    val peerID: String,
) : MeshTransport {
    fun interface Listener {
        fun onPacket(packet: BitchatPacket, senderPeerID: String, linkAddress: String)
    }

    override val id: String = "loopback:$linkAddress"

    @Volatile
    var listener: Listener? = null

    @Volatile
    internal var isOpen = true
        private set

    private val inbox: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "loopback-$linkAddress").apply { isDaemon = true }
    }

    override fun broadcastPacket(routed: RoutedPacket): Boolean {
        val bytes = routed.packet.toBinaryData() ?: return false
        var accepted = false
        for (other in hub.others(this)) {
            // Never hand a packet back over the link it arrived on.
            if (other.linkAddress == routed.relayAddress) continue
            hub.deliver(this, other, bytes)
            accepted = true
        }
        return accepted
    }

    override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        val target = hub.byPeer(peerID) ?: return false
        val bytes = packet.toBinaryData() ?: return false
        hub.deliver(this, target, bytes)
        return true
    }

    override fun getDeviceAddressForPeer(peerID: String): String? = hub.byPeer(peerID)?.linkAddress

    override fun getDeviceAddressToPeerMapping(): Map<String, String> =
        hub.others(this).associate { it.linkAddress to it.peerID }

    override fun getTransportDebugInfo(): String = "Loopback: links=${hub.others(this).size}"

    internal fun receive(bytes: ByteArray, fromLink: String) {
        if (!isOpen) return
        inbox.execute {
            val packet = BitchatPacket.fromBinaryData(bytes) ?: return@execute
            val sender = packet.senderID.toHexString().take(16)
            listener?.onPacket(packet, sender, fromLink)
        }
    }

    fun close() {
        isOpen = false
        hub.remove(this)
        inbox.shutdownNow()
    }
}
