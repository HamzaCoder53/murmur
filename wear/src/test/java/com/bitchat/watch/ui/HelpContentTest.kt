package com.bitchat.watch.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class HelpContentTest {

    @Test
    fun `every help card is short enough to read on a watch`() {
        HelpContent.topics.forEach { topic ->
            assertTrue("${topic.title} title too long", topic.title.length <= 20)
            assertTrue("${topic.title} body too long (${topic.body.length})", topic.body.length <= 180)
        }
    }

    @Test
    fun `the guide covers linking, internet messages and the tile`() {
        val titles = HelpContent.topics.map { it.title }
        listOf("Link to phone", "Internet messages", "Tile", "Complication", "Favorites").forEach {
            assertTrue("missing $it", it in titles)
        }
    }
}
