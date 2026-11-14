package com.bitchat.watch.link

import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.wearlink.WearLinkContract

/**
 * The single transport the watch's MeshCore sees. Routes each send to the watch's own Bluetooth
 * mesh or to the phone tunnel according to the current [LinkMode], so the mesh layer above never
 * needs to know which radio is carrying its packets.
 */
class SwitchingTransport(
    private val bluetooth: MeshTransport,
    private val phoneLink: MeshTransport,
    private val mode: () -> LinkMode
) : MeshTransport {

    /** Kept as the Bluetooth id: nothing on the watch registers transports by id. */
    override val id: String = bluetooth.id

    private val active: MeshTransport
        get() = if (mode() == LinkMode.TETHERED) phoneLink else bluetooth

    override fun broadcastPacket(routed: RoutedPacket): Boolean = active.broadcastPacket(routed)

    /**
     * A peer known to be on the far end of the phone tunnel (the phone itself) is always reached
     * through it, even in the moments before the mode switch to TETHERED lands.
     */
    override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        val transport = if (phoneLink.getDeviceAddressForPeer(peerID) != null) phoneLink else active
        return transport.sendPacketToPeer(peerID, packet)
    }

    /** A reply to a specific link has to leave on that link, whatever the current mode. */
    override fun sendPacketToLink(
        relayAddress: String,
        ingressLinkID: String,
        packet: BitchatPacket
    ): Boolean = transportFor(relayAddress).sendPacketToLink(relayAddress, ingressLinkID, packet)

    override fun cancelTransfer(transferId: String): Boolean {
        val cancelledOnBluetooth = bluetooth.cancelTransfer(transferId)
        val cancelledOnPhoneLink = phoneLink.cancelTransfer(transferId)
        return cancelledOnBluetooth || cancelledOnPhoneLink
    }

    override fun getDeviceAddressForPeer(peerID: String): String? =
        active.getDeviceAddressForPeer(peerID)

    override fun getDeviceAddressToPeerMapping(): Map<String, String> =
        bluetooth.getDeviceAddressToPeerMapping() + phoneLink.getDeviceAddressToPeerMapping()

    override fun getTransportDebugInfo(): String =
        "Mode: ${mode()}\n${phoneLink.getTransportDebugInfo()}\n${bluetooth.getTransportDebugInfo()}"

    private fun transportFor(relayAddress: String): MeshTransport =
        if (WearLinkContract.isLinkAddress(relayAddress)) phoneLink else bluetooth
}

/** A transport that reaches nobody: the phone side of a watch that shares the phone's identity. */
object NoTransport : MeshTransport {
    override val id: String = "NONE"
    override fun broadcastPacket(routed: RoutedPacket): Boolean = false
    override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean = false
}
