package com.bitchat.android.wearlink

import android.util.Log
import com.bitchat.android.mesh.FragmentManager
import com.bitchat.android.mesh.FragmentingPacketSender
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Mesh transport that moves packets between a phone and its paired watch over a
 * [WearLinkChannel]. Used unchanged on both ends of the tunnel.
 *
 * @param defaultRoute true on the watch, where the phone is the route to every peer; false on the
 *   phone, where the tunnel only reaches peers that announced themselves over it.
 */
class WearLinkTransport(
    private val scope: CoroutineScope,
    private val channel: WearLinkChannel,
    private val defaultRoute: Boolean
) : MeshTransport {

    private companion object {
        const val TAG = "WearLinkTransport"
    }

    /** Receives every packet that arrives over the tunnel. */
    interface Listener {
        fun onPacket(packet: BitchatPacket, peerID: String, linkAddress: String)

        /** A peer bound to the tunnel is no longer reachable through it. */
        fun onPeerUnreachable(peerID: String) = Unit
    }

    override val id: String = WearLinkContract.TRANSPORT_ID

    @Volatile
    var listener: Listener? = null

    @Volatile
    private var sender: FragmentingPacketSender? = null

    /** linkAddress -> peerID of the device on the other end, learned from its direct announce. */
    private val peerByLink = ConcurrentHashMap<String, String>()

    private var nodeWatcher: Job? = null

    val reachableNodes get() = channel.reachableNodes

    val isLinked: Boolean get() = channel.reachableNodes.value.isNotEmpty()

    /** MeshCore owns the fragment manager but needs its transport first, so attach it afterwards. */
    fun attachFragmentManager(fragmentManager: FragmentManager) {
        sender = FragmentingPacketSender(scope, fragmentManager, TAG)
    }

    fun start() {
        channel.onMessage = ::handleMessage
        channel.start()
        if (nodeWatcher == null) {
            nodeWatcher = scope.launch {
                channel.reachableNodes.collect { nodes -> dropUnreachableBindings(nodes) }
            }
        }
    }

    fun stop() {
        nodeWatcher?.cancel()
        nodeWatcher = null
        channel.stop()
        channel.onMessage = null
        dropUnreachableBindings(emptySet())
    }

    /** Record that [peerID] is the device directly on the other end of [linkAddress]. */
    fun bindPeer(linkAddress: String, peerID: String): Boolean {
        if (!WearLinkContract.isLinkAddress(linkAddress)) return false
        if (WearLinkContract.nodeIdOf(linkAddress) !in channel.reachableNodes.value) return false
        peerByLink[linkAddress] = peerID
        return true
    }

    override fun broadcastPacket(routed: RoutedPacket): Boolean {
        // Never hand a packet back to the link it arrived on.
        val targets = channel.reachableNodes.value.filter {
            WearLinkContract.linkAddress(it) != routed.relayAddress
        }
        return sendTo(targets, routed)
    }

    override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        val bound = peerByLink.entries.firstOrNull { it.value == peerID }?.key
        val targets = when {
            bound != null -> listOf(WearLinkContract.nodeIdOf(bound))
            defaultRoute -> channel.reachableNodes.value.toList()
            else -> emptyList()
        }
        return sendTo(targets, RoutedPacket(packet))
    }

    override fun sendPacketToLink(
        relayAddress: String,
        ingressLinkID: String,
        packet: BitchatPacket
    ): Boolean {
        if (!WearLinkContract.isLinkAddress(relayAddress)) return false
        val nodeId = WearLinkContract.nodeIdOf(relayAddress)
        if (nodeId !in channel.reachableNodes.value) return false
        return sendTo(listOf(nodeId), RoutedPacket(packet))
    }

    override fun cancelTransfer(transferId: String): Boolean =
        sender?.cancelTransfer(transferId) ?: false

    override fun getDeviceAddressForPeer(peerID: String): String? =
        peerByLink.entries.firstOrNull { it.value == peerID }?.key

    override fun getDeviceAddressToPeerMapping(): Map<String, String> = HashMap(peerByLink)

    override fun getTransportDebugInfo(): String =
        "Wear link: nodes=${channel.reachableNodes.value.size} boundPeers=${peerByLink.size}"

    private fun sendTo(nodeIds: List<String>, routed: RoutedPacket): Boolean {
        if (nodeIds.isEmpty()) return false
        val fragmentingSender = sender ?: return false
        return fragmentingSender.send(routed, "wear link") { single ->
            val data = single.packet.toBinaryData() ?: return@send false
            var accepted = false
            for (nodeId in nodeIds) {
                if (channel.send(nodeId, data)) accepted = true
            }
            accepted
        }
    }

    private fun handleMessage(nodeId: String, data: ByteArray) {
        val packet = BitchatPacket.fromBinaryData(data) ?: run {
            Log.d(TAG, "Dropped undecodable tunnel message (${data.size} bytes)")
            return
        }
        val peerID = packet.senderID.toHexString().take(16)
        listener?.onPacket(packet, peerID, WearLinkContract.linkAddress(nodeId))
    }

    private fun dropUnreachableBindings(reachable: Set<String>) {
        val iterator = peerByLink.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (WearLinkContract.nodeIdOf(entry.key) !in reachable) {
                iterator.remove()
                try {
                    listener?.onPeerUnreachable(entry.value)
                } catch (e: Exception) {
                    Log.w(TAG, "Peer-unreachable handler failed: ${e.message}")
                }
            }
        }
    }
}
