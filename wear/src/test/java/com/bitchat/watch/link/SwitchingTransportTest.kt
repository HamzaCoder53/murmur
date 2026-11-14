package com.bitchat.watch.link

import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.wearlink.WearLinkContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchingTransportTest {

    private class RecordingTransport(override val id: String) : MeshTransport {
        val calls = ArrayList<String>()
        var addresses: Map<String, String> = emptyMap()

        override fun broadcastPacket(routed: RoutedPacket): Boolean {
            calls += "broadcast"
            return true
        }

        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
            calls += "peer:$peerID"
            return true
        }

        override fun sendPacketToLink(relayAddress: String, ingressLinkID: String, packet: BitchatPacket): Boolean {
            calls += "link:$relayAddress"
            return true
        }

        override fun getDeviceAddressToPeerMapping(): Map<String, String> = addresses

        override fun getDeviceAddressForPeer(peerID: String): String? =
            addresses.entries.firstOrNull { it.value == peerID }?.key
    }

    private val bluetooth = RecordingTransport("BLE")
    private val phoneLink = RecordingTransport(WearLinkContract.TRANSPORT_ID)
    private var mode = LinkMode.STANDALONE
    private val transport = SwitchingTransport(bluetooth, phoneLink) { mode }

    private val packet = BitchatPacket(
        type = 0x02u,
        ttl = 7u,
        senderID = "0102030405060708",
        payload = byteArrayOf(1, 2, 3)
    )

    @Test
    fun `standalone sends go out over bluetooth`() {
        transport.broadcastPacket(RoutedPacket(packet))
        transport.sendPacketToPeer("peer-a", packet)

        assertEquals(listOf("broadcast", "peer:peer-a"), bluetooth.calls)
        assertTrue(phoneLink.calls.isEmpty())
    }

    @Test
    fun `tethered sends go through the phone`() {
        mode = LinkMode.TETHERED

        transport.broadcastPacket(RoutedPacket(packet))
        transport.sendPacketToPeer("peer-a", packet)

        assertEquals(listOf("broadcast", "peer:peer-a"), phoneLink.calls)
        assertTrue(bluetooth.calls.isEmpty())
    }

    @Test
    fun `a peer bound to the phone tunnel is reached through it even when standalone`() {
        phoneLink.addresses = mapOf(WearLinkContract.linkAddress("node-1") to "phone-peer")

        transport.sendPacketToPeer("phone-peer", packet)
        transport.sendPacketToPeer("other-peer", packet)

        assertEquals(listOf("peer:phone-peer"), phoneLink.calls)
        assertEquals(listOf("peer:other-peer"), bluetooth.calls)
    }

    @Test
    fun `a reply to a tunnel link uses the tunnel even when standalone`() {
        val address = WearLinkContract.linkAddress("node-1")

        transport.sendPacketToLink(address, address, packet)

        assertEquals(listOf("link:$address"), phoneLink.calls)
        assertTrue(bluetooth.calls.isEmpty())
    }

    @Test
    fun `a reply to a bluetooth link uses bluetooth even when tethered`() {
        mode = LinkMode.TETHERED

        transport.sendPacketToLink("AA:BB:CC:DD:EE:FF", "link-7", packet)

        assertEquals(listOf("link:AA:BB:CC:DD:EE:FF"), bluetooth.calls)
        assertTrue(phoneLink.calls.isEmpty())
    }

    @Test
    fun `address map covers both transports`() {
        bluetooth.addresses = mapOf("AA:BB:CC:DD:EE:FF" to "peer-ble")
        phoneLink.addresses = mapOf(WearLinkContract.linkAddress("node-1") to "peer-phone")

        assertEquals(
            setOf("peer-ble", "peer-phone"),
            transport.getDeviceAddressToPeerMapping().values.toSet()
        )
    }
}
