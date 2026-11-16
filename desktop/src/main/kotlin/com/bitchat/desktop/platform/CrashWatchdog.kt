package com.bitchat.desktop.platform

import java.util.concurrent.TimeUnit

/**
 * Crash watchdog loop: spawns the child Skein process and restarts it after a crash
 * (non-zero exit), with exponential backoff and a restart-rate cap.
 */
object CrashWatchdog {
    private const val MAX_FAST_CRASHES = 5
    private const val WINDOW_MS = 60_000L

    fun run(
        command: List<String>,
        maxRestartsInWindow: Int = MAX_FAST_CRASHES,
        sleepMs: (Long) -> Unit = Thread::sleep,
    ): Int {
        var crashesInWindow = 0
        var windowStart = System.currentTimeMillis()
        while (true) {
            val proc = ProcessBuilder(command)
                .inheritIO()
                .start()
            val exitCode = proc.waitFor()
            if (exitCode == 0) return 0

            val now = System.currentTimeMillis()
            if (now - windowStart > WINDOW_MS) {
                windowStart = now
                crashesInWindow = 0
            }
            crashesInWindow++
            if (crashesInWindow > maxRestartsInWindow) {
                System.err.println("Skein watchdog: too many crashes ($crashesInWindow), giving up.")
                return exitCode
            }
            val backoff = TimeUnit.SECONDS.toMillis(minOf(16L, 1L shl (crashesInWindow - 1)))
            sleepMs(backoff)
        }
    }
}
