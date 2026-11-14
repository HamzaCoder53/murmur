package com.bitchat.watch.mesh

import com.bitchat.android.model.DeliveryStatus
import com.bitchat.watch.mesh.DmOutbox.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class DmOutboxTest {

    private var clock = 1_000_000L
    private val outbox = DmOutbox(now = { clock })
    private val status = HashMap<String, DeliveryStatus?>()
    private val reachable = HashSet<String>()
    private val sessions = HashSet<String>()

    private fun track(id: String, peer: String = "peer-a") {
        outbox.track(id, peer, "Alice", "hello $id")
        status[id] = DeliveryStatus.Sent
    }

    private fun due(urgent: Set<String> = emptySet()) = outbox.due(
        statusOf = { status[it] },
        isReachable = { it in reachable },
        hasSession = { it in sessions },
        urgentPeers = urgent
    )

    @Test
    fun `nothing is resent before the first backoff`() {
        track("m1"); reachable += "peer-a"; sessions += "peer-a"
        clock += DmOutbox.FIRST_RETRY_MS - 1

        assertTrue(due().isEmpty())
    }

    @Test
    fun `an unacknowledged message is resent once the backoff passes`() {
        track("m1"); reachable += "peer-a"; sessions += "peer-a"
        clock += DmOutbox.FIRST_RETRY_MS

        val actions = due()

        assertEquals(1, actions.size)
        assertTrue(actions.single() is Action.Resend)
        assertEquals("m1", actions.single().entry.messageID)
    }

    @Test
    fun `a delivered message leaves the outbox`() {
        track("m1"); reachable += "peer-a"; sessions += "peer-a"
        status["m1"] = DeliveryStatus.Delivered("peer-a", Date())
        clock += DmOutbox.FIRST_RETRY_MS

        assertTrue(due().isEmpty())
        assertEquals(0, outbox.size)
    }

    @Test
    fun `a read message leaves the outbox`() {
        track("m1")
        status["m1"] = DeliveryStatus.Read("peer-a", Date())

        due()

        assertEquals(0, outbox.size)
    }

    @Test
    fun `a message removed from the chat leaves the outbox`() {
        track("m1")
        status.remove("m1")

        due()

        assertEquals(0, outbox.size)
    }

    @Test
    fun `nothing is sent to a peer that is out of range`() {
        track("m1"); sessions += "peer-a"
        clock += DmOutbox.MAX_RETRY_MS

        assertTrue(due().isEmpty())
        assertEquals(1, outbox.size)
    }

    @Test
    fun `a reachable peer without a session gets a handshake first`() {
        track("m1"); reachable += "peer-a"
        clock += DmOutbox.FIRST_RETRY_MS

        assertTrue(due().single() is Action.Handshake)
    }

    @Test
    fun `a peer that reappears skips the backoff`() {
        track("m1"); reachable += "peer-a"; sessions += "peer-a"

        val actions = due(urgent = setOf("peer-a"))

        assertTrue(actions.single() is Action.Resend)
    }

    @Test
    fun `backoff doubles after each resend and is capped`() {
        assertEquals(15_000L, outbox.backoffMs(1))
        assertEquals(30_000L, outbox.backoffMs(2))
        assertEquals(60_000L, outbox.backoffMs(3))
        assertEquals(DmOutbox.MAX_RETRY_MS, outbox.backoffMs(20))
    }

    @Test
    fun `a resend pushes the next attempt back`() {
        track("m1"); reachable += "peer-a"; sessions += "peer-a"
        clock += DmOutbox.FIRST_RETRY_MS
        outbox.attempted(due().single())

        clock += DmOutbox.FIRST_RETRY_MS
        assertTrue(due().isEmpty())

        clock += DmOutbox.FIRST_RETRY_MS
        assertTrue(due().single() is Action.Resend)
    }

    @Test
    fun `an old message expires even while the peer is away`() {
        track("m1")
        clock += DmOutbox.EXPIRY_MS + 1

        val actions = due()

        assertTrue(actions.single() is Action.Expire)
        assertEquals(0, outbox.size)
    }

    @Test
    fun `the outbox drops the oldest entries beyond its cap`() {
        val small = DmOutbox(now = { clock }, maxEntries = 2)
        small.track("m1", "p", "n", "a")
        small.track("m2", "p", "n", "b")
        small.track("m3", "p", "n", "c")

        assertEquals(listOf("m2", "m3"), small.pending().map { it.messageID })
    }
}
