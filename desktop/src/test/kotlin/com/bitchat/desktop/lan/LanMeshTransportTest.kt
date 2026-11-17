package com.bitchat.desktop.lan

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.desktop.platform.NoopLocalDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LanMeshTransportTest {
    @Test
    fun `exchanges BitchatPackets over loopback TCP sockets`() {
        val received = mutableListOf<Pair<BitchatPacket, String>>()
        val latch = CountDownLatch(1)

        val peerA = "0102030405060708"
        val peerB = "1112131415161718"
        val lanA = LanMeshTransport(peerA, NoopLocalDiscovery) { _, _, _ -> }
        val lanB = LanMeshTransport(peerB, NoopLocalDiscovery) { pkt, sender, _ ->
            received += pkt to sender
            latch.countDown()
        }

        lanA.start(0)
        lanB.start(0)
        try {
            assertTrue(lanA.connectTo("127.0.0.1", lanB.listeningPort))
            val pkt = BitchatPacket(
                version = 1u,
                type = MessageType.MESSAGE.value,
                senderID = ByteArray(8) { (it + 1).toByte() },
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = 1_700_000_000_000u,
                payload = "synthetic lan hello".toByteArray(),
                signature = null,
                ttl = 5u,
            )
            assertTrue(lanA.broadcastPacket(RoutedPacket(pkt)))
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(peerA, received.single().second)
            assertEquals("synthetic lan hello", String(received.single().first.payload))
        } finally {
            lanA.stop()
            lanB.stop()
        }
    }
}
