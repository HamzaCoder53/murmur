package com.bitchat.desktop.ui

import com.bitchat.desktop.TestDevices
import com.bitchat.desktop.core.DesktopIdentity
import com.bitchat.desktop.core.DesktopMeshNode
import com.bitchat.desktop.core.LoopbackHub
import com.bitchat.desktop.core.LoopbackTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The real [MeshMessagingAdapter] over two [DesktopMeshNode]s on the loopback hub (MeshCore,
 * Noise, wire codec). Waits are on observable state; the timeout only bounds a failure.
 * All data synthetic.
 */
class MeshMessagingAdapterTest {
    private val devices = TestDevices()
    private val hub = LoopbackHub()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transports = mutableListOf<LoopbackTransport>()
    private val nodes = mutableListOf<DesktopMeshNode>()
    private lateinit var aliceNode: DesktopMeshNode
    private lateinit var bobNode: DesktopMeshNode
    private lateinit var alice: MeshMessagingAdapter
    private lateinit var bob: MeshMessagingAdapter

    @Before
    fun setUp() {
        aliceNode = node("alice")
        bobNode = node("bob")
        alice = MeshMessagingAdapter(aliceNode, scope)
        bob = MeshMessagingAdapter(bobNode, scope)
        nodes.forEach { it.start() }
    }

    @After
    fun tearDown() {
        nodes.forEach { it.stop() }
        transports.forEach { it.close() }
        scope.cancel()
        devices.close()
    }

    private fun node(name: String, pendingTimeoutMs: Long = DesktopMeshNode.PENDING_PRIVATE_TIMEOUT_MS): DesktopMeshNode {
        val id = DesktopIdentity.loadOrCreate(devices.context(name)).apply { nickname = name }
        val (n, t) = DesktopMeshNode.overLoopback(id, hub, pendingTimeoutMs)
        transports += t
        nodes += n
        return n
    }

    @Test
    fun `first private message to a peer without a session is queued, then delivered`() = runBlocking<Unit> {
        awaitPeers()
        assertFalse(aliceNode.hasEstablishedSession(bobNode.peerID))

        alice.sendPrivate(bobNode.peerID, "synthetic first secret")
        // The node records the outgoing message synchronously, before any handshake can finish.
        val outgoing = aliceNode.privateChats.value[bobNode.peerID].orEmpty().single()
        assertEquals("not shown as sent before a session exists", DeliveryState.SENDING, outgoing.deliveryStatus?.let(::toDeliveryState))
        val queued = await(alice.messages) { it.text == "synthetic first secret" }
        assertEquals(ConversationId.Private(bobNode.peerID), queued.conversation)
        assertTrue(queued.isMine)

        val received = await(bob.messages) { it.text == "synthetic first secret" }
        assertEquals(ConversationId.Private(aliceNode.peerID), received.conversation)
        assertFalse(received.isMine)

        val sent = await(alice.messages) { it.id == queued.id && it.delivery != DeliveryState.SENDING }
        assertTrue(sent.delivery in setOf(DeliveryState.SENT, DeliveryState.DELIVERED, DeliveryState.READ))
        assertEquals("one ID end to end", queued.id, received.id)
        assertEquals("own message appears once", 1, alice.messages.value.count { it.text == "synthetic first secret" })
    }

    @Test
    fun `starting a session moves the peer to established and exposes the fingerprint`() = runBlocking<Unit> {
        awaitPeers()
        assertEquals(SessionState.NONE, alice.peers.value.single { it.peerID == bobNode.peerID }.session)

        alice.startSession(bobNode.peerID)
        // No message is sent: the session change alone must update the peer list.
        withTimeout(TIMEOUT_MS) {
            alice.peers.first { list -> list.any { it.peerID == bobNode.peerID && it.session == SessionState.ESTABLISHED } }
        }
        val fp = alice.peerFingerprint(bobNode.peerID)
        assertNotNull(fp)
        assertEquals(bobNode.identity.fingerprint.lowercase(), fp!!.lowercase())
        withTimeout(TIMEOUT_MS) {
            bob.peers.first { list -> list.any { it.peerID == aliceNode.peerID && it.session == SessionState.ESTABLISHED } }
        }
    }

    @Test
    fun `private message to a peer that never answers is marked failed`() = runBlocking<Unit> {
        val lonely = node("carol", pendingTimeoutMs = 300)
        val carol = MeshMessagingAdapter(lonely, scope, handshakeTimeoutMs = 300)
        lonely.start()
        val absentPeer = "0f0f0f0f0f0f0f0f"

        carol.sendPrivate(absentPeer, "synthetic into the void")
        assertEquals(DeliveryState.SENDING, lonely.privateChats.value[absentPeer].orEmpty().single().deliveryStatus?.let(::toDeliveryState))
        val failed = await(carol.messages) { it.delivery == DeliveryState.FAILED }
        assertEquals("synthetic into the void", failed.text)
    }

    @Test
    fun `nickname change is announced to connected peers`() = runBlocking<Unit> {
        awaitPeers()
        alice.setNickname("alice2")
        withTimeout(TIMEOUT_MS) {
            bob.peers.first { list -> list.any { it.peerID == aliceNode.peerID && it.nickname == "alice2" } }
        }
    }

    private suspend fun awaitPeers() = withTimeout(TIMEOUT_MS) {
        alice.peers.first { list -> list.any { it.peerID == bobNode.peerID } }
        bob.peers.first { list -> list.any { it.peerID == aliceNode.peerID } }
    }

    private suspend fun await(flow: StateFlow<List<ChatMessage>>, predicate: (ChatMessage) -> Boolean): ChatMessage =
        withTimeout(TIMEOUT_MS) { flow.first { list -> list.any(predicate) }.first(predicate) }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
