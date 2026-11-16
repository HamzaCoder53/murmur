package com.bitchat.desktop.ble

import android.util.Log
import com.bitchat.android.mesh.BLEPacketPaddingPolicy
import com.bitchat.android.mesh.FragmentManager
import com.bitchat.android.mesh.FragmentingPacketSender
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.desktop.platform.BleLink
import com.bitchat.desktop.platform.BleState
import com.bitchat.desktop.platform.BleTransport
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges [BleTransport] (native helper stdio link) to [MeshTransport] for [com.bitchat.android.mesh.MeshCore].
 * Reuses [FragmentingPacketSender] and [BLEPacketPaddingPolicy] so wire framing matches Android/iOS.
 */
class BleMeshTransport(
    private val ble: BleTransport,
    scope: CoroutineScope,
    fragmentManager: FragmentManager? = null,
    private val onPacketReceived: (packet: BitchatPacket, senderPeerID: String, linkId: String) -> Unit,
    private val onStateChanged: (BleState) -> Unit = {},
) : MeshTransport {

    override val id: String = "ble"

    private val linkToPeer = ConcurrentHashMap<String, String>()
    private val peerToLink = ConcurrentHashMap<String, String>()
    private val sender = FragmentingPacketSender(scope, fragmentManager, TAG)

    fun start() {
        ble.start(object : BleTransport.Listener {
            override fun onLinkUp(link: BleLink) = Unit

            override fun onLinkDown(linkId: String) {
                val peer = linkToPeer.remove(linkId)
                if (peer != null) {
                    peerToLink.remove(peer, linkId)
                }
            }

            override fun onFrame(linkId: String, frame: ByteArray) {
                val packet = BitchatPacket.fromBinaryData(frame) ?: return
                val senderPeerID = packet.senderID.joinToString("") { "%02x".format(it) }
                linkToPeer[linkId] = senderPeerID
                peerToLink[senderPeerID] = linkId
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
        linkToPeer.clear()
        peerToLink.clear()
    }

    override fun broadcastPacket(routed: RoutedPacket): Boolean {
        return sender.send(routed, "ble-broadcast") { single ->
            val bytes = encodeForBle(single.packet) ?: return@send false
            ble.broadcast(bytes, except = single.relayAddress) > 0
        }
    }

    override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        val linkId = peerToLink[peerID] ?: return false
        val bytes = encodeForBle(packet) ?: return false
        return ble.send(linkId, bytes)
    }

    override fun sendPacketToLink(
        relayAddress: String,
        ingressLinkID: String,
        packet: BitchatPacket,
    ): Boolean {
        val linkId = ingressLinkID.ifEmpty { relayAddress }
        if (!linkToPeer.containsKey(linkId)) return false
        val bytes = encodeForBle(packet) ?: return false
        return ble.send(linkId, bytes)
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
