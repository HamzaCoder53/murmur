package com.bitchat.watch.rendezvous

import com.bitchat.watch.rendezvous.RendezvousSchedule.Mode
import com.bitchat.watch.rendezvous.RendezvousSchedule.State
import com.bitchat.watch.rendezvous.RendezvousSchedule.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendezvousScheduleTest {

    private val minute = 60_000L
    private val base = 1_000 * minute // an exact minute boundary

    @Test
    fun `synced window opens five seconds before the minute and closes five after`() {
        val window = RendezvousSchedule.currentOrNextWindow(base + 30_000, State())

        assertEquals(Window(base + 55_000, base + 65_000), window)
    }

    @Test
    fun `time inside the window before the boundary returns the open window`() {
        val window = RendezvousSchedule.currentOrNextWindow(base + 57_000, State())

        assertEquals(base + 55_000, window.openMs)
        assertTrue(window.contains(base + 57_000))
    }

    @Test
    fun `time inside the window after the boundary returns the same window`() {
        val window = RendezvousSchedule.currentOrNextWindow(base + 3_000, State())

        assertEquals(Window(base - 5_000, base + 5_000), window)
    }

    @Test
    fun `window end is exclusive so the close instant schedules the next minute`() {
        val window = RendezvousSchedule.currentOrNextWindow(base + 5_000, State())

        assertEquals(Window(base + 55_000, base + 65_000), window)
    }

    @Test
    fun `window open instant is inclusive`() {
        val window = RendezvousSchedule.currentOrNextWindow(base + 55_000, State())

        assertEquals(base + 55_000, window.openMs)
    }

    @Test
    fun `two watches a few seconds apart still overlap`() {
        val skewMs = 4_000L
        val a = RendezvousSchedule.currentOrNextWindow(base + 30_000, State())
        val b = RendezvousSchedule.currentOrNextWindow(base + 30_000 + skewMs, State())
        // b's clock reads later, so in a's time base b's window sits skewMs earlier.
        val bInATime = Window(b.openMs - skewMs, b.closeMs - skewMs)

        val overlapMs = minOf(a.closeMs, bInATime.closeMs) - maxOf(a.openMs, bInATime.openMs)

        assertEquals(6_000L, overlapMs)
    }

    @Test
    fun `a miss is counted but the schedule stays synced below the threshold`() {
        var state = State()
        repeat(2) { state = RendezvousSchedule.onWindowClosed(state, sawPeer = false, nowMs = base, recoveryPick = 0) }

        assertEquals(Mode.SYNCED, state.mode)
        assertEquals(2, state.consecutiveMisses)
    }

    @Test
    fun `third consecutive miss enters recovery anchored at the close time`() {
        var state = State()
        repeat(3) { state = RendezvousSchedule.onWindowClosed(state, sawPeer = false, nowMs = base + 5_000, recoveryPick = 0) }

        assertEquals(Mode.RECOVERY, state.mode)
        assertEquals(base + 5_000, state.recoveryAnchorMs)
        assertEquals(53_000L, state.recoveryPeriodMs)
    }

    @Test
    fun `recovery pick selects the other coprime period`() {
        var state = State()
        repeat(3) { state = RendezvousSchedule.onWindowClosed(state, sawPeer = false, nowMs = base, recoveryPick = 1) }

        assertEquals(67_000L, state.recoveryPeriodMs)
    }

    @Test
    fun `negative recovery pick still selects a valid period`() {
        var state = State()
        repeat(3) { state = RendezvousSchedule.onWindowClosed(state, sawPeer = false, nowMs = base, recoveryPick = -1) }

        assertTrue(state.recoveryPeriodMs in RendezvousSchedule.RECOVERY_PERIODS_MS)
    }

    @Test
    fun `seeing a peer resets the miss count`() {
        var state = State()
        repeat(2) { state = RendezvousSchedule.onWindowClosed(state, sawPeer = false, nowMs = base, recoveryPick = 0) }
        state = RendezvousSchedule.onWindowClosed(state, sawPeer = true, nowMs = base, recoveryPick = 0)

        assertEquals(State(), state)
    }

    @Test
    fun `first recovery window opens one period after the anchor`() {
        val state = recoveryState(anchor = base + 5_000, period = 53_000)

        val window = RendezvousSchedule.currentOrNextWindow(base + 5_000, state)

        assertEquals(Window(base + 58_000, base + 68_000), window)
    }

    @Test
    fun `recovery windows repeat on the recovery period`() {
        val anchor = base + 5_000
        val state = recoveryState(anchor = anchor, period = 67_000)

        val first = RendezvousSchedule.currentOrNextWindow(anchor, state)
        val second = RendezvousSchedule.currentOrNextWindow(first.closeMs, state)
        val third = RendezvousSchedule.currentOrNextWindow(second.closeMs, state)

        assertEquals(anchor + 67_000, first.openMs)
        assertEquals(anchor + 134_000, second.openMs)
        assertEquals(anchor + 201_000, third.openMs)
    }

    @Test
    fun `time inside a recovery window returns that window`() {
        val anchor = base
        val state = recoveryState(anchor = anchor, period = 53_000)

        val window = RendezvousSchedule.currentOrNextWindow(anchor + 53_000 + 4_000, state)

        assertEquals(anchor + 53_000, window.openMs)
    }

    @Test
    fun `recovery sweeps every phase of the minute`() {
        val state = recoveryState(anchor = base, period = 53_000)
        val phases = HashSet<Long>()
        var now = base
        repeat(60) {
            val window = RendezvousSchedule.currentOrNextWindow(now, state)
            phases += Math.floorMod(window.openMs, minute) / 1_000
            now = window.closeMs
        }

        assertEquals(60, phases.size)
    }

    @Test
    fun `missed recovery windows stay in recovery with the same period`() {
        val state = recoveryState(anchor = base, period = 67_000)

        val next = RendezvousSchedule.onWindowClosed(state, sawPeer = false, nowMs = base + 200_000, recoveryPick = 0)

        assertEquals(state, next)
    }

    @Test
    fun `a peer seen in a recovery window snaps back to synced`() {
        val state = recoveryState(anchor = base, period = 53_000)

        val next = RendezvousSchedule.onWindowClosed(state, sawPeer = true, nowMs = base + 63_000, recoveryPick = 0)

        assertEquals(State(), next)
    }

    @Test
    fun `peer contact outside a window also leaves recovery`() {
        val state = recoveryState(anchor = base, period = 53_000)

        assertEquals(State(), RendezvousSchedule.onPeerContact(state))
    }

    @Test
    fun `peer contact on a healthy synced schedule changes nothing`() {
        val state = State()

        assertTrue(RendezvousSchedule.onPeerContact(state) === state)
    }

    @Test
    fun `window does not contain its close instant`() {
        assertFalse(Window(10, 20).contains(20))
    }

    private fun recoveryState(anchor: Long, period: Long) = State(
        mode = Mode.RECOVERY,
        consecutiveMisses = RendezvousSchedule.MISSES_BEFORE_RECOVERY,
        recoveryPeriodMs = period,
        recoveryAnchorMs = anchor
    )
}
