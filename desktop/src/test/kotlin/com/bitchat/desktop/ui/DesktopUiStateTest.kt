package com.bitchat.desktop.ui

import androidx.compose.ui.unit.LayoutDirection
import com.bitchat.android.model.BitchatMessage
import com.bitchat.desktop.TestDevices
import com.bitchat.desktop.platform.AutostartController
import com.bitchat.desktop.platform.Notifier
import com.bitchat.desktop.platform.TrayController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class DesktopUiStateTest {
    private val devices = TestDevices()

    @After
    fun tearDown() = devices.close()

    @Test
    fun `Arabic language uses RTL layout direction and translated tab labels`() {
        assertEquals(LayoutDirection.Ltr, AppLanguage.ENGLISH.layoutDirection)
        assertEquals(LayoutDirection.Rtl, AppLanguage.ARABIC.layoutDirection)

        val en = stringsFor(AppLanguage.ENGLISH)
        val ar = stringsFor(AppLanguage.ARABIC)
        assertEquals("Skein", en.appTitle)
        assertTrue(ar.tabChat.isNotBlank() && ar.tabChat != en.tabChat)
        assertTrue(ar.tabSettings.isNotBlank() && ar.tabSettings != en.tabSettings)
    }

    private class FakeNotifier : Notifier {
        val shown = mutableListOf<Triple<String, String, String>>()
        override fun notify(id: String, title: String, body: String) { shown += Triple(id, title, body) }
        override fun cancel(id: String) = Unit
    }

    private class FakeTray : TrayController {
        val counts = mutableListOf<Int>()
        override val isSupported = true
        override fun show(tooltip: String, onOpen: () -> Unit, onQuit: () -> Unit) = Unit
        override fun setUnreadCount(count: Int) { counts += count }
        override fun hide() = Unit
    }

    private fun dm(id: String, from: String, text: String = "synthetic secret text") = BitchatMessage(
        id = id, sender = "nick-$from", content = text, timestamp = Date(), isPrivate = true, senderPeerID = from,
    )

    @Test
    fun `incoming DMs notify and badge while the app is hidden, and opening the DM tab clears the badge`() {
        val notifier = FakeNotifier()
        val tray = FakeTray()
        val alerts = PrivateMessageAlerts(notifier, tray)

        alerts.onIncoming(dm("1", "aa"), "alice")
        alerts.onIncoming(dm("2", "aa"), "alice")
        assertEquals(2, notifier.shown.size)
        assertEquals(listOf(1, 2), tray.counts)
        // The preview names the sender but never carries message text.
        assertTrue(notifier.shown.none { "synthetic secret text" in it.third })
        assertEquals("New private message from alice", notifier.shown[0].third)

        // Window in front on another tab: badge only, no notification.
        alerts.setAppInFront(true)
        alerts.onIncoming(dm("3", "aa"), "alice")
        assertEquals(2, notifier.shown.size)
        assertEquals(3, alerts.unreadCount)

        // Opening the DM tab clears the badge, and messages read there do not count.
        alerts.setViewingDms(true)
        assertEquals(0, alerts.unreadCount)
        assertEquals(0, tray.counts.last())
        alerts.onIncoming(dm("4", "aa"), "alice")
        assertEquals(0, alerts.unreadCount)
        assertEquals(2, notifier.shown.size)

        // Hiding the window again resumes notifications.
        alerts.setAppInFront(false)
        alerts.onIncoming(dm("5", "aa"), "alice")
        assertEquals(3, notifier.shown.size)
        assertEquals(1, alerts.unreadCount)
    }

    @Test
    fun `the start-at-login switch shows what the OS accepted, not what was clicked`() {
        val failing = object : AutostartController {
            override val isSupported = true
            override fun isEnabled() = false
            override fun setEnabled(enabled: Boolean) = false
        }
        assertFalse(applyAutostart(failing, true))

        var on = false
        val working = object : AutostartController {
            override val isSupported = true
            override fun isEnabled() = on
            override fun setEnabled(enabled: Boolean): Boolean { on = enabled; return true }
        }
        assertTrue(applyAutostart(working, true))
        assertFalse(applyAutostart(working, false))

        val unsupported = object : AutostartController {
            override val isSupported = false
            override fun isEnabled() = false
            override fun setEnabled(enabled: Boolean): Boolean = error("must not be called")
        }
        assertFalse(applyAutostart(unsupported, true))
    }

    @Test
    fun `DM view shows the whole conversation with the selected peer only`() {
        val mine = dm("m1", "me").copy(recipientNickname = "bob")
        val fromBob = dm("b1", "bob")
        val fromCarol = dm("c1", "carol")
        val conversations = mapOf("bob" to listOf(fromBob, mine), "carol" to listOf(fromCarol))

        assertEquals(listOf("b1", "m1"), conversationWith(conversations, "bob").map { it.id })
        assertEquals(listOf("c1"), conversationWith(conversations, "carol").map { it.id })
        assertTrue(conversationWith(conversations, null).isEmpty())
        assertEquals(listOf("dave", "bob", "carol"), dmPeers(listOf("dave", "bob"), conversations))
    }

    @Test
    fun `UI settings persist across launches`() {
        val dir = devices.context("settings").rootDir
        val store = DesktopSettingsStore(dir)
        assertEquals(DesktopSettingsStore.Saved(), store.load())
        store.setLanguage(AppLanguage.ARABIC)
        store.setShowInDock(false)
        store.setAutostartPrompted()
        com.bitchat.desktop.shim.ShimRuntime.resetCachesForTests()
        val reloaded = DesktopSettingsStore(dir).load()
        assertEquals(AppLanguage.ARABIC, reloaded.language)
        assertFalse(reloaded.showInDock)
        assertTrue(reloaded.autostartPrompted)
    }
}
