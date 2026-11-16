package com.bitchat.desktop.platform

import java.io.File

/**
 * OS start-at-login controller:
 * - macOS: LaunchAgent plist under `~/Library/LaunchAgents/xfuturehb.murmur.desktop.plist`
 * - Windows: `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` entry `Skein`
 * Both launch with `--autostart` so Skein starts hidden in the menu bar / tray.
 */
class OsAutostartController(
    private val hostOs: HostOs = HostOs.current(),
    private val executablePath: String? = ProcessHandle.current().info().command().orElse(null),
    private val macLaunchAgentsDir: File = File(System.getProperty("user.home"), "Library/LaunchAgents"),
) : AutostartController {

    override val isSupported: Boolean
        get() {
            if (hostOs != HostOs.MAC && hostOs != HostOs.WINDOWS) return false
            val exe = executablePath?.takeIf { it.isNotBlank() } ?: return false
            val name = File(exe).name.lowercase()
            return name != "java" && name != "java.exe" && name != "javaw.exe"
        }

    private val macPlistFile: File
        get() = File(macLaunchAgentsDir, "xfuturehb.murmur.desktop.plist")

    override fun isEnabled(): Boolean = when (hostOs) {
        HostOs.MAC -> macPlistFile.isFile
        HostOs.WINDOWS -> queryWindowsRunKey()
        HostOs.OTHER -> false
    }

    override fun setEnabled(enabled: Boolean): Boolean {
        val exe = executablePath?.takeIf { it.isNotBlank() } ?: return false
        return when (hostOs) {
            HostOs.MAC -> setMacAutostart(enabled, exe)
            HostOs.WINDOWS -> setWindowsAutostart(enabled, exe)
            HostOs.OTHER -> false
        }
    }

    private fun setMacAutostart(enabled: Boolean, exe: String): Boolean {
        return try {
            if (!enabled) {
                !macPlistFile.exists() || macPlistFile.delete()
            } else {
                macLaunchAgentsDir.mkdirs()
                val escapedExe = exe.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                macPlistFile.writeText(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0">
                    <dict>
                        <key>Label</key>
                        <string>xfuturehb.murmur.desktop</string>
                        <key>ProgramArguments</key>
                        <array>
                            <string>$escapedExe</string>
                            <string>--autostart</string>
                        </array>
                        <key>RunAtLoad</key>
                        <true/>
                    </dict>
                    </plist>
                    """.trimIndent() + "\n",
                )
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun queryWindowsRunKey(): Boolean = try {
        val proc = ProcessBuilder(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
            "/v", "Skein",
        ).start()
        proc.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun setWindowsAutostart(enabled: Boolean, exe: String): Boolean = try {
        val args = if (enabled) {
            listOf(
                "reg", "add",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "Skein", "/t", "REG_SZ", "/d", "\"$exe\" --autostart", "/f",
            )
        } else {
            listOf(
                "reg", "delete",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "Skein", "/f",
            )
        }
        ProcessBuilder(args).start().waitFor() == 0
    } catch (_: Exception) {
        false
    }
}
