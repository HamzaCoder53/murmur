package com.bitchat.android.linked

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

private class FakeLinkedAppBridge(
    private var nick: String = "alice",
    private val devName: String = "Test Phone"
) : LinkedAppBridge {
    override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val executedCommands = mutableListOf<Pair<LinkCommand, ByteArray?>>()
    val entries = LinkedHashMap<String, LinkEntry>()
    val favs = mutableListOf<LinkFavorite>()
    var mirrorPeers: List<LinkPeer>? = null
    var radiosSuppressed = false
        private set
    var identityErased = false
    var restarted = false
    var bundleBytes: ByteArray? = "fake-bundle".toByteArray()

    override fun nickname() = nick
    override fun setNickname(name: String) { nick = name }
    override suspend fun entriesSince(since: Long) = entries.values.filter { it.message.timestamp.time >= since }
    override fun currentEntries() = entries.values.toList()

    override suspend fun applyEntries(entries: List<LinkEntry>): List<String> {
        val known = this.entries.mapValues { it.value.message to it.value.read }
        val plan = BacklogMerge.plan(known, entries)
        plan.add.forEach { this.entries[it.message.id] = it }
        plan.update.forEach { u ->
            val old = this.entries[u.message.id] ?: return@forEach
            this.entries[u.message.id] = old.copy(message = u.message)
        }
        plan.markRead.forEach { id ->
            this.entries[id]?.let { this.entries[id] = it.copy(read = true) }
        }
        return emptyList()
    }

    override fun favorites() = favs.toList()
    override fun applyFavorites(favorites: List<LinkFavorite>) {
        favorites.forEach { f ->
            favs.removeAll { it.noiseKeyHex == f.noiseKeyHex }
            favs += f
        }
    }

    override fun peers() = emptyList<LinkPeer>()
    override fun applyMirrorPeers(peers: List<LinkPeer>?) { mirrorPeers = peers }
    override fun setRadiosSuppressed(suppressed: Boolean) { radiosSuppressed = suppressed }

    override suspend fun execute(command: LinkCommand, media: ByteArray?) {
        executedCommands += command to media
    }

    override fun mediaFile(messageID: String): Pair<String, ByteArray>? = null
    override suspend fun onMediaReceived(messageID: String, fileName: String, data: ByteArray) = Unit
    override fun identityBundle() = bundleBytes

    override suspend fun adoptPairing(
        identity: ByteArray,
        entries: List<LinkEntry>,
        favorites: List<LinkFavorite>,
        media: Map<String, Pair<String, ByteArray>>
    ): Boolean {
        applyEntries(entries)
        applyFavorites(favorites)
        return true
    }

    override suspend fun eraseIdentity() { identityErased = true; entries.clear(); favs.clear() }
    override fun restartProcess() { restarted = true }
    override fun deviceName() = devName
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LinkCoordinatorTest {
    private val idA = "00000000000000000000000000000001"
    private val idB = "00000000000000000000000000000002"
    private val idC = "00000000000000000000000000000003"

    @Test
    fun `leader election is deterministic by smallest device id`() {
        assertEquals(LinkRole.Standalone, LeaderElection.roleFor(idB, emptyList()))
        assertEquals(LinkRole.Leader, LeaderElection.roleFor(idA, listOf(idB, idC)))
        assertEquals(LinkRole.Follower(idA), LeaderElection.roleFor(idB, listOf(idA, idC)))
        assertEquals(LinkRole.Follower(idB), LeaderElection.roleFor(idC, listOf(idB)))
    }

    @Test
    fun `backlog merge deduplicates by id, unions read state, and keeps furthest status`() {
        val t = Date(1_000_000L)
        val localMsg = BitchatMessage(
            id = "m1",
            sender = "bob",
            content = "hi",
            timestamp = t,
            isPrivate = true,
            deliveryStatus = DeliveryStatus.Sent
        )
        val known = mapOf("m1" to (localMsg to false))
        val incoming = listOf(
            LinkEntry(localMsg.copy(deliveryStatus = DeliveryStatus.Delivered("bob", t)), "conv1", read = true),
            LinkEntry(localMsg.copy(id = "m2", content = "new"), "conv1", read = false)
        )

        val plan = BacklogMerge.plan(known, incoming)
        assertEquals(1, plan.add.size)
        assertEquals("m2", plan.add.first().message.id)
        assertEquals(1, plan.update.size)
        assertTrue(plan.update.first().message.deliveryStatus is DeliveryStatus.Delivered)
        assertEquals(setOf("m1"), plan.markRead)
    }

    @Test
    fun `follower suppresses radios and routes commands to leader`() = runTest {
        val linkKey = ByteArray(32) { 7 }
        val storeA = InMemoryLinkStore(idA).apply { put(LinkedDevice(idB, "Phone B", linkKey)) }
        val storeB = InMemoryLinkStore(idB).apply { put(LinkedDevice(idA, "Phone A", linkKey)) }
        val bridgeA = FakeLinkedAppBridge(nick = "alice", devName = "Phone A")
        val bridgeB = FakeLinkedAppBridge(nick = "alice", devName = "Phone B")
        val coordA = LinkCoordinator(this, storeA, bridgeA) { 1000L }
        val coordB = LinkCoordinator(this, storeB, bridgeB) { 1000L }

        val (ioA, ioB) = createPipePair()
        val kA = ByteArray(32) { 11 }
        val kB = ByteArray(32) { 22 }
        val sessA = LinkSession(idB, ioA, FrameCipher(kA, kB), dialed = true)
        val sessB = LinkSession(idA, ioB, FrameCipher(kB, kA), dialed = false)

        val jobA = launch { coordA.runSession(sessA) }
        val jobB = launch { coordB.runSession(sessB) }
        advanceUntilIdle()

        assertEquals(LinkRole.Leader, coordA.role.value)
        assertEquals(LinkRole.Follower(idA), coordB.role.value)
        assertFalse(bridgeA.radiosSuppressed)
        assertTrue(bridgeB.radiosSuppressed)

        val cmd = LinkCommand.SendPrivate("msg-1", "hello", "peer1234", "Peer")
        assertTrue(coordB.forward(cmd))
        advanceUntilIdle()

        assertEquals(1, bridgeA.executedCommands.size)
        assertEquals(cmd, bridgeA.executedCommands.first().first)

        sessA.close()
        sessB.close()
        jobA.cancel()
        jobB.cancel()
    }

    @Test
    fun `unlink and panic wipe propagate to reachable and keep pending for unreachable`() = runTest {
        val keyB = ByteArray(32) { 5 }
        val keyC = ByteArray(32) { 6 }
        val storeA = InMemoryLinkStore(idA).apply {
            put(LinkedDevice(idB, "Phone B", keyB))
            put(LinkedDevice(idC, "Phone C", keyC))
        }
        val storeB = InMemoryLinkStore(idB).apply {
            put(LinkedDevice(idA, "Phone A", keyB))
        }
        val bridgeA = FakeLinkedAppBridge()
        val bridgeB = FakeLinkedAppBridge()
        val coordA = LinkCoordinator(this, storeA, bridgeA) { 2000L }
        val coordB = LinkCoordinator(this, storeB, bridgeB) { 2000L }

        val (ioA, ioB) = createPipePair()
        val kA = ByteArray(32) { 12 }
        val kB = ByteArray(32) { 23 }
        val sessA = LinkSession(idB, ioA, FrameCipher(kA, kB), dialed = true)
        val sessB = LinkSession(idA, ioB, FrameCipher(kB, kA), dialed = false)

        val jobA = launch { coordA.runSession(sessA) }
        val jobB = launch { coordB.runSession(sessB) }
        advanceUntilIdle()

        coordA.panicWipeAll()
        advanceUntilIdle()

        // B was reachable -> erased and restarted, and acked so A removed B's pending wipe.
        assertTrue(bridgeB.identityErased)
        assertTrue(bridgeB.restarted)
        assertFalse(storeA.pendingWipes().containsKey(idB))

        // C was unreachable -> A keeps pending wipe for C.
        assertTrue(storeA.pendingWipes().containsKey(idC))
        assertTrue(storeA.isPanicWipe(idC))
        assertTrue(storeA.devices().isEmpty())

        sessA.close()
        sessB.close()
        jobA.cancel()
        jobB.cancel()
    }
}
