package com.bitchat.watch.rendezvous

import com.bitchat.android.mesh.PowerManager.ScanWindowOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the standalone watch's scan windows: sleep, wake on an alarm, scan for one window,
 * account for whether anyone was seen, and schedule the next wake.
 *
 * The engine only governs the watch while it is standalone and the app is in the background. With
 * the app open it steps aside (scan override cleared) so the normal foreground schedule keeps
 * chat responsive.
 *
 * Everything that touches the OS goes through [Platform], so the state machine is tested on the
 * JVM with a fake.
 */
class RendezvousEngine(
    private val clock: MeshClock,
    private val platform: Platform,
    private val setScanOverride: (ScanWindowOverride?) -> Unit,
    private val hasLivePeers: () -> Boolean
) {
    interface Platform {
        /** Whether wakes can be scheduled to the second. When false they may arrive late. */
        fun canScheduleExactWakes(): Boolean

        /** Wake the device at [wallTimeMs] and call [RendezvousEngine.onWake]. Replaces any pending wake. */
        fun scheduleWake(wallTimeMs: Long, exact: Boolean)

        fun cancelWake()

        /** Keep the CPU running for at most [timeoutMs]. */
        fun acquireWakeLock(timeoutMs: Long)

        fun releaseWakeLock()

        /** Run [action] after [delayMs]; the returned function cancels it. */
        fun runAfter(delayMs: Long, action: () -> Unit): () -> Unit

        fun isAppInForeground(): Boolean
    }

    enum class Phase { STOPPED, FOREGROUND, WAITING, WINDOW_OPEN }

    data class Status(
        val phase: Phase = Phase.STOPPED,
        /** Windows in a row that saw nobody. */
        val consecutiveMisses: Int = 0,
        /** False when the OS will not grant exact wakes, so windows drift from the shared schedule. */
        val preciseTiming: Boolean = false,
        val nextWindowWallMs: Long? = null,
        /** Whether the most recently closed window saw a peer; null before the first close. */
        val lastWindowSawPeer: Boolean? = null,
        val windowsCompleted: Long = 0
    )

    private companion object {
        /** Slack on the wake lock so the close handler always runs before the lock expires. */
        const val WAKE_LOCK_SLACK_MS = 2_000L
    }

    private val lock = Any()
    private var running = false
    private var misses = 0
    private var sawPeer = false
    private var cancelClose: (() -> Unit)? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            misses = 0
            sawPeer = false
            _status.value = Status()
            reschedule()
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            endWindow()
            platform.cancelWake()
            setScanOverride(null)
            _status.value = Status()
        }
    }

    /** The wake scheduled through [Platform.scheduleWake] fired. */
    fun onWake() {
        synchronized(lock) {
            if (!running) return
            if (platform.isAppInForeground()) {
                reschedule()
                return
            }
            if (_status.value.phase == Phase.WINDOW_OPEN) return
            val now = clock.now()
            val window = RendezvousSchedule.currentOrNextWindow(now)
            // On time: scan to the shared close. Late (inexact wake): still scan a full window, since
            // peers advertise continuously and will be found whenever the scanner is on.
            val durationMs = if (window.contains(now)) {
                window.closeMs - now
            } else {
                RendezvousSchedule.WINDOW_LENGTH_MS
            }
            openWindow(durationMs)
        }
    }

    /** A packet arrived from, or a connection was made to, a directly reachable peer. */
    fun onPeerContact() {
        synchronized(lock) {
            if (!running) return
            sawPeer = true
            if (misses != 0) {
                misses = 0
                publish()
            }
        }
    }

    fun onForegroundChanged() {
        synchronized(lock) {
            if (running) reschedule()
        }
    }

    /** The OS changed whether exact wakes are allowed. */
    fun onExactWakePermissionChanged() {
        synchronized(lock) {
            if (running && _status.value.phase == Phase.WAITING) reschedule()
        }
    }

    private fun reschedule() {
        if (platform.isAppInForeground()) {
            endWindow()
            platform.cancelWake()
            setScanOverride(null)
            publish(phase = Phase.FOREGROUND, nextWindowWallMs = null)
            return
        }
        if (_status.value.phase == Phase.WINDOW_OPEN) return

        val now = clock.now()
        val window = RendezvousSchedule.currentOrNextWindow(now)
        if (window.contains(now)) {
            openWindow(window.closeMs - now)
            return
        }
        setScanOverride(ScanWindowOverride.CLOSED)
        val exact = platform.canScheduleExactWakes()
        val wakeAt = clock.toWallClock(window.openMs)
        platform.scheduleWake(wakeAt, exact)
        publish(phase = Phase.WAITING, nextWindowWallMs = wakeAt, preciseTiming = exact)
    }

    private fun openWindow(durationMs: Long) {
        platform.cancelWake()
        platform.acquireWakeLock(durationMs + WAKE_LOCK_SLACK_MS)
        setScanOverride(ScanWindowOverride.OPEN)
        cancelClose = platform.runAfter(durationMs) { onWindowElapsed() }
        publish(phase = Phase.WINDOW_OPEN, nextWindowWallMs = null)
    }

    private fun onWindowElapsed() {
        synchronized(lock) {
            if (!running || _status.value.phase != Phase.WINDOW_OPEN) return
            val sawPeerThisWindow = sawPeer || hasLivePeers()
            misses = if (sawPeerThisWindow) 0 else misses + 1
            sawPeer = false
            cancelClose = null
            setScanOverride(ScanWindowOverride.CLOSED)
            platform.releaseWakeLock()
            _status.value = _status.value.copy(
                phase = Phase.WAITING,
                lastWindowSawPeer = sawPeerThisWindow,
                consecutiveMisses = misses,
                windowsCompleted = _status.value.windowsCompleted + 1
            )
            reschedule()
        }
    }

    /** Abandon an open window without counting it as a hit or a miss. */
    private fun endWindow() {
        cancelClose?.invoke()
        cancelClose = null
        if (_status.value.phase == Phase.WINDOW_OPEN) {
            platform.releaseWakeLock()
            _status.value = _status.value.copy(phase = Phase.WAITING)
        }
    }

    private fun publish(
        phase: Phase = _status.value.phase,
        nextWindowWallMs: Long? = _status.value.nextWindowWallMs,
        preciseTiming: Boolean = _status.value.preciseTiming
    ) {
        _status.value = _status.value.copy(
            phase = phase,
            consecutiveMisses = misses,
            preciseTiming = preciseTiming,
            nextWindowWallMs = nextWindowWallMs
        )
    }
}
