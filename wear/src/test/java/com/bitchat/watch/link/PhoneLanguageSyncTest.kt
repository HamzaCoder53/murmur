package com.bitchat.watch.link

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneLanguageSyncTest {

    @Test
    fun `arabic and english phone languages carry over to the watch`() {
        assertEquals("ar", PhoneLanguageSync.watchTagFor("ar"))
        assertEquals("ar", PhoneLanguageSync.watchTagFor("ar-EG"))
        assertEquals("en", PhoneLanguageSync.watchTagFor("en-GB,fr"))
    }

    @Test
    fun `system default or an untranslated language leaves the watch on its own language`() {
        assertEquals("", PhoneLanguageSync.watchTagFor(""))
        assertEquals("", PhoneLanguageSync.watchTagFor("fr"))
    }
}
