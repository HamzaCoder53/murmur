package com.bitchat.desktop.platform

/**
 * OS [Notifier]. On macOS, Notification Center via `osascript` (AWT tray balloons are not shown
 * there by default); title and body are passed as script arguments, never spliced into the
 * script. Elsewhere, the tray balloon ([AwtTrayController.displayMessage]). Either path falls back
 * to the other when it cannot show the message.
 */
class OsNotifier(
    private val tray: AwtTrayController? = null,
    private val hostOs: HostOs = HostOs.current(),
    private val runner: ProcessRunner = SystemProcessRunner,
) : Notifier {
    override fun notify(id: String, title: String, body: String) {
        if (hostOs == HostOs.MAC) {
            if (notifyMac(title, body)) return
            trayMessage(title, body)
            return
        }
        trayMessage(title, body)
    }

    private fun trayMessage(title: String, body: String): Boolean =
        tray != null && tray.isSupported && tray.displayMessage(title, body)

    private fun notifyMac(title: String, body: String): Boolean = try {
        runner.run(
            listOf(
                "/usr/bin/osascript",
                "-e", "on run argv",
                "-e", "display notification (item 1 of argv) with title (item 2 of argv)",
                "-e", "end run",
                "--", body, title,
            ),
            null,
        ).exitCode == 0
    } catch (_: Exception) {
        false
    }

    override fun cancel(id: String) = Unit
}
