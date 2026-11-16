package com.bitchat.desktop.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.desktop.TestDevices
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiFormattingTest {
    @Test
    fun `fingerprint is grouped like the phone`() {
        val fp = "0123456789abcdef".repeat(4)
        assertEquals(
            "0123 4567 89AB CDEF\n0123 4567 89AB CDEF\n0123 4567 89AB CDEF\n0123 4567 89AB CDEF",
            formatFingerprint(fp),
        )
    }

    @Test
    fun `mention parsing only accepts known nicknames`() {
        assertEquals(listOf("alice"), parseMentions("hi @alice and @nobody @alice", setOf("alice", "bob")))
        assertTrue(mentionsNickname("yo @Tester!", "tester"))
        assertFalse(mentionsNickname("email tester@example", "tester2"))
    }

    @Test
    fun `mention suggestions follow the token being typed`() {
        val nicks = listOf("alice", "albert", "bob")
        assertEquals(listOf("albert", "alice"), mentionSuggestions("hey @al", nicks))
        assertEquals(nicks.sorted(), mentionSuggestions("@", nicks))
        assertTrue(mentionSuggestions("hey al", nicks).isEmpty())
        assertTrue(mentionSuggestions("@alice", listOf("alice")).isEmpty())
        assertEquals("hey @alice ", applyMention("hey @al", "alice"))
        assertEquals("no mention", applyMention("no mention", "alice"))
    }

    @Test
    fun `timestamps show time today and date otherwise`() {
        val zone = ZoneOffset.UTC
        val now = Date(LocalDateTime.of(2030, 1, 15, 18, 0).toInstant(zone).toEpochMilli())
        val today = Date(LocalDateTime.of(2030, 1, 15, 9, 5).toInstant(zone).toEpochMilli())
        val before = Date(LocalDateTime.of(2030, 1, 3, 9, 5).toInstant(zone).toEpochMilli())
        assertEquals("09:05", formatTimestamp(today, now, zone, AppLanguage.ENGLISH))
        assertEquals("3 Jan 09:05", formatTimestamp(before, now, zone, AppLanguage.ENGLISH))
        assertTrue(formatTimestamp(before, now, zone, AppLanguage.ARABIC).isNotBlank())
    }

    @Test
    fun `geohash and nickname validation`() {
        assertEquals("u4pr", normalizeGeohash(" #U4PR "))
        assertNull(normalizeGeohash("abc")) // 'a' is not in the geohash alphabet
        assertNull(normalizeGeohash(""))
        assertNull(normalizeGeohash("0123456789bcd"))
        assertEquals("nick", validNickname(" nick "))
        assertNull(validNickname(" "))
    }

    @Test
    fun `every string is translated and Arabic is right to left`() {
        val en = stringsFor(AppLanguage.ENGLISH)
        val ar = stringsFor(AppLanguage.ARABIC)
        val sameOnPurpose = setOf("meshChannel")
        val fields = UiStrings::class.java.declaredFields.filter { it.type == String::class.java }
        assertTrue(fields.size > 50)
        fields.forEach { p ->
            p.isAccessible = true
            val e = p.get(en) as String
            val a = p.get(ar) as String
            assertTrue("${p.name} empty", e.isNotBlank() && a.isNotBlank())
            if (p.name !in sameOnPurpose) assertTrue("${p.name} not translated", e != a)
        }
        assertEquals(androidx.compose.ui.unit.LayoutDirection.Rtl, AppLanguage.ARABIC.layoutDirection)
        assertEquals(AppLanguage.ARABIC, AppLanguage.fromCode("ar"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("xx"))
    }

    @Test
    fun `mesh messages map to chat messages with delivery state`() {
        val me = "1111111111111111"
        val m = BitchatMessage(
            id = "id1", sender = "me", content = "hi", timestamp = Date(1), senderPeerID = me,
            deliveryStatus = DeliveryStatus.Delivered("peer", Date(2)),
        )
        val c = toChatMessage(m, me, ConversationId.Mesh)
        assertTrue(c.isMine)
        assertEquals(DeliveryState.DELIVERED, c.delivery)
        assertEquals(DeliveryState.READ, toDeliveryState(DeliveryStatus.Read("x", Date(3))))
        assertEquals(DeliveryState.FAILED, toDeliveryState(DeliveryStatus.Failed("no route")))
        assertEquals(DeliveryState.SENDING, toDeliveryState(DeliveryStatus.Sending))
    }

    @Test
    fun `settings survive a reload through the preference store`() {
        TestDevices().use { devices ->
            val ctx = devices.context("ui")
            val port = PrefsSettingsPort(ctx)
            assertEquals(DesktopUiSettings(), port.load())
            val changed = DesktopUiSettings(
                language = AppLanguage.ARABIC,
                broadcastPower = BroadcastPower.LOW,
                showInDock = false,
                saveHistory = false,
            )
            port.save(changed)
            assertEquals(changed, PrefsSettingsPort(ctx).load())
        }
    }
}
