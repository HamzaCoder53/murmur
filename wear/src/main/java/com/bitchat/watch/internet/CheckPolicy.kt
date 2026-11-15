package com.bitchat.watch.internet

/**
 * When the watch looks for internet messages in the timed modes (every 1 or 5 minutes).
 *
 * Instead of one fixed interval:
 * - Right after a message goes out or comes in over the internet, stay connected for a while
 *   ([BURST_MS]), so replies in a running conversation arrive at once.
 * - Then check every minute for a while longer ([WARM_MS]), then fall back to the chosen interval.
 * - On the charger battery does not matter, so stay connected.
 * - When the watch is awake anyway (screen on, app opened, network back), check right away, at
 *   most once per [OPPORTUNISTIC_MIN_GAP_MS].
 *
 * "Always on" and "Off" are not affected.
 */
object CheckPolicy {
    const val BURST_MS = 10 * 60_000L
    const val WARM_MS = 30 * 60_000L
    const val WARM_INTERVAL_MS = 60_000L
    const val OPPORTUNISTIC_MIN_GAP_MS = 60_000L

    sealed interface Plan {
        /** Keep a live connection until [untilMs] (null: as long as the condition holds). */
        data class Live(val untilMs: Long?) : Plan
        /** Check once after [delayMs]. */
        data class Check(val delayMs: Long) : Plan
        data object Idle : Plan
    }

    fun plan(
        mode: WatchNostrClient.Mode,
        nowMs: Long,
        lastActivityMs: Long?,
        charging: Boolean
    ): Plan {
        val interval = mode.intervalMs
        if (mode == WatchNostrClient.Mode.OFF) return Plan.Idle
        if (mode == WatchNostrClient.Mode.ALWAYS || interval == null) return Plan.Live(null)
        if (charging) return Plan.Live(null)
        val sinceActivity = lastActivityMs?.let { nowMs - it }?.takeIf { it >= 0 }
        return when {
            sinceActivity != null && sinceActivity < BURST_MS -> Plan.Live(lastActivityMs + BURST_MS)
            sinceActivity != null && sinceActivity < WARM_MS -> Plan.Check(minOf(interval, WARM_INTERVAL_MS))
            else -> Plan.Check(interval)
        }
    }

    /** Whether a wake-up the watch had anyway is worth a check now. */
    fun opportunistic(mode: WatchNostrClient.Mode, nowMs: Long, lastCheckMs: Long?): Boolean =
        mode.intervalMs != null && (lastCheckMs == null || nowMs - lastCheckMs >= OPPORTUNISTIC_MIN_GAP_MS)
}
