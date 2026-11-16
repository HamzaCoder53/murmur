package com.bitchat.android.lan

import com.bitchat.android.mesh.FragmentManager
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

@OptIn(ExperimentalCoroutinesApi::class)
class LanTransportTest {

    // Synthetic identifiers only.
    private val peerA = "1111111111111111"
    private val peerB = "2222222222222222"
    private val testHost = "192.0.2.20"

    private class Received(val packet: BitchatPacket, val peerID: String, val linkAddress: String)

    private class Recorder : LanTransport.Listener {
        val packets = CopyOnWriteArrayList<Received>()
        val unreachable = CopyOnWriteArrayList<String>()
        var onPacketHook: ((Received) -> Unit)? = null
        var onUnreachableHook: ((String) -> Unit)? = null

        override fun onPacket(packet: BitchatPacket, peerID: String, linkAddress: String) {
            val received = Received(packet, peerID, linkAddress)
            packets += received
            onPacketHook?.invoke(received)
        }

        override fun onPeerUnreachable(peerID: String) {
            unreachable += peerID
            onUnreachableHook?.invoke(peerID)
        }
    }

    /** A link over fixed bytes: reads them, then reports end of stream. */
    private class ScriptedLink(bytes: ByteArray) : LanLink {
        override val input: InputStream = ByteArrayInputStream(bytes)
        override val output = ByteArrayOutputStream()
        var closed = false
        override fun close() {
            closed = true
        }
    }

    /** A blocking in-memory byte pipe; stands in for one direction of a TCP connection. */
    private class MemoryPipe {
        private val queue = LinkedBlockingQueue<Int>()

        fun end() {
            queue.put(-1)
        }

        val input: InputStream = object : InputStream() {
            @Volatile private var ended = false

            override fun read(): Int {
                if (ended) return -1
                val value = queue.take()
                if (value < 0) ended = true
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val first = read()
                if (first < 0) return -1
                buffer[offset] = first.toByte()
                var count = 1
                while (count < length) {
                    val next = queue.peek() ?: break
                    if (next < 0) break
                    buffer[offset + count] = queue.take().toByte()
                    count++
                }
                return count
            }
        }

