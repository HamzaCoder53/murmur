package com.bitchat.desktop.ble

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.desktop.platform.BleLink
import com.bitchat.desktop.platform.BleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleMeshTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private class FakeBleTransport : BleTransport {
        override val isAvailable: Boolean = true
        var listener: BleTransport.Listener? = null
        val sentFrames = mutableListOf<Pair<String, ByteArray>>()
        val broadcastFrames = mutableListOf<Pair<ByteArray, String?>>()

        override fun start(listener: BleTransport.Listener) { this.listener = listener }
        override fun stop() { listener = null }
        override fun send(linkId: String, frame: ByteArray): Boolean {
            sentFrames += linkId to frame
            return true
        }
        override fun broadcast(frame: ByteArray, except: String?): Int {
            broadcastFrames += frame to except
            return 1
        }
    }

    @Test
    fun `decodes incoming BLE frames, maps peer to link, and routes outbound packets`() {
        val fakeBle = FakeBleTransport()
        val received = mutableListOf<Triple<BitchatPacket, String, String>>()
        val mesh = BleMeshTransport(
            ble = fakeBle,
            scope = scope,
            onPacketReceived = { pkt, peer, link -> received += Triple(pkt, peer, link) },
        )
        mesh.start()

        val senderBytes = ByteArray(8) { (it + 1).toByte() }
        val expectedPeerId = senderBytes.joinToString("") { "%02x".format(it) }
        val inbound = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = senderBytes,
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = 1_700_000_000_000u,
            payload = "synthetic ble hello".toByteArray(),
            signature = null,
            ttl = 5u,
        )
        val wire = inbound.toBinaryData(padding = false)!!
        fakeBle.listener!!.onFrame("opaque-link-1", wire)

        assertEquals(1, received.size)
        assertEquals(expectedPeerId, received[0].second)
        assertEquals("opaque-link-1", mesh.getDeviceAddressForPeer(expectedPeerId))

        assertTrue(mesh.sendPacketToPeer(expectedPeerId, inbound))
        assertEquals(1, fakeBle.sentFrames.size)
        assertEquals("opaque-link-1", fakeBle.sentFrames[0].first)
        assertNotNull(BitchatPacket.fromBinaryData(fakeBle.sentFrames[0].second))

        assertTrue(mesh.broadcastPacket(RoutedPacket(inbound)))
        assertEquals(1, fakeBle.broadcastFrames.size)

        fakeBle.listener!!.onLinkDown("opaque-link-1")
        assertFalse(mesh.sendPacketToPeer(expectedPeerId, inbound))
    }
}
