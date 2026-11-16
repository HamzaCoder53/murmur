package com.bitchat.desktop.storage

import com.bitchat.desktop.ui.ChatMessage
import com.bitchat.desktop.ui.ConversationId
import com.bitchat.desktop.ui.DeliveryState
import java.io.File
import java.nio.file.Files
import java.util.Date
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic messages only. */
class EncryptedHistoryStoreTest {
    private val dir: File = Files.createTempDirectory("skein-history-test").toFile()
    private val key = ByteArray(32) { (it * 7).toByte() }
    private val keyFor: (String) -> ByteArray = { key }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun msg(id: String, conv: ConversationId, text: String, ts: Long, mine: Boolean = false, delivery: DeliveryState? = null) =
        ChatMessage(id, conv, if (mine) "1111111111111111" else "2222222222222222", if (mine) "me" else "peer", text, Date(ts), mine, delivery)

    @Test
    fun `history survives a restart and is encrypted at rest`() {
        val first = EncryptedHistoryStore.open(dir, keyFor)
        first.append(msg("m1", ConversationId.Mesh, "synthetic public line", 1_000))
        first.append(msg("p1", ConversationId.Private("2222222222222222"), "synthetic private line", 2_000, mine = true, delivery = DeliveryState.SENT))
        first.append(msg("l1", ConversationId.Location("zzzzz"), "synthetic location line", 3_000))
        first.close()

        val raw = File(dir, EncryptedHistoryStore.FILE_NAME).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("plaintext must not be on disk", raw.contains("synthetic"))

        val reopened = EncryptedHistoryStore.open(dir, keyFor)
        val loaded = reopened.load()
        assertEquals(listOf("m1", "p1", "l1"), loaded.map { it.id })
        assertEquals(ConversationId.Private("2222222222222222"), loaded[1].conversation)
        assertEquals(DeliveryState.SENT, loaded[1].delivery)
        assertEquals(ConversationId.Location("zzzzz"), loaded[2].conversation)
        assertEquals("synthetic location line", loaded[2].text)
        reopened.close()
    }

    @Test
    fun `append replaces the saved copy so delivery updates persist`() {
        val store = EncryptedHistoryStore.open(dir, keyFor)
        val sent = msg("p1", ConversationId.Private("2222222222222222"), "synthetic", 1_000, mine = true, delivery = DeliveryState.SENT)
        store.append(sent)
        store.append(sent.copy(delivery = DeliveryState.READ))
        store.close()

        val loaded = EncryptedHistoryStore.open(dir, keyFor).load().single()
        assertEquals(DeliveryState.READ, loaded.delivery)
    }

    @Test
    fun `clear erases the file and a pending write does not bring it back`() {
        val store = EncryptedHistoryStore.open(dir, keyFor)
        repeat(50) { store.append(msg("m$it", ConversationId.Mesh, "synthetic $it", it.toLong())) }
        store.clear()
        store.flush()
        assertFalse(File(dir, EncryptedHistoryStore.FILE_NAME).exists())
        assertTrue(store.load().isEmpty())
        store.close()
        assertTrue(EncryptedHistoryStore.open(dir, keyFor).load().isEmpty())
    }

    @Test
    fun `a file sealed with another key reads as empty`() {
        EncryptedHistoryStore.open(dir, keyFor).apply { append(msg("m1", ConversationId.Mesh, "synthetic", 1)) }.close()
        val other = ByteArray(32) { 1 }
        assertTrue(EncryptedHistoryStore.open(dir) { other }.load().isEmpty())
    }

    @Test
    fun `oldest messages are dropped past the cap`() {
        val store = EncryptedHistoryStore(SealedFile(File(dir, "capped"), key, "test"), maxMessages = 3)
        (1..5).forEach { store.append(msg("m$it", ConversationId.Mesh, "synthetic", it.toLong())) }
        assertEquals(listOf("m3", "m4", "m5"), store.load().map { it.id })
        store.close()
    }
}
