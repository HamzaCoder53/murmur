package com.bitchat.watch.rendezvous

/**
 * Pure scheduling math for standalone watch rendezvous.
 *
 * A standalone watch advertises continuously at low power and opens its scanner only in short
 * windows. In [Mode.SYNCED] every watch opens the same wall-clock window: five seconds before each
 * minute boundary to five seconds after it. The ten-second guard band absorbs clock disagreement
 * between watches that have been away from a time source.
 *
 * A watch that sees nobody for [MISSES_BEFORE_RECOVERY] windows in a row switches to
 * [Mode.RECOVERY] and scans on a period coprime with the minute, so its windows sweep across the
 * minute instead of staying parked at a phase where nobody else is awake. Any peer contact snaps
 * it back to [Mode.SYNCED].
 *
 * All times are mesh time in milliseconds (see [MeshClock]); nothing here reads a clock.
 */
object RendezvousSchedule {
    const val SYNC_PERIOD_MS = 60_000L
    const val WINDOW_LEAD_MS = 5_000L
    const val WINDOW_LENGTH_MS = 10_000L
    const val MISSES_BEFORE_RECOVERY = 3

    /** Both are prime and coprime with 60, so recovery windows drift across the whole minute. */
    val RECOVERY_PERIODS_MS: List<Long> = listOf(53_000L, 67_000L)

    enum class Mode { SYNCED, RECOVERY }

    data class Window(val openMs: Long, val closeMs: Long) {
        fun contains(timeMs: Long): Boolean = timeMs in openMs until closeMs
    }

    data class State(
        val mode: Mode = Mode.SYNCED,
        val consecutiveMisses: Int = 0,
        val recoveryPeriodMs: Long = RECOVERY_PERIODS_MS.first(),
        /** Mesh time recovery started; recovery windows open at anchor + n * period, n >= 1. */
        val recoveryAnchorMs: Long = 0L
    )

    /**
     * The window to act on at [nowMs]: the one currently open if there is one, otherwise the next
     * one to open.
     */
    fun currentOrNextWindow(nowMs: Long, state: State): Window = when (state.mode) {
        Mode.SYNCED -> syncedWindow(nowMs)
        Mode.RECOVERY -> recoveryWindow(nowMs, state)
    }

    private fun syncedWindow(nowMs: Long): Window {
        val boundary = Math.floorDiv(nowMs + WINDOW_LEAD_MS, SYNC_PERIOD_MS) * SYNC_PERIOD_MS
        val open = boundary - WINDOW_LEAD_MS
        val current = Window(open, open + WINDOW_LENGTH_MS)
        return if (nowMs < current.closeMs) current else current.shiftedBy(SYNC_PERIOD_MS)
    }

    private fun recoveryWindow(nowMs: Long, state: State): Window {
        val period = state.recoveryPeriodMs
        val elapsed = nowMs - state.recoveryAnchorMs
        val index = Math.floorDiv(elapsed, period).coerceAtLeast(1L)
        val open = state.recoveryAnchorMs + index * period
        val current = Window(open, open + WINDOW_LENGTH_MS)
        return if (nowMs < current.closeMs) current else current.shiftedBy(period)
    }

    private fun Window.shiftedBy(deltaMs: Long) = Window(openMs + deltaMs, closeMs + deltaMs)

    /**
     * Advance the state when a window closes.
     *
     * @param sawPeer whether any peer was contacted since the previous window closed
     * @param nowMs mesh time at close, used as the recovery anchor
     * @param recoveryPick selects the recovery period for a new recovery episode; callers pass a
     *   random value so two watches that lost each other do not pick the same period every time
     */
    fun onWindowClosed(state: State, sawPeer: Boolean, nowMs: Long, recoveryPick: Int): State {
        if (sawPeer) return State()
        return when (state.mode) {
            Mode.RECOVERY -> state
            Mode.SYNCED -> {
                val misses = state.consecutiveMisses + 1
                if (misses < MISSES_BEFORE_RECOVERY) {
                    state.copy(consecutiveMisses = misses)
                } else {
                    State(
                        mode = Mode.RECOVERY,
                        consecutiveMisses = misses,
                        recoveryPeriodMs = RECOVERY_PERIODS_MS[
                            Math.floorMod(recoveryPick, RECOVERY_PERIODS_MS.size)
                        ],
                        recoveryAnchorMs = nowMs
                    )
                }
            }
        }
    }

    /** Peer contact at any time (for example an inbound connection) restores the synced schedule. */
    fun onPeerContact(state: State): State =
        if (state.mode == Mode.SYNCED && state.consecutiveMisses == 0) state else State()
}