        val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                queue.put(b and 0xff)
            }
        }
    }

    private class PipeLink(private val incoming: MemoryPipe, private val outgoing: MemoryPipe) : LanLink {
        override val input: InputStream = incoming.input
        override val output: OutputStream = outgoing.output
        override fun close() {
            // Like closing a socket: the far side sees end of stream and our reader wakes up.
            outgoing.end()
            incoming.end()
        }
    }

    private fun packet(sender: String, text: String) = BitchatPacket(
        type = MessageType.MESSAGE.value,
        ttl = 7u,
        senderID = sender,
        payload = text.toByteArray()
    )

    private fun framed(packet: BitchatPacket): ByteArray = LanFrameCodec.encode(packet.toBinaryData()!!)!!

    private fun TestScope.transport(
        listener: LanTransport.Listener,
        maxConnections: Int = LanPeerTable.DEFAULT_MAX_CONNECTIONS,
        queue: Int = LanTransport.DEFAULT_OUTBOUND_QUEUE,
        now: () -> Long = { 0L }
    ) = LanTransport(
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        maxConnections = maxConnections,
        outboundQueueCapacity = queue,
        now = now
    ).apply {
        attachFragmentManager(FragmentManager())
        this.listener = listener
    }

    @Test
    fun `packets from a link reach the listener with the sender and link address`() = runTest {
        val recorder = Recorder()
        val lan = transport(recorder)
        val sent = packet(peerA, "first")
        val link = ScriptedLink(framed(sent) + LanFrameCodec.KEEPALIVE + framed(packet(peerA, "second")))

        val address = lan.attach(link, testHost)!!
        runCurrent()

        assertEquals(2, recorder.packets.size)
        val first = recorder.packets[0]
        assertEquals(peerA, first.peerID)
        assertEquals(address, first.linkAddress)
        assertArrayEquals(sent.payload, first.packet.payload)
        assertEquals("second", String(recorder.packets[1].packet.payload))
        // The scripted stream ended, so the connection is gone.
        assertTrue(link.closed)
        assertEquals(0, lan.connectionCount)
    }

    @Test
    fun `an oversize frame drops the connection without delivering anything`() = runTest {
        val recorder = Recorder()
        val lan = transport(recorder)
        val oversize = LanFrameCodec.MAX_FRAME_BYTES + 1
        val link = ScriptedLink(
            byteArrayOf((oversize ushr 24).toByte(), (oversize ushr 16).toByte(), (oversize ushr 8).toByte(), oversize.toByte()) +
                ByteArray(64)
        )

        lan.attach(link, testHost)
        runCurrent()

        assertTrue(recorder.packets.isEmpty())
        assertTrue(link.closed)
        assertEquals(0, lan.connectionCount)
    }

    @Test
    fun `a well framed but undecodable packet drops the connection`() = runTest {
        val recorder = Recorder()
        val lan = transport(recorder)
        val good = framed(packet(peerA, "never delivered after garbage"))
        val link = ScriptedLink(LanFrameCodec.encode(byteArrayOf(0x7f, 0x00, 0x01))!! + good)

        lan.attach(link, testHost)
        runCurrent()

        assertTrue(recorder.packets.isEmpty())
        assertTrue(link.closed)
    }

    @Test
    fun `keepalives alone cannot hold a connection past the handshake window`() = runTest {
        val recorder = Recorder()
        var clock = 0L
        val lan = transport(recorder, now = { clock.also { clock += 10_000L } })
        var exhausted = false
        // Keepalives that never block. Without the handshake rule the loop would only end when
        // this stream gives up, far beyond the few frames the rule allows.
        val endless = object : InputStream() {
            private var position = 0
            override fun read(): Int {
                if (++position > 1_000_000) {
                    exhausted = true
                    throw java.io.IOException("synthetic stream exhausted")
                }
                return 0
            }
        }
        var closed = false
        val link = object : LanLink {
            override val input: InputStream = endless
            override val output: OutputStream = ByteArrayOutputStream()
            override fun close() {
                closed = true
            }
        }

        lan.attach(link, testHost)
        runCurrent()

        assertTrue(closed)
        assertFalse("dropped by the handshake rule, not by the stream ending", exhausted)
        assertEquals(0, lan.connectionCount)
    }

    @Test
    fun `connections beyond the cap are refused and closed`() = runTest {
        val lan = transport(Recorder(), maxConnections = 2)
        val links = List(3) { ScriptedLink(ByteArray(0)) }

        assertNotNull(lan.attach(links[0], "192.0.2.1"))
        assertNotNull(lan.attach(links[1], "192.0.2.2"))
        assertNull(lan.attach(links[2], "192.0.2.3"))

        assertTrue(links[2].closed)
        assertFalse(links[0].closed)
        assertEquals(2, lan.connectionCount)
        assertTrue(lan.hasConnectionTo("192.0.2.1"))
    }

    @Test
    fun `a peer that stops reading is dropped when its queue fills`() = runTest {
        val lan = transport(Recorder(), queue = 1)
        val link = ScriptedLink(ByteArray(0))
        lan.attach(link, testHost)
        // The writer never runs: nothing advances the test dispatcher.

        assertTrue(lan.broadcastPacket(RoutedPacket(packet(peerA, "one"))))
        assertFalse(lan.broadcastPacket(RoutedPacket(packet(peerA, "two"))))

        assertTrue(link.closed)
        assertEquals(0, lan.connectionCount)
    }

    @Test
    fun `broadcast skips the link a packet arrived on`() = runTest {
        val lan = transport(Recorder(), queue = 4)
        val address = lan.attach(ScriptedLink(ByteArray(0)), testHost)!!

        assertFalse(lan.broadcastPacket(RoutedPacket(packet(peerA, "echo"), relayAddress = address)))
        assertTrue(lan.broadcastPacket(RoutedPacket(packet(peerA, "fresh"))))
    }

    @Test
    fun `exact link sends require the same live link generation`() = runTest {
        val lan = transport(Recorder(), queue = 4)
        val address = lan.attach(ScriptedLink(ByteArray(0)), testHost)!!

        assertTrue(lan.sendPacketToLink(address, address, packet(peerA, "ok")))
        assertFalse(lan.sendPacketToLink(address, "lan:999", packet(peerA, "other generation")))
        assertFalse(lan.sendPacketToLink("lan:999", "lan:999", packet(peerA, "unknown")))
        assertFalse(lan.sendPacketToLink("AA:BB", "AA:BB", packet(peerA, "not a LAN address")))
    }

    @Test
    fun `two transports exchange packets over an in-memory connection`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val sideA = Recorder()
            val sideB = Recorder()
            val lanA = LanTransport(scope).apply {
                attachFragmentManager(FragmentManager())
                listener = sideA
            }
            val lanB = LanTransport(scope).apply {
                attachFragmentManager(FragmentManager())
                listener = sideB
            }
            val aToB = MemoryPipe()
            val bToA = MemoryPipe()
            val linkOnA = lanA.attach(PipeLink(incoming = bToA, outgoing = aToB), "192.0.2.2")!!
            val linkOnB = lanB.attach(PipeLink(incoming = aToB, outgoing = bToA), "192.0.2.1")!!

            // A broadcasts; B hears it on its end of the connection.
            val atB = CompletableDeferred<Received>()
            sideB.onPacketHook = { atB.complete(it) }
            val fromA = packet(peerA, "hello from A")
            assertTrue(lanA.broadcastPacket(RoutedPacket(fromA)))
            val heardByB = withTimeout(5_000) { atB.await() }
            assertEquals(peerA, heardByB.peerID)
            assertEquals(linkOnB, heardByB.linkAddress)
            assertArrayEquals(fromA.payload, heardByB.packet.payload)

            // Once A's peer ID is bound to the link (MeshCore does this after a valid announce),
            // B can address A directly, and vice versa.
            assertTrue(lanB.bindPeer(linkOnB, peerA))
            assertTrue(lanA.bindPeer(linkOnA, peerB))
            assertEquals(linkOnB, lanB.getDeviceAddressForPeer(peerA))
            val atA = CompletableDeferred<Received>()
            sideA.onPacketHook = { atA.complete(it) }
            assertTrue(lanB.sendPacketToPeer(peerA, packet(peerB, "reply from B")))
            assertEquals("reply from B", String(withTimeout(5_000) { atA.await() }.packet.payload))
            assertFalse(lanB.sendPacketToPeer("3333333333333333", packet(peerB, "nobody")))

            // Closing A's side ends B's connection, and B reports A unreachable.
            val unreachableAtB = CompletableDeferred<String>()
            sideB.onUnreachableHook = { unreachableAtB.complete(it) }
            lanA.closeAll()
            assertEquals(peerA, withTimeout(5_000) { unreachableAtB.await() })
            assertEquals(0, lanA.connectionCount)
            assertEquals(0, lanB.connectionCount)
            assertNull(lanB.getDeviceAddressForPeer(peerA))
        } finally {
            scope.cancel()
        }
    }
}
