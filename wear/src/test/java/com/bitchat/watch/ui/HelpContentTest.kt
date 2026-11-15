package com.bitchat.watch.ui

import com.bitchat.watch.R
import com.bitchat.watch.testing.ResourceStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpContentTest {

    @Test
    fun `every help card is short enough to read on a watch, in English and Arabic`() {
        listOf("", "ar").forEach { lang ->
            HelpContent.topics.forEach { topic ->
                val title = ResourceStrings.get(topic.title, lang)
                val body = ResourceStrings.get(topic.body, lang)
                assertTrue("[$lang] $title title too long", title.length <= 20)
                assertTrue("[$lang] $title body too long (${body.length})", body.length <= 190)
            }
        }
    }

    @Test
    fun `the guide covers linking, internet messages, the tile and language`() {
        val titles = HelpContent.topics.map { ResourceStrings.get(it.title) }
        listOf("Link to phone", "Internet messages", "Tile", "Complication", "Favorites", "Language").forEach {
            assertTrue("missing $it", it in titles)
        }
    }

    @Test
    fun `arabic guide is actually translated`() {
        HelpContent.topics.forEach { topic ->
            assertNotEquals(ResourceStrings.get(topic.body), ResourceStrings.get(topic.body, "ar"))
        }
    }

    @Test
    fun `every watch string has an arabic translation`() {
        assertEquals(emptySet<String>(), ResourceStrings.names() - ResourceStrings.names("ar"))
        assertEquals("Skein", ResourceStrings.get(R.string.app_name, "ar"))
    }
}
