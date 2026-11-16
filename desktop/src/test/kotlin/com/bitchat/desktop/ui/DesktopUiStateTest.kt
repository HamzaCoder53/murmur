package com.bitchat.desktop.ui

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUiStateTest {
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
}
