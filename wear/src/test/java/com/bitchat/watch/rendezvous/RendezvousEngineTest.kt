package com.bitchat.watch.rendezvous

import com.bitchat.android.mesh.PowerManager.ScanWindowOverride
import com.bitchat.watch.rendezvous.RendezvousEngine.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendezvousEngineTest {

    private val minute = 60_000L
    private val base = 1_000 * minute

    /** Deterministic stand-in for alarms, wake locks and delayed callbacks. */
    private inner class FakePlatform : RendezvousEngine.Platform {
        var exactAllowed = true
        var foreground = false
        var wakeAt: Long? = null
        var wakeExact: Boolean? = null
        var wakeLockHeld = false
        var pending: Pair<Long, () -> Unit>? = null

        override fun canScheduleExactWakes() = exactAllowed
        override fun scheduleWake(wallTimeMs: Long, exact: Boolean) {
            wakeAt = wallTimeMs
            wakeExact = exact
        }
        override fun cancelWake() {
            wakeAt = null
        }
        override fun acquireWakeLock(timeoutMs: Long) {
            wakeLockHeld = true
        }
        override fun releaseWakeLock() {
            wakeLockHeld = false
        }
        override fun runAfter(delayMs: Long, action: () -> Unit): () -> Unit {
            val entry = (wall + delayMs) to action
            pending = entry
            return { if (pending === entry) pending = null }
        }
        override fun isAppInForeground() = foreground
    }

    private var wall = base + 30_000
    private val platform = FakePlatform()
    private val overrides = ArrayList<ScanWindowOverride?>()
    private var livePeers = false
    private val clock = MeshClock(wallClock = { wall })
    private val engine = RendezvousEngine(
        clock = clock,
        platform = platform,
        setScanOverride = { overrides += it },
        hasLivePeers = { livePeers },
        recoveryPick = { 0 }
    )

    /** Jump to the scheduled wake and deliver it, as the alarm would. */
    private fun fireWake() {
        wall = requireNotNull(platform.wakeAt) { "no wake scheduled" }
        platform.wakeAt = null
        engine.onWake()
    }

    /** Jump to the end of the open window and run its close callback. */
    private fun elapseWindow() {
        val (at, action) = requireNotNull(platform.pending) { "no window open" }
        wall = at
        platform.pending = null
        action()
    }

    private fun runWindow() {
        fireWake()
        elapseWindow()
    }

    @Test
    fun `starting between windows closes the scanner and schedules the next minute boundary`() {
        engine.start()

        assertEquals(ScanWindowOverride.CLOSED, overrides.last())
        assertEquals(base + 55_000, platform.wakeAt)
        assertEquals(true, platform.wakeExact)
        assertEquals(Phase.WAITING, engine.status.value.phase)
        assertTrue(engine.status.value.preciseTiming)
    }

    @Test
    fun `starting inside a window opens it for the remainder`() {
        wall = base + 58_000

        engine.start()

        assertEquals(ScanWindowOverride.OPEN, overrides.last())
        assertEquals(Phase.WINDOW_OPEN, engine.status.value.phase)
        assertEquals(base + 65_000, platform.pending?.first)
        assertTrue(platform.wakeLockHeld)
    }

    @Test
    fun `a wake opens the scanner under a wake lock until the shared close`() {
        engine.start()

        fireWake()

        assertEquals(ScanWindowOverride.OPEN, overrides.last())
        assertTrue(platform.wakeLockHeld)
        assertEquals(base + 65_000, platform.pending?.first)
    }

    @Test
    fun `closing a window releases the wake lock and schedules the next one`() {
        engine.start()

        runWindow()

        assertEquals(ScanWindowOverride.CLOSED, overrides.last())
        assertFalse(platform.wakeLockHeld)
        assertEquals(base + 115_000, platform.wakeAt)
        assertEquals(1L, engine.status.value.windowsCompleted)
    }

    @Test
    fun `a window that saw a peer keeps the synced schedule`() {
        engine.start()
        fireWake()
        engine.onPeerContact()

        elapseWindow()

        assertEquals(true, engine.status.value.lastWindowSawPeer)
        assertEquals(0, engine.status.value.consecutiveMisses)
        assertEquals(RendezvousSchedule.Mode.SYNCED, engine.status.value.mode)
    }

    @Test
    fun `a still-connected peer counts as contact even with no new packets`() {
        livePeers = true
        engine.start()

        repeat(4) { runWindow() }

        assertEquals(RendezvousSchedule.Mode.SYNCED, engine.status.value.mode)
    }

    @Test
    fun `three empty windows switch to the recovery period`() {
        engine.start()

        repeat(3) { runWindow() }

        assertEquals(RendezvousSchedule.Mode.RECOVERY, engine.status.value.mode)
        // Third window closed at base + 185 s; first recovery window is 53 s after that.
        assertEquals(base + 185_000 + 53_000, platform.wakeAt)
    }

    @Test
    fun `peer contact while waiting in recovery snaps back to the minute boundary`() {
        engine.start()
        repeat(3) { runWindow() }
        wall = base + 190_000

        engine.onPeerContact()

        assertEquals(RendezvousSchedule.Mode.SYNCED, engine.status.value.mode)
        assertEquals(base + 235_000, platform.wakeAt)
    }

    @Test
    fun `the mesh clock offset shifts the wall time of the wake`() {
        // A peer 4 s ahead pulls a free-running watch 2 s forward, so it must wake 2 s earlier.
        clock.onPassiveSample("peer", peerWallMs = wall + 4_000, receivedAtWallMs = wall)

        engine.start()

        assertEquals(base + 55_000 - 2_000, platform.wakeAt)
    }

    @Test
    fun `without exact wake access the wake is requested as inexact and reported`() {
        platform.exactAllowed = false

        engine.start()

        assertEquals(false, platform.wakeExact)
        assertFalse(engine.status.value.preciseTiming)
    }

    @Test
    fun `a late wake still scans a full length window`() {
        platform.exactAllowed = false
        engine.start()
        platform.wakeAt = null
        wall = base + 80_000 // 25 s after the window it was meant for

        engine.onWake()

        assertEquals(ScanWindowOverride.OPEN, overrides.last())
        assertEquals(wall + RendezvousSchedule.WINDOW_LENGTH_MS, platform.pending?.first)
    }

    @Test
    fun `gaining exact wake access reschedules the pending wake as exact`() {
        platform.exactAllowed = false
        engine.start()
        platform.exactAllowed = true

        engine.onExactWakePermissionChanged()

        assertEquals(true, platform.wakeExact)
        assertTrue(engine.status.value.preciseTiming)
    }

    @Test
    fun `opening the app hands scanning back to the normal schedule`() {
        engine.start()
        platform.foreground = true

        engine.onForegroundChanged()

        assertNull(overrides.last())
        assertNull(platform.wakeAt)
        assertEquals(Phase.FOREGROUND, engine.status.value.phase)
    }

    @Test
    fun `opening the app during a window abandons it without counting a miss`() {
        engine.start()
        fireWake()
        platform.foreground = true

        engine.onForegroundChanged()

        assertNull(platform.pending)
        assertFalse(platform.wakeLockHeld)
        assertEquals(0, engine.status.value.consecutiveMisses)
        assertEquals(0L, engine.status.value.windowsCompleted)
    }

    @Test
    fun `leaving the app resumes rendezvous windows`() {
        platform.foreground = true
        engine.start()
        platform.foreground = false

        engine.onForegroundChanged()

        assertEquals(ScanWindowOverride.CLOSED, overrides.last())
        assertEquals(base + 55_000, platform.wakeAt)
    }

    @Test
    fun `a wake that arrives while the app is open does not open a window`() {
        engine.start()
        platform.foreground = true

        engine.onWake()

        assertNull(platform.pending)
        assertEquals(Phase.FOREGROUND, engine.status.value.phase)
    }

    @Test
    fun `stop cancels everything and returns scan control`() {
        engine.start()
        fireWake()

        engine.stop()

        assertNull(overrides.last())
        assertNull(platform.wakeAt)
        assertNull(platform.pending)
        assertFalse(platform.wakeLockHeld)
        assertEquals(Phase.STOPPED, engine.status.value.phase)
    }

    @Test
    fun `events after stop are ignored`() {
        engine.start()
        engine.stop()
        val overridesBefore = overrides.size

        engine.onWake()
        engine.onPeerContact()
        engine.onForegroundChanged()

        assertEquals(overridesBefore, overrides.size)
        assertNull(platform.wakeAt)
    }
}
