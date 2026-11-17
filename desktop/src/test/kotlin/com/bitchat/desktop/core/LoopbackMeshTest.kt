package com.bitchat.desktop.core

import com.bitchat.android.model.BitchatMessage
import com.bitchat.desktop.TestDevices
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Two desktop nodes in one process over the loopback hub: announces, signed public messages and
 * a Noise-encrypted private message all go through MeshCore and the BinaryProtocol wire codec.
 * Waits are on observable state (no sleeps); the timeout only bounds a failure.
 */
class LoopbackMeshTest {
    private val devices = TestDevices()
    private val hub = LoopbackHub()
    private lateinit var alice: DesktopMeshNode
    private lateinit var bob: DesktopMeshNode
    private val transports = mutableListOf<LoopbackTransport>()

    @Before
    fun setUp() {
        val a = DesktopIdentity.loadOrCreate(devices.context("alice")).apply { nickname = "alice" }
        val b = DesktopIdentity.loadOrCreate(devices.context("bob")).apply { nickname = "bob" }
        alice = DesktopMeshNode.overLoopback(a, hub).also { transports += it.second }.first
        bob = DesktopMeshNode.overLoopback(b, hub).also { transports += it.second }.first
        alice.start()
        bob.start()
    }

    @After
    fun tearDown() {
        alice.stop()
        bob.stop()
        transports.forEach { it.close() }
        devices.close()
    }

    @Test
    fun `nodes discover each other through signed announces`() = runBlocking {
        awaitPeer(alice, bob.peerID)
        awaitPeer(bob, alice.peerID)
        assertEquals("bob", alice.peerNickname(bob.peerID))
        assertEquals("alice", bob.peerNickname(alice.peerID))
    }

    @Test
    fun `a public message from one node arrives at the other`() = runBlocking {
        awaitPeer(bob, alice.peerID)
        alice.sendPublic("synthetic public hello")

        val received = awaitMessage(bob.publicMessages) { it.content == "synthetic public hello" }
        assertEquals("alice", received.sender)
        assertEquals(alice.peerID, received.senderPeerID)
        assertFalse(received.isPrivate)
        // The sender records its own outgoing message once in its local timeline.
        assertEquals(1, alice.publicMessages.value.count { it.content == "synthetic public hello" })
    }

    @Test
    fun `late joiner receives earlier public message via gossip sync`() = runBlocking {
        alice.sendPublic("synthetic earlier message")

        val charlieId = DesktopIdentity.loadOrCreate(devices.context("charlie")).apply { nickname = "charlie" }
        val (charlie, charlieTransport) = DesktopMeshNode.overLoopback(charlieId, hub)
        transports += charlieTransport
        charlie.start()
        try {
            awaitPeer(charlie, alice.peerID)
            val synced = awaitMessage(charlie.publicMessages) { it.content == "synthetic earlier message" }
            assertEquals(alice.peerID, synced.senderPeerID)
        } finally {
            charlie.stop()
        }
    }

    @Test
    fun `a private message is delivered after the cores complete a Noise handshake`() = runBlocking {
        awaitPeer(alice, bob.peerID)
        awaitPeer(bob, alice.peerID)
        alice.initiateHandshake(bob.peerID)
        withTimeout(TIMEOUT_MS) {
            while (!(alice.hasEstablishedSession(bob.peerID) && bob.hasEstablishedSession(alice.peerID))) {
                kotlinx.coroutines.yield()
                kotlinx.coroutines.delay(10)
            }
        }

        alice.sendPrivate("synthetic secret", bob.peerID, "bob")

        val received = awaitMessage(bob.privateMessages) { it.content == "synthetic secret" }
        assertTrue(received.isPrivate)
        assertEquals(alice.peerID, received.senderPeerID)

        // Both sides file the message under the conversation partner, so the sender sees its own
        // message in the chat with bob and bob sees it in the chat with alice.
        assertEquals(
            listOf("synthetic secret"),
            alice.privateConversations.value[bob.peerID]?.map { it.content },
        )
        assertEquals(
            listOf("synthetic secret"),
            bob.privateConversations.value[alice.peerID]?.map { it.content },
        )
        assertFalse(alice.privateConversations.value.containsKey(alice.peerID))
    }

    @Test
    fun `only received private messages are announced for notifications`() = runBlocking {
        awaitPeer(alice, bob.peerID)
        awaitPeer(bob, alice.peerID)
        val bobIncoming = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(TIMEOUT_MS) { bob.incomingPrivate.first() }
        }
        val aliceIncoming = mutableListOf<BitchatMessage>()
        val aliceWatch = launch(start = CoroutineStart.UNDISPATCHED) { alice.incomingPrivate.collect { aliceIncoming += it } }
        alice.initiateHandshake(bob.peerID)
        withTimeout(TIMEOUT_MS) {
            while (!(alice.hasEstablishedSession(bob.peerID) && bob.hasEstablishedSession(alice.peerID))) {
                kotlinx.coroutines.delay(10)
            }
        }
        alice.sendPrivate("synthetic ping", bob.peerID, "bob")
        assertEquals("synthetic ping", bobIncoming.await().content)
        aliceWatch.cancel()
        assertTrue(aliceIncoming.isEmpty())
    }

    @Test
    fun `conversations survive a restart through the encrypted store and obey the history switch`() {
        val ctx = devices.context("persist")
        var historyOn = true
        val store = EncryptedConversationStore(ctx) { historyOn }
        val id = DesktopIdentity.loadOrCreate(ctx).apply { nickname = "persist" }
        val msg = com.bitchat.android.model.BitchatMessage(
            id = "persist-1",
            sender = "synthetic-peer",
            content = "synthetic saved text",
            timestamp = java.util.Date(1_700_000_000_000L),
            isPrivate = true,
            senderPeerID = "00112233aabbccdd",
        )
        val (first, t1) = DesktopMeshNode.overLoopback(id, LoopbackHub(), store)
        first.importPrivate("00112233aabbccdd", listOf(msg))
        first.persistNow()
        t1.close()

        // The sealed file never holds the plaintext.
        val sealed = java.io.File(ctx.prefsDir(), "skein_desktop_conversations.enc")
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!sealed.isFile && System.currentTimeMillis() < deadline) Thread.sleep(10) // apply() is async
        assertTrue(sealed.isFile)
        assertFalse(String(sealed.readBytes(), Charsets.ISO_8859_1).contains("synthetic saved text"))

        val reloaded = store.load()
        assertEquals("synthetic saved text", reloaded.privateConversations["00112233aabbccdd"]?.single()?.content)

        historyOn = false
        first.persistNow()
        historyOn = true
        assertTrue(store.load().privateConversations.isEmpty())
    }

    private suspend fun awaitPeer(node: DesktopMeshNode, peerID: String) {
        withTimeout(TIMEOUT_MS) { node.peers.first { peerID in it } }
    }

    private suspend fun awaitMessage(
        flow: StateFlow<List<BitchatMessage>>,
        predicate: (BitchatMessage) -> Boolean,
    ): BitchatMessage = withTimeout(TIMEOUT_MS) { flow.first { list -> list.any(predicate) }.first(predicate) }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
