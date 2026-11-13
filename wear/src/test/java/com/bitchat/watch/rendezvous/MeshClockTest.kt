package com.bitchat.watch.rendezvous

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshClockTest {

    private var wall = 1_000_000_000L
    private val published = ArrayList<Long>()

    private fun clock(initialOffsetMs: Long = 0L) =
        MeshClock(wallClock = { wall }, initialOffsetMs = initialOffsetMs, onOffsetChanged = { published += it })

    @Test
    fun `round trip skew assumes the peer answered at the midpoint`() {
        // Sent at 100, peer read 5_150, reply arrived at 300: midpoint is 200, peer is 4_950 ahead.
        assertEquals(4_950L, MeshClock.roundTripSkewMs(t0 = 100, peerWallMs = 5_150, t1 = 300))
    }

    @Test
    fun `now adds the offset and wall conversion removes it`() {
        val clock = clock(initialOffsetMs = 1_500)

        assertEquals(wall + 1_500, clock.now())
        assertEquals(wall, clock.toWallClock(clock.now()))
    }

    @Test
    fun `passive sample from an equal peer is adopted halfway`() {
        val clock = clock()

        clock.onPassiveSample("peer-a", peerWallMs = wall + 4_000, receivedAtWallMs = wall)

        assertEquals(2_000L, clock.offsetMs)
        assertEquals(listOf(2_000L), published)
    }

    @Test
    fun `two free running watches land on the same mesh time`() {
        val wallA = 1_000_000_000L
        val wallB = wallA + 6_000
        val a = MeshClock(wallClock = { wallA })
        val b = MeshClock(wallClock = { wallB })

        a.onPassiveSample("b", peerWallMs = wallB, receivedAtWallMs = wallA)
        b.onPassiveSample("a", peerWallMs = wallA, receivedAtWallMs = wallB)

        assertEquals(a.now(), b.now())
    }

    @Test
    fun `repeating the same measurement does not move the clocks apart`() {
        val wallA = 1_000_000_000L
        val wallB = wallA + 6_000
        val a = MeshClock(wallClock = { wallA })
        val b = MeshClock(wallClock = { wallB })

        repeat(3) {
            a.onPassiveSample("b", peerWallMs = wallB, receivedAtWallMs = wallA)
            b.onPassiveSample("a", peerWallMs = wallA, receivedAtWallMs = wallB)
        }

        assertEquals(a.now(), b.now())
    }

    @Test
    fun `round trip sample from a disciplined peer is adopted in full`() {
        val clock = clock()

        val accepted = clock.onRoundTripSample(
            peerID = "phone-synced-watch",
            t0 = wall,
            peerWallMs = wall + 3_100,
            t1 = wall + 200,
            peerRank = MeshClock.RANK_DISCIPLINED
        )

        assertTrue(accepted)
        assertEquals(3_000L, clock.offsetMs)
    }

    @Test
    fun `a disciplined peer outweighs free running peers`() {
        val clock = clock()
        clock.onPassiveSample("drifter-1", peerWallMs = wall + 20_000, receivedAtWallMs = wall)
        clock.onPassiveSample("drifter-2", peerWallMs = wall + 22_000, receivedAtWallMs = wall)

        clock.onRoundTripSample("good", t0 = wall, peerWallMs = wall + 1_000, t1 = wall, peerRank = MeshClock.RANK_DISCIPLINED)

        assertEquals(1_000L, clock.offsetMs)
    }

    @Test
    fun `equal peers are combined by median so one outlier does not steer`() {
        val clock = clock()
        clock.onPassiveSample("a", peerWallMs = wall + 2_000, receivedAtWallMs = wall)
        clock.onPassiveSample("b", peerWallMs = wall + 4_000, receivedAtWallMs = wall)
        clock.onPassiveSample("c", peerWallMs = wall + 60_000, receivedAtWallMs = wall)

        assertEquals(2_000L, clock.offsetMs)
    }

    @Test
    fun `a slow round trip is rejected`() {
        val clock = clock()

        val accepted = clock.onRoundTripSample(
            peerID = "slow",
            t0 = wall,
            peerWallMs = wall + 9_000,
            t1 = wall + MeshClock.MAX_ROUND_TRIP_MS + 1,
            peerRank = MeshClock.RANK_DISCIPLINED
        )

        assertFalse(accepted)
        assertEquals(0L, clock.offsetMs)
    }

    @Test
    fun `an implausible skew is ignored`() {
        val clock = clock()

        clock.onPassiveSample("wrong-year", peerWallMs = wall + MeshClock.MAX_PLAUSIBLE_SKEW_MS + 1, receivedAtWallMs = wall)

        assertEquals(0L, clock.offsetMs)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `corrections inside the deadband are not applied`() {
        val clock = clock()

        clock.onPassiveSample("near", peerWallMs = wall + 300, receivedAtWallMs = wall)

        assertEquals(0L, clock.offsetMs)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a disciplined watch ignores peers and uses its own wall clock`() {
        val clock = clock()
        clock.setSelfRank(MeshClock.RANK_DISCIPLINED)

        clock.onPassiveSample("drifter", peerWallMs = wall + 30_000, receivedAtWallMs = wall)

        assertEquals(0L, clock.offsetMs)
    }

    @Test
    fun `becoming disciplined clears a stored offset even when it is small`() {
        val clock = clock(initialOffsetMs = 100)

        clock.setSelfRank(MeshClock.RANK_DISCIPLINED)

        assertEquals(0L, clock.offsetMs)
        assertEquals(listOf(0L), published)
    }

    @Test
    fun `samples expire so a departed peer stops steering`() {
        val clock = clock()
        clock.onPassiveSample("gone", peerWallMs = wall + 10_000, receivedAtWallMs = wall)
        assertEquals(5_000L, clock.offsetMs)

        wall += MeshClock.SAMPLE_MAX_AGE_MS + 1
        clock.onPassiveSample("here", peerWallMs = wall + 2_000, receivedAtWallMs = wall)

        assertEquals(1_000L, clock.offsetMs)
    }

    @Test
    fun `forgetting the only peer keeps the last offset`() {
        val clock = clock()
        clock.onPassiveSample("a", peerWallMs = wall + 4_000, receivedAtWallMs = wall)

        clock.forgetPeer("a")

        assertEquals(2_000L, clock.offsetMs)
    }
}
