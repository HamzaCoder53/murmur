package com.bitchat.desktop.core

import com.bitchat.android.model.BitchatMessage
import com.bitchat.desktop.TestDevices
import kotlinx.coroutines.flow.StateFlow
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
        // The sender never receives its own broadcast back over the link.
        assertTrue(alice.publicMessages.value.none { it.content == "synthetic public hello" })
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
