package com.bitchat.watch.rendezvous

/**
 * Pure scheduling math for standalone watch rendezvous.
 *
 * A standalone watch advertises continuously at low power and opens its scanner only in short
 * windows. Every watch opens the same wall-clock window: [WINDOW_LEAD_MS] before each minute
 * boundary until [WINDOW_LENGTH_MS] later. Watch clocks follow network and phone time closely, so a
 * short window centred on the minute is enough for two sleeping watches to meet.
 *
 * All times are mesh time in milliseconds (see [MeshClock]); nothing here reads a clock.
 */
object RendezvousSchedule {
    const val SYNC_PERIOD_MS = 60_000L
    const val WINDOW_LEAD_MS = 2_500L
    const val WINDOW_LENGTH_MS = 5_000L

    /** A window with less than this left is skipped for the next one rather than opened as a sliver. */
    const val MIN_WINDOW_MS = 1_000L

    data class Window(val openMs: Long, val closeMs: Long) {
        fun contains(timeMs: Long): Boolean = timeMs in openMs until closeMs
    }

    /**
     * The window to act on at [nowMs]: the one currently open if there is one (and it has at least
     * [MIN_WINDOW_MS] left), otherwise the next one to open.
     */
    fun currentOrNextWindow(nowMs: Long): Window {
        val boundary = Math.floorDiv(nowMs + WINDOW_LEAD_MS, SYNC_PERIOD_MS) * SYNC_PERIOD_MS
        val open = boundary - WINDOW_LEAD_MS
        val current = Window(open, open + WINDOW_LENGTH_MS)
        return if (nowMs < current.closeMs - MIN_WINDOW_MS) current else current.shiftedBy(SYNC_PERIOD_MS)
    }

    private fun Window.shiftedBy(deltaMs: Long) = Window(openMs + deltaMs, closeMs + deltaMs)
}
