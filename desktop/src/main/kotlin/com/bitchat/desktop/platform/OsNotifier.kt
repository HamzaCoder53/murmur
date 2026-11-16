package com.bitchat.desktop.platform

/**
 * OS [Notifier]: delegates to [AwtTrayController.displayMessage] when available, or macOS
 * `osascript` notification center as a fallback.
 */
class OsNotifier(
    private val tray: AwtTrayController? = null,
    private val hostOs: HostOs = HostOs.current(),
) : Notifier {
    override fun notify(id: String, title: String, body: String) {
        if (tray != null && tray.isSupported) {
            tray.displayMessage(title, body)
            return
        }
        if (hostOs == HostOs.MAC) {
            try {
                ProcessBuilder(
                    "/usr/bin/osascript",
                    "-e", "on run argv",
                    "-e", "display notification (item 1 of argv) with title (item 2 of argv)",
                    "-e", "end run",
                    "--", body, title,
                ).start()
            } catch (_: Exception) {
            }
        }
    }

    override fun cancel(id: String) = Unit
}
