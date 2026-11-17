package com.bitchat.desktop.platform

import com.bitchat.desktop.TestDevices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class PlatformIntegrationTest {
    private val devices = TestDevices()

    @After
    fun tearDown() = devices.close()

    /** Records every command and answers from a script, keyed by the command's first arguments. */
    private class FakeRunner(private val answer: (List<String>, String?) -> ProcessResult) : ProcessRunner {
        val calls = mutableListOf<Pair<List<String>, String?>>()
        override fun run(command: List<String>, stdin: String?): ProcessResult {
            calls += command to stdin
            return answer(command, stdin)
        }
    }

    // ---- start at login --------------------------------------------------------------------

    @Test
    fun `macOS autostart writes a LaunchAgent that starts hidden under the watchdog`() {
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
        val args = Regex("<string>([^<]*)</string>").findAll(plist.substringAfter("ProgramArguments"))
            .map { it.groupValues[1] }.take(3).toList()
        assertEquals(listOf("/Applications/Skein.app/Contents/MacOS/Skein", "--watchdog", "--autostart"), args)

        assertTrue(controller.setEnabled(false))
        assertFalse(controller.isEnabled())
    }

    @Test
    fun `autostart is unsupported and refuses to enable without a packaged launcher`() {
        val agentsDir = File(devices.root, "LaunchAgents")
        val controller = OsAutostartController(hostOs = HostOs.MAC, executablePath = null, macLaunchAgentsDir = agentsDir)
        assertFalse(controller.isSupported)
        assertFalse(controller.setEnabled(true))
        assertFalse(File(agentsDir, "xfuturehb.murmur.desktop.plist").exists())
        // gradle test runs on a plain JVM: there is no jpackage launcher to point at.
        assertNull(OsAutostartController.launcherPath())
    }

    @Test
    fun `Windows autostart writes the Run value through an encoded script with exact quoting`() {
        val exe = "C:\\Program Files\\Skein's App\\Skein.exe"
        val runner = FakeRunner { _, _ -> ProcessResult(0, "") }
        val controller = OsAutostartController(hostOs = HostOs.WINDOWS, executablePath = exe, runner = runner)
        assertTrue(controller.setEnabled(true))

        val (cmd, stdin) = runner.calls.single()
        assertEquals("powershell.exe", cmd.first())
        assertNull(stdin)
        val script = String(Base64.getDecoder().decode(cmd.last()), Charsets.UTF_16LE)
        assertEquals(OsAutostartController.windowsSetScript(exe), script)
        // Single quotes are doubled inside the PowerShell literal; the value itself is exact.
        assertTrue(script.contains("-Value '\"C:\\Program Files\\Skein''s App\\Skein.exe\" --watchdog --autostart'"))
        assertEquals("\"$exe\" --watchdog --autostart", OsAutostartController.windowsRunValue(exe))
    }

    @Test
    fun `Windows autostart reports failure when the registry write fails`() {
        val runner = FakeRunner { _, _ -> ProcessResult(1, "") }
        val controller = OsAutostartController(hostOs = HostOs.WINDOWS, executablePath = "C:\\Skein.exe", runner = runner)
        assertFalse(controller.setEnabled(true))
        assertFalse(controller.isEnabled())
    }

    // ---- crash watchdog --------------------------------------------------------------------

    @Test
    fun `CrashWatchdog exits 0 when child exits 0`() {
        val code = CrashWatchdog.run(command = listOf("skein"), sleepMs = {}, launch = { 0 })
        assertEquals(0, code)
    }

    @Test
    fun `CrashWatchdog stops after exceeding max fast restarts`() {
        var sleeps = 0
        val code = CrashWatchdog.run(
            command = listOf("skein"),
            maxRestartsInWindow = 2,
            sleepMs = { sleeps++ },
            launch = { 42 },
        )
        assertEquals(42, code)
        assertEquals(2, sleeps)
    }

    @Test
    fun `CrashWatchdog gives up on a crash loop even when backoff makes each cycle long`() {
        // Each child crashes 2 s after start and every backoff is long; wall-clock windowing
        // would reset forever. Only run time counts, so five quick crashes trip the cap.
        var now = 0L
        var launches = 0
        val code = CrashWatchdog.run(
            command = listOf("skein"),
            maxRestartsInWindow = 5,
            windowMs = 60_000,
            sleepMs = { now += 30_000 },
            clockMs = { now },
            launch = { launches++; now += 2_000; 3 },
        )
        assertEquals(3, code)
        assertEquals(6, launches)
    }

    @Test
    fun `CrashWatchdog keeps restarting a child that crashes only rarely`() {
        var now = 0L
        var launches = 0
        val code = CrashWatchdog.run(
            command = listOf("skein"),
            maxRestartsInWindow = 2,
            windowMs = 60_000,
            sleepMs = {},
            clockMs = { now },
            // Ten crashes, each after 10 minutes of uptime, then a clean exit.
            launch = { launches++; now += 600_000; if (launches <= 10) 1 else 0 },
        )
        assertEquals(0, code)
        assertEquals(11, launches)
    }

    // ---- macOS Keychain --------------------------------------------------------------------

    private val keyHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

    @Test
    fun `Keychain returns the stored key`() {
        val runner = FakeRunner { _, _ -> ProcessResult(0, "$keyHex\n") }
        val key = MacKeychainKeyStore(runner = runner).getOrCreateKey("skein.master")
        assertEquals(keyHex, key.joinToString("") { "%02x".format(it) })
        assertEquals(1, runner.calls.size)
    }

    @Test
    fun `Keychain creates a key only when the item is not found, over stdin and without -U`() {
        val runner = FakeRunner { cmd, _ ->
            if ("find-generic-password" in cmd) ProcessResult(MacKeychainKeyStore.EXIT_ITEM_NOT_FOUND, "")
            else ProcessResult(0, "")
        }
        val key = MacKeychainKeyStore(runner = runner).getOrCreateKey("skein.master")
        val hex = key.joinToString("") { "%02x".format(it) }
        val (addCmd, addStdin) = runner.calls[1]
        assertEquals(listOf("/usr/bin/security", "-i"), addCmd)
        assertTrue(addStdin!!.startsWith("add-generic-password "))
        assertFalse(addStdin.contains(" -U "))
        assertTrue(addStdin.contains(hex))
        runner.calls.forEach { (cmd, _) -> assertFalse(cmd.any { hex in it }) }
    }

    @Test
    fun `Keychain fails closed when the keychain is locked or access is denied`() {
        for (exit in listOf(36, 51, 128, 1)) {
            val runner = FakeRunner { _, _ -> ProcessResult(exit, "") }
            val result = runCatching { MacKeychainKeyStore(runner = runner).getOrCreateKey("skein.master") }
            assertTrue("exit $exit must throw", result.isFailure)
            assertEquals("no key may be written after exit $exit", 1, runner.calls.size)
        }
    }

    @Test
    fun `Keychain uses the existing item when another writer created it first`() {
        var finds = 0
        val runner = FakeRunner { cmd, _ ->
            when {
                "find-generic-password" in cmd ->
                    if (finds++ == 0) ProcessResult(MacKeychainKeyStore.EXIT_ITEM_NOT_FOUND, "") else ProcessResult(0, keyHex)
                else -> ProcessResult(MacKeychainKeyStore.EXIT_DUPLICATE_ITEM, "")
            }
        }
        val key = MacKeychainKeyStore(runner = runner).getOrCreateKey("skein.master")
        assertEquals(keyHex, key.joinToString("") { "%02x".format(it) })
    }

    // ---- Windows DPAPI (argv-free round trip is covered by WindowsDpapiKeyStoreTest) -------

    @Test
    fun `DPAPI failure throws and writes no key file`() {
        val dir = File(devices.root, "secrets-fail")
        val store = WindowsDpapiKeyStore(dir) { _, _ -> error("synthetic DPAPI failure") }
        assertTrue(runCatching { store.getOrCreateKey("skein.master") }.isFailure)
        assertFalse(File(dir, "skein_master.dpapi").exists())
    }

    // ---- notifications ---------------------------------------------------------------------

    @Test
    fun `macOS notifications go to Notification Center with text passed as arguments`() {
        val runner = FakeRunner { _, _ -> ProcessResult(0, "") }
        OsNotifier(tray = null, hostOs = HostOs.MAC, runner = runner).notify("id", "Skein", "New private message from x\"; quit")
        val cmd = runner.calls.single().first
        assertEquals("/usr/bin/osascript", cmd.first())
        assertEquals(listOf("--", "New private message from x\"; quit", "Skein"), cmd.takeLast(3))
    }

    @Test
    fun `Windows notifications do not shell out`() {
        val runner = FakeRunner { _, _ -> ProcessResult(0, "") }
        OsNotifier(tray = null, hostOs = HostOs.WINDOWS, runner = runner).notify("id", "Skein", "hi")
        assertTrue(runner.calls.isEmpty())
    }
}
