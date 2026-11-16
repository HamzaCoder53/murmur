package com.bitchat.desktop

import kotlin.system.exitProcess

/**
 * Restarts Skein so every component reloads the (new or erased) identity from disk, as the phone
 * does after linking or a restore. The new process is started with [RELAUNCHED_ARG], which makes
 * it wait for this process to release the single-instance lock instead of exiting at once.
 */
object DesktopRestart {
    const val RELAUNCHED_ARG = "--relaunched"

    fun relaunch() {
        try {
            val info = ProcessHandle.current().info()
            val command = info.command().orElse(null)
            if (command != null) {
                val args = info.arguments().orElse(emptyArray()).filter { it != RELAUNCHED_ARG }
                ProcessBuilder(listOf(command) + args + RELAUNCHED_ARG).inheritIO().start()
            }
        } catch (_: Exception) {
            // Could not relaunch: exiting still guarantees no component keeps the old identity.
        }
        exitProcess(0)
    }
}
