package com.bitchat.desktop.platform

import java.io.File
import java.util.Base64

/**
 * OS start-at-login controller:
 * - macOS: LaunchAgent plist under `~/Library/LaunchAgents/xfuturehb.murmur.desktop.plist`
 * - Windows: `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` entry `Skein`
 * Both launch with `--watchdog --autostart`, so Skein starts hidden in the menu bar / tray under
 * the crash watchdog.
 *
 * The login entry must point at the packaged launcher (the `jpackage.app-path` property set by
 * the jpackage launcher). Under `gradle run` or a bare `java` there is no such launcher, and a
 * saved entry would fail at every login, so the controller reports itself unsupported.
 */
class OsAutostartController(
    private val hostOs: HostOs = HostOs.current(),
    private val executablePath: String? = launcherPath(),
    private val macLaunchAgentsDir: File = File(System.getProperty("user.home"), "Library/LaunchAgents"),
    private val runner: ProcessRunner = SystemProcessRunner,
) : AutostartController {

    override val isSupported: Boolean
        get() = (hostOs == HostOs.MAC || hostOs == HostOs.WINDOWS) && !executablePath.isNullOrBlank()

    private val macPlistFile: File
        get() = File(macLaunchAgentsDir, "xfuturehb.murmur.desktop.plist")

    override fun isEnabled(): Boolean = when (hostOs) {
        HostOs.MAC -> macPlistFile.isFile
        HostOs.WINDOWS -> queryWindowsRunKey()
        HostOs.OTHER -> false
    }

    override fun setEnabled(enabled: Boolean): Boolean {
        if (!isSupported) return false
        val exe = executablePath!!
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
                val args = (listOf(exe) + LOGIN_ARGS).joinToString("\n") { "        <string>${xmlEscape(it)}</string>" }
                macPlistFile.writeText(
                    """
                    |<?xml version="1.0" encoding="UTF-8"?>
                    |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    |<plist version="1.0">
                    |<dict>
                    |    <key>Label</key>
                    |    <string>xfuturehb.murmur.desktop</string>
                    |    <key>ProgramArguments</key>
                    |    <array>
                    |$args
                    |    </array>
                    |    <key>RunAtLoad</key>
                    |    <true/>
                    |</dict>
                    |</plist>
                    |""".trimMargin(),
                )
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun queryWindowsRunKey(): Boolean = try {
        runner.run(listOf("reg", "query", RUN_KEY_REG, "/v", RUN_VALUE), null).exitCode == 0
    } catch (_: Exception) {
        false
    }

    private fun setWindowsAutostart(enabled: Boolean, exe: String): Boolean = try {
        if (enabled) {
            // reg.exe /d needs embedded quotes, which Java's Windows argument quoting mangles.
            // An -EncodedCommand script carries the exact value with no shell quoting at all.
            val script = windowsSetScript(exe)
            val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
            runner.run(
                listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded),
                null,
            ).exitCode == 0
        } else {
            runner.run(listOf("reg", "delete", RUN_KEY_REG, "/v", RUN_VALUE, "/f"), null).exitCode == 0 ||
                !queryWindowsRunKey()
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        val LOGIN_ARGS = listOf("--watchdog", "--autostart")
        private const val RUN_KEY_REG = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        private const val RUN_KEY_PS = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        private const val RUN_VALUE = "Skein"

        /** The packaged launcher (set by jpackage), or null when not running from an install. */
        fun launcherPath(): String? =
            System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() && File(it).isFile }

        /** The Run value: the quoted launcher path followed by [LOGIN_ARGS]. */
        internal fun windowsRunValue(exe: String): String = "\"$exe\" " + LOGIN_ARGS.joinToString(" ")

        internal fun windowsSetScript(exe: String): String {
            val value = windowsRunValue(exe).replace("'", "''")
            return "\$ErrorActionPreference='Stop';" +
                "New-ItemProperty -Path '$RUN_KEY_PS' -Name '$RUN_VALUE' -PropertyType String -Value '$value' -Force | Out-Null"
        }

        private fun xmlEscape(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
