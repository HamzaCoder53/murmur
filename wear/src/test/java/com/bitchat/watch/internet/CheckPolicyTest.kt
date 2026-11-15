package com.bitchat.watch.internet

import com.bitchat.watch.internet.CheckPolicy.Plan
import com.bitchat.watch.internet.WatchNostrClient.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckPolicyTest {
    private val now = 10_000_000L

    @Test
    fun `quiet watch checks at the chosen interval`() {
        assertEquals(Plan.Check(5 * 60_000L), CheckPolicy.plan(Mode.EVERY_5_MIN, now, null, charging = false))
        assertEquals(Plan.Check(60_000L), CheckPolicy.plan(Mode.EVERY_1_MIN, now, null, charging = false))
    }

    @Test
    fun `a message just now keeps a live connection for the burst`() {
        val last = now - 2 * 60_000L
        assertEquals(Plan.Live(last + CheckPolicy.BURST_MS), CheckPolicy.plan(Mode.EVERY_5_MIN, now, last, false))
    }

    @Test
    fun `after the burst it checks every minute, then back to the interval`() {
        assertEquals(Plan.Check(60_000L), CheckPolicy.plan(Mode.EVERY_5_MIN, now, now - 15 * 60_000L, false))
        assertEquals(Plan.Check(5 * 60_000L), CheckPolicy.plan(Mode.EVERY_5_MIN, now, now - 31 * 60_000L, false))
    }

    @Test
    fun `charging stays connected, off stays off, always on is unchanged`() {
        assertEquals(Plan.Live(null), CheckPolicy.plan(Mode.EVERY_5_MIN, now, null, charging = true))
        assertEquals(Plan.Idle, CheckPolicy.plan(Mode.OFF, now, now, charging = true))
        assertEquals(Plan.Live(null), CheckPolicy.plan(Mode.ALWAYS, now, null, charging = false))
    }

    @Test
    fun `a clock jump backwards does not start a burst`() {
        assertEquals(Plan.Check(5 * 60_000L), CheckPolicy.plan(Mode.EVERY_5_MIN, now, now + 60_000L, false))
    }

    @Test
    fun `wake-up checks are throttled and only for timed modes`() {
        assertTrue(CheckPolicy.opportunistic(Mode.EVERY_5_MIN, now, null))
        assertTrue(CheckPolicy.opportunistic(Mode.EVERY_5_MIN, now, now - 61_000L))
        assertFalse(CheckPolicy.opportunistic(Mode.EVERY_5_MIN, now, now - 30_000L))
        assertFalse(CheckPolicy.opportunistic(Mode.ALWAYS, now, null))
        assertFalse(CheckPolicy.opportunistic(Mode.OFF, now, null))
    }
}
