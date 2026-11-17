package com.bitchat.desktop.platform

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Crash watchdog loop: spawns the child Skein process and restarts it after a crash
 * (non-zero exit), with exponential backoff and a restart-rate cap.
 *
 * The cap counts crashes in a sliding window of recent crash times, and only time the child
 * actually ran counts towards that window (backoff sleeps do not), so a child that keeps crashing
 * shortly after start is given up on however long each cycle takes.
 */
object CrashWatchdog {
    private const val MAX_FAST_CRASHES = 5
    private val WINDOW_MS = TimeUnit.SECONDS.toMillis(60)

    fun run(
        command: List<String>,
        maxRestartsInWindow: Int = MAX_FAST_CRASHES,
        windowMs: Long = WINDOW_MS,
        sleepMs: (Long) -> Unit = Thread::sleep,
        clockMs: () -> Long = System::currentTimeMillis,
        launch: (List<String>) -> Int = { cmd -> ProcessBuilder(cmd).inheritIO().start().waitFor() },
    ): Int {
        // Uptime-only clock: the sum of child run times, so backoff never ages crashes out.
        var uptime = 0L
        val recentCrashes = ArrayDeque<Long>()
        while (true) {
            val started = clockMs()
            val exitCode = launch(command)
            uptime += (clockMs() - started).coerceAtLeast(0)
            if (exitCode == 0) return 0

            recentCrashes.addLast(uptime)
            while (recentCrashes.isNotEmpty() && uptime - recentCrashes.first() > windowMs) {
                recentCrashes.removeFirst()
            }
            val crashes = recentCrashes.size
            if (crashes > maxRestartsInWindow) {
                System.err.println("Skein watchdog: too many crashes ($crashes), giving up.")
                return exitCode
            }
            val backoff = TimeUnit.SECONDS.toMillis(minOf(16L, 1L shl (crashes - 1).coerceAtMost(4)))
            sleepMs(backoff)
        }
    }
}
