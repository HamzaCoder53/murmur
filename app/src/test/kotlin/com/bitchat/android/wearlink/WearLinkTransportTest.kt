package com.bitchat.android.wearlink

import com.bitchat.android.mesh.FragmentManager
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearLinkTransportTest {

    /** One end of an in-memory Data Layer: delivers straight to the paired channel. */
    private class FakeChannel(val selfNode: String) : WearLinkChannel {
        override val reachableNodes = MutableStateFlow<Set<String>>(emptySet())
        override var onMessage: ((nodeId: String, data: ByteArray) -> Unit)? = null
        var peer: FakeChannel? = null
        val sent = ArrayList<Pair<String, ByteArray>>()

        override fun start() = Unit
        override fun stop() = Unit
        override fun send(nodeId: String, data: ByteArray): Boolean {
            sent += nodeId to data
            peer?.takeIf { it.selfNode == nodeId }?.onMessage?.invoke(selfNode, data)
            return true
        }
    }

    private class Received(val packet: BitchatPacket, val peerID: String, val linkAddress: String)

    private class Recorder : WearLinkTransport.Listener {
        val packets = ArrayList<Received>()
        val unreachable = ArrayList<String>()
        override fun onPacket(packet: BitchatPacket, peerID: String, linkAddress: String) {
            packets += Received(packet, peerID, linkAddress)
        }
        override fun onPeerUnreachable(peerID: String) {
            unreachable += peerID
        }
    }

    private val watchChannel = FakeChannel("watch-node")
    private val phoneChannel = FakeChannel("phone-node")
    private val watchSide = Recorder()
    private val phoneSide = Recorder()

    private val watchPeer = "1111111111111111"
    private val phonePeer = "2222222222222222"
    private val phoneLinkAddress = WearLinkContract.linkAddress("phone-node")
    private val watchLinkAddress = WearLinkContract.linkAddress("watch-node")

    private var clockMs = 1_000_000L

    private fun TestScope.linkedPair(): Pair<WearLinkTransport, WearLinkTransport> {
        watchChannel.peer = phoneChannel
        phoneChannel.peer = watchChannel
        watchChannel.reachableNodes.value = setOf("phone-node")
        phoneChannel.reachableNodes.value = setOf("watch-node")
        val watch = WearLinkTransport(backgroundScope, watchChannel, defaultRoute = true, now = { clockMs }).apply {
            attachFragmentManager(FragmentManager())
            listener = watchSide
            start()
        }
        val phone = WearLinkTransport(backgroundScope, phoneChannel, defaultRoute = false, now = { clockMs }).apply {
            attachFragmentManager(FragmentManager())
            listener = phoneSide
            start()
        }
        runCurrent()
        return watch to phone
    }

    private fun packet(sender: String = watchPeer, payload: ByteArray = "hello mesh".toByteArray()) =
        BitchatPacket(type = MessageType.MESSAGE.value, ttl = 7u, senderID = sender, payload = payload)

    @Test
    fun `a packet sent by the watch arrives at the phone unchanged`() = runTest {
        val (watch, _) = linkedPair()
        val sent = packet()

        assertTrue(watch.broadcastPacket(RoutedPacket(sent)))

        val received = phoneSide.packets.single()
        assertEquals(sent.type, received.packet.type)
        assertEquals(sent.ttl, received.packet.ttl)
        assertEquals(sent.timestamp, received.packet.timestamp)
        assertArrayEquals(sent.senderID, received.packet.senderID)
        assertArrayEquals(sent.payload, received.packet.payload)
        assertEquals(watchPeer, received.peerID)
        assertEquals(watchLinkAddress, received.linkAddress)
    }

    @Test
    fun `a packet sent by the phone arrives at the watch`() = runTest {
        val (_, phone) = linkedPair()

        assertTrue(phone.broadcastPacket(RoutedPacket(packet(sender = phonePeer))))

        assertEquals(phonePeer, watchSide.packets.single().peerID)
        assertEquals(phoneLinkAddress, watchSide.packets.single().linkAddress)
    }

    @Test
    fun `a relayed packet is not sent back to the link it arrived on`() = runTest {
        val (_, phone) = linkedPair()

        val accepted = phone.broadcastPacket(RoutedPacket(packet(), relayAddress = watchLinkAddress))

        assertFalse(accepted)
        assertTrue(phoneChannel.sent.isEmpty())
    }

    @Test
    fun `broadcast with nobody reachable is refused`() = runTest {
        val (watch, _) = linkedPair()
        watchChannel.reachableNodes.value = emptySet()

        assertFalse(watch.broadcastPacket(RoutedPacket(packet())))
    }

    @Test
    fun `the watch routes any peer through the phone`() = runTest {
        val (watch, _) = linkedPair()

        assertTrue(watch.sendPacketToPeer("9999999999999999", packet()))

        assertEquals("phone-node", watchChannel.sent.single().first)
    }

    @Test
    fun `the phone only uses the tunnel for a peer bound to it`() = runTest {
        val (_, phone) = linkedPair()

        assertFalse(phone.sendPacketToPeer(watchPeer, packet(sender = phonePeer)))

        assertTrue(phone.bindPeer(watchLinkAddress, watchPeer))
        assertTrue(phone.sendPacketToPeer(watchPeer, packet(sender = phonePeer)))
        assertEquals(watchLinkAddress, phone.getDeviceAddressForPeer(watchPeer))
    }

    @Test
    fun `binding is refused for a non tunnel address or an unreachable node`() = runTest {
        val (_, phone) = linkedPair()

        assertFalse(phone.bindPeer("AA:BB:CC:DD:EE:FF", watchPeer))
        assertFalse(phone.bindPeer(WearLinkContract.linkAddress("stranger"), watchPeer))
        assertNull(phone.getDeviceAddressForPeer(watchPeer))
    }

    @Test
    fun `a node that goes away is unbound and reported`() = runTest {
        val (_, phone) = linkedPair()
        phone.bindPeer(watchLinkAddress, watchPeer)

        phoneChannel.reachableNodes.value = emptySet()
        runCurrent()

        assertEquals(listOf(watchPeer), phoneSide.unreachable)
        assertNull(phone.getDeviceAddressForPeer(watchPeer))
    }

    @Test
    fun `stop unbinds peers`() = runTest {
        val (_, phone) = linkedPair()
        phone.bindPeer(watchLinkAddress, watchPeer)

        phone.stop()

        assertEquals(listOf(watchPeer), phoneSide.unreachable)
        assertTrue(phone.getDeviceAddressToPeerMapping().isEmpty())
    }

    @Test
    fun `a reply to a link goes only to that reachable node`() = runTest {
        val (_, phone) = linkedPair()

        assertTrue(phone.sendPacketToLink(watchLinkAddress, watchLinkAddress, packet(sender = phonePeer)))
        assertFalse(phone.sendPacketToLink("AA:BB:CC:DD:EE:FF", "link", packet(sender = phonePeer)))
        assertFalse(
            phone.sendPacketToLink(
                WearLinkContract.linkAddress("stranger"),
                "link",
                packet(sender = phonePeer)
            )
        )
        assertEquals(1, watchSide.packets.size)
    }

    @Test
    fun `a node heard from counts as live before the reachability check catches up`() = runTest {
        val (_, phone) = linkedPair()
        phoneChannel.reachableNodes.value = emptySet()
        runCurrent()

        // The watch speaks first over a link the phone does not yet see as reachable.
        phoneChannel.onMessage?.invoke("watch-node", packet().toBinaryData()!!)

        assertTrue(phone.bindPeer(watchLinkAddress, watchPeer))
        assertTrue(phone.sendPacketToPeer(watchPeer, packet(sender = phonePeer)))
    }

    @Test
    fun `a node that only went quiet is unbound once it stops being fresh`() = runTest {
        val (_, phone) = linkedPair()
        phoneChannel.reachableNodes.value = emptySet()
        runCurrent()
        phoneChannel.onMessage?.invoke("watch-node", packet().toBinaryData()!!)
        phone.bindPeer(watchLinkAddress, watchPeer)

        clockMs += WearLinkTransport.HEARD_FRESH_MS + 1
        advanceTimeBy(WearLinkTransport.HEARD_FRESH_MS)
        runCurrent()

        assertEquals(listOf(watchPeer), phoneSide.unreachable)
        assertFalse(phone.sendPacketToPeer(watchPeer, packet(sender = phonePeer)))
    }

    @Test
    fun `bytes that are not a packet are dropped`() = runTest {
        linkedPair()

        phoneChannel.onMessage?.invoke("watch-node", byteArrayOf(1, 2, 3))

        assertTrue(phoneSide.packets.isEmpty())
    }

    @Test
    fun `a large packet crosses the tunnel as fragments and reassembles`() = runTest {
        val (watch, _) = linkedPair()
        // Seeded noise: a regular pattern would compress below the fragmentation threshold.
        val payload = ByteArray(3_000).also { java.util.Random(7).nextBytes(it) }

        assertTrue(watch.broadcastPacket(RoutedPacket(packet(payload = payload))))
        // Fragments go out from the background scope, which advanceUntilIdle does not drive.
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(phoneSide.packets.size > 1)
        assertTrue(phoneSide.packets.all { it.packet.type == MessageType.FRAGMENT.value })
        val reassembler = FragmentManager()
        val reassembled = phoneSide.packets.firstNotNullOfOrNull { reassembler.handleFragment(it.packet) }
        assertNotNull(reassembled)
        assertArrayEquals(payload, reassembled!!.payload)
    }
}
