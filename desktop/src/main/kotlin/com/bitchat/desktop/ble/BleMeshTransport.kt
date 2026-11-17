package com.bitchat.desktop.ble

import android.util.Log
import com.bitchat.android.mesh.BLEPacketPaddingPolicy
import com.bitchat.android.mesh.FragmentManager
import com.bitchat.android.mesh.FragmentingPacketSender
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.desktop.core.DirectLinkBinder
import com.bitchat.desktop.platform.BleLink
import com.bitchat.desktop.platform.BleState
import com.bitchat.desktop.platform.BleTransport
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges [BleTransport] (native helper stdio link) to [MeshTransport] for [com.bitchat.android.mesh.MeshCore].
 * Reuses [FragmentingPacketSender] and [BLEPacketPaddingPolicy] so wire framing matches Android/iOS.
 *
 * A peer is mapped to a link only through [bindPeer], which the node calls after MeshCore has
 * verified a direct (full-TTL) announce from that peer on that link. Raw packet headers never
 * change the mapping: they are unauthenticated and relayed packets carry other peers' IDs.
 */
class BleMeshTransport(
    private val ble: BleTransport,
    private val scope: CoroutineScope,
    fragmentManager: FragmentManager? = null,
    private val onPacketReceived: (packet: BitchatPacket, senderPeerID: String, linkId: String) -> Unit,
    private val onStateChanged: (BleState) -> Unit = {},
    private val onPeerUnreachable: (peerID: String) -> Unit = {},
) : MeshTransport, DirectLinkBinder {

    override val id: String = "ble"

    private val liveLinks = ConcurrentHashMap<String, BleLink>()
    private val linkToPeer = ConcurrentHashMap<String, String>()
    private val peerToLink = ConcurrentHashMap<String, String>()
    private val bindLock = Any()

    @Volatile
    private var sender = FragmentingPacketSender(scope, fragmentManager, TAG)

    /** Fragments outgoing packets with the node's [FragmentManager], as the phone's transports do. */
    fun attachFragmentManager(fragmentManager: FragmentManager) {
        sender = FragmentingPacketSender(scope, fragmentManager, TAG)
    }

    fun start() {
        ble.start(object : BleTransport.Listener {
            override fun onLinkUp(link: BleLink) {
                liveLinks[link.linkId] = link
            }

            override fun onLinkUpdated(link: BleLink) {
                liveLinks[link.linkId] = link
            }

            override fun onLinkDown(linkId: String) {
                liveLinks.remove(linkId)
                val lostPeer = synchronized(bindLock) {
                    val peer = linkToPeer.remove(linkId)
                    if (peer != null && peerToLink.remove(peer, linkId)) peer else null
                }
                lostPeer?.let(onPeerUnreachable)
            }

            override fun onFrame(linkId: String, frame: ByteArray) {
                val packet = BitchatPacket.fromBinaryData(frame) ?: return
                liveLinks.putIfAbsent(linkId, BleLink(linkId = linkId, rssi = null))
                val senderPeerID = packet.senderID.joinToString("") { "%02x".format(it) }
                onPacketReceived(packet, senderPeerID, linkId)
            }

            override fun onStateChanged(state: BleState) {
                onStateChanged.invoke(state)
            }

            override fun onError(message: String) {
                Log.w(TAG, message)
            }
        })
    }

    fun stop() {
        ble.stop()
        liveLinks.clear()
        synchronized(bindLock) {
            linkToPeer.clear()
            peerToLink.clear()
        }
    }

    override fun bindPeer(linkAddress: String, peerID: String): Boolean {
        if (!liveLinks.containsKey(linkAddress)) return false
        synchronized(bindLock) {
            // The peer moved to a new link: forget the old link's claim on it.
            peerToLink.put(peerID, linkAddress)?.let { old -> if (old != linkAddress) linkToPeer.remove(old, peerID) }
            // The link now belongs to this peer: drop whoever was bound to it before.
            linkToPeer.put(linkAddress, peerID)?.let { prev -> if (prev != peerID) peerToLink.remove(prev, linkAddress) }
        }
        return true
    }

    override fun broadcastPacket(routed: RoutedPacket): Boolean {
        return sender.send(routed, "ble-broadcast") { single ->
            val bytes = encodeForBle(single.packet) ?: return@send false
            ble.broadcast(bytes, except = single.relayAddress) > 0
        }
    }

    override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        val linkId = peerToLink[peerID] ?: return false
        return sender.send(RoutedPacket(packet), "ble-peer") { single ->
            val bytes = encodeForBle(single.packet) ?: return@send false
            ble.send(linkId, bytes)
        }
    }

    override fun sendPacketToLink(
        relayAddress: String,
        ingressLinkID: String,
        packet: BitchatPacket,
    ): Boolean {
        val linkId = ingressLinkID.ifEmpty { relayAddress }
        if (!liveLinks.containsKey(linkId)) return false
        return sender.send(RoutedPacket(packet), "ble-link") { single ->
            val bytes = encodeForBle(single.packet) ?: return@send false
            ble.send(linkId, bytes)
        }
    }

    override fun cancelTransfer(transferId: String): Boolean = sender.cancelTransfer(transferId)

    override fun getDeviceAddressForPeer(peerID: String): String? = peerToLink[peerID]

    override fun getDeviceAddressToPeerMapping(): Map<String, String> = linkToPeer.toMap()

    private fun encodeForBle(packet: BitchatPacket): ByteArray? =
        packet.toBinaryData(padding = BLEPacketPaddingPolicy.shouldPadForBLE(packet.type))

    companion object {
        private const val TAG = "BleMeshTransport"
    }
}
