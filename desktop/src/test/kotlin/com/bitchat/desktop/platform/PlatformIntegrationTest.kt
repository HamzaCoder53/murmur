package com.bitchat.desktop.platform

import com.bitchat.desktop.TestDevices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlatformIntegrationTest {
    private val devices = TestDevices()

    @After
    fun tearDown() = devices.close()

    @Test
    fun `macOS autostart writes and removes LaunchAgent plist with --autostart`() {
        val agentsDir = File(devices.root, "LaunchAgents")
        val controller = OsAutostartController(
            hostOs = HostOs.MAC,
            executablePath = "/Applications/Skein.app/Contents/MacOS/Skein",
            macLaunchAgentsDir = agentsDir,
        )
        assertTrue(controller.isSupported)
        assertFalse(controller.isEnabled())

        assertTrue(controller.setEnabled(true))
        assertTrue(controller.isEnabled())
        val plist = File(agentsDir, "xfuturehb.murmur.desktop.plist").readText()
        assertTrue(plist.contains("<string>--autostart</string>"))
        assertTrue(plist.contains("<string>/Applications/Skein.app/Contents/MacOS/Skein</string>"))

        assertTrue(controller.setEnabled(false))
        assertFalse(controller.isEnabled())
    }

    @Test
    fun `CrashWatchdog exits 0 when child exits 0`() {
        val code = CrashWatchdog.run(
            command = listOf("sh", "-c", "exit 0"),
            sleepMs = {},
        )
        assertEquals(0, code)
    }

    @Test
    fun `CrashWatchdog stops after exceeding max fast restarts`() {
        var sleeps = 0
        val code = CrashWatchdog.run(
            command = listOf("sh", "-c", "exit 42"),
            maxRestartsInWindow = 2,
            sleepMs = { sleeps++ },
        )
        assertEquals(42, code)
        assertEquals(2, sleeps)
    }
}
