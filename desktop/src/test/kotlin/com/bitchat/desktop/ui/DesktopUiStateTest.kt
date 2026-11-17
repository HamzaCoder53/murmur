package com.bitchat.desktop.ui

import androidx.compose.ui.unit.LayoutDirection
import com.bitchat.desktop.TestDevices
import com.bitchat.desktop.platform.AutostartController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `UI settings, including the first-run answer, persist across launches`() {
        val ctx = devices.context("settings")
        val port = PrefsSettingsPort(ctx)
        assertEquals(DesktopUiSettings(), port.load())
        port.save(DesktopUiSettings(language = AppLanguage.ARABIC, showInDock = false, firstRunPromptsShown = true))
        val reloaded = PrefsSettingsPort(devices.context("settings")).load()
        assertEquals(AppLanguage.ARABIC, reloaded.language)
        assertFalse(reloaded.showInDock)
        assertTrue(reloaded.firstRunPromptsShown)
    }
}
