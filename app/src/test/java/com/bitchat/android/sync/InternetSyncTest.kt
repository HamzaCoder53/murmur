package com.bitchat.android.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.MessageHistory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InternetSyncTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        InternetSyncPreferences.resetForTesting()
    }

    @Test
    fun payloadEncodeDecodeRoundTrip_msgAndReadOps() {
        val msgOp = InternetSyncProtocol.SyncOp.MsgOp(
            id = "msg-1",
            conv = "contact_${"a".repeat(64)}",
            peer = "0123456789abcdef",
            noiseKeyHex = "b".repeat(64),
            nostrPubkeyHex = "c".repeat(64),
            sender = "Bob",
            content = "Hello from device A",
            timestampMs = 1_700_000_000_000L,
            direction = InternetSyncProtocol.Direction.IN,
            deliveryState = "delivered",
            statusActor = "Alice",
            statusAtMs = 1_700_000_001_000L,
            isRead = true
        )
        val readOp = InternetSyncProtocol.SyncOp.ReadOp(
            conv = "contact_${"a".repeat(64)}",
            peer = "0123456789abcdef",
            messageIds = listOf("msg-0", "msg-1"),
            markAll = false,
            timestampMs = 1_700_000_002_000L
        )
        val envelope = InternetSyncProtocol.SyncEnvelope(
            deviceId = "device-a",
            ops = listOf(msgOp, readOp)
        )

        val encoded = InternetSyncProtocol.encode(envelope)
        assertTrue(encoded.startsWith("skein-sync1:"))
        assertFalse(encoded.startsWith("bitchat1:"))

        val decoded = InternetSyncProtocol.decode(encoded)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.version)
        assertEquals("device-a", decoded.deviceId)
        assertEquals(listOf(msgOp, readOp), decoded.ops)
    }

    @Test
    fun mediaMessages_usePlaceholdersOnly() {
        val image = BitchatMessage(
            id = "img-1",
            sender = "Alice",
            content = "/data/user/0/secret/photo.jpg",
            type = BitchatMessageType.Image,
            timestamp = Date(1_700_000_000_000L),
            isPrivate = true
        )
        val audio = BitchatMessage(
            id = "aud-1",
            sender = "Alice",
            content = "/data/user/0/secret/voice.m4a",
            type = BitchatMessageType.Audio,
            timestamp = Date(1_700_000_000_000L),
            isPrivate = true
        )
        val file = BitchatMessage(
            id = "fil-1",
            sender = "Alice",
            content = "/data/user/0/secret/doc.pdf",
            type = BitchatMessageType.File,
            timestamp = Date(1_700_000_000_000L),
            isPrivate = true
        )

        assertEquals(InternetSyncProtocol.PLACEHOLDER_PHOTO, InternetSyncProtocol.contentForSync(image))
        assertEquals(InternetSyncProtocol.PLACEHOLDER_VOICE, InternetSyncProtocol.contentForSync(audio))
        assertEquals(InternetSyncProtocol.PLACEHOLDER_FILE, InternetSyncProtocol.contentForSync(file))
    }

    @Test
    fun batchOps_splitsByMaxOpsAndMaxBytes() {
        val ops = (1..5).map { i ->
            InternetSyncProtocol.SyncOp.MsgOp(
                id = "id-$i",
                conv = "contact_${"a".repeat(64)}",
                sender = "Alice",
                content = "x".repeat(400),
                timestampMs = 1_700_000_000_000L + i,
                direction = InternetSyncProtocol.Direction.OUT
            )
        }

        val batchesByCount = InternetSyncProtocol.batchOps("dev-1", ops, maxOps = 2, maxBytes = 32_000)
        assertEquals(3, batchesByCount.size)

        val batchesBySize = InternetSyncProtocol.batchOps("dev-1", ops, maxOps = 20, maxBytes = 1_200)
        assertTrue(batchesBySize.size >= 3)
        batchesBySize.forEach { encoded ->
            assertNotNull(InternetSyncProtocol.decode(encoded))
        }
    }

    @Test
    fun malformedPayloads_areRejectedFailClosed() {
        val cases = listOf(
            "",
            "bitchat1:{}",
            "skein-sync1:",
            "skein-sync1:not-json",
            "skein-sync1:{\"v\":2,\"dev\":\"d1\",\"ops\":[{\"op\":\"read\",\"conv\":\"c\",\"all\":true,\"t\":1}]}",
            "skein-sync1:{\"v\":1,\"dev\":\"\",\"ops\":[{\"op\":\"read\",\"conv\":\"c\",\"all\":true,\"t\":1}]}",
            "skein-sync1:{\"v\":1,\"dev\":\"d1\",\"ops\":[]}",
            "skein-sync1:{\"v\":1,\"dev\":\"d1\",\"ops\":[{\"op\":\"unknown\"}]}",
            "skein-sync1:{\"v\":1,\"dev\":\"d1\",\"ops\":[{\"op\":\"msg\",\"id\":\"1\",\"conv\":\"c\",\"s\":\"A\",\"c\":\"hi\",\"t\":0,\"dir\":\"in\"}]}",
            "skein-sync1:{\"v\":1,\"dev\":\"d1\",\"ops\":[{\"op\":\"msg\",\"id\":\"1\",\"conv\":\"c\",\"s\":\"A\",\"c\":\"hi\",\"t\":10,\"dir\":\"sideways\"}]}",
            "skein-sync1:{\"v\":1,\"dev\":\"d1\",\"ops\":[{\"op\":\"msg\",\"id\":\"1\",\"conv\":\"c\",\"s\":\"A\",\"c\":\"hi\",\"t\":10,\"dir\":\"in\",\"st\":\"bogus\"}]}",
            "skein-sync1:{\"v\":1,\"dev\":\"d1\",\"ops\":[{\"op\":\"read\",\"conv\":\"c\",\"t\":10}]}"
        )

        cases.forEach { payload ->
            assertNull("Expected null for malformed payload: $payload", InternetSyncProtocol.decode(payload))
        }
    }

    @Test
    fun ownEcho_isIgnored() = runTest {
        val inserted = mutableListOf<BitchatMessage>()
        val receiver = InternetSyncReceiver(
            isSyncEnabled = { true },
            localDeviceIdProvider = { "device-self" },
            myNicknameProvider = { "Alice" },
            hasSeenMessage = { false },
            insertPrivateMessage = { _, msg, _ -> inserted += msg; true },
            markMessageRead = {},
            markConversationRead = { true }
        )

        val payload = InternetSyncProtocol.encode(
            InternetSyncProtocol.SyncEnvelope(
                deviceId = "device-self",
                ops = listOf(
                    InternetSyncProtocol.SyncOp.MsgOp(
                        id = "m-echo",
                        conv = "contact_${"a".repeat(64)}",
                        sender = "Alice",
                        content = "hi",
                        timestampMs = 1_700_000_000_000L,
                        direction = InternetSyncProtocol.Direction.OUT
                    )
                )
            )
        )

        val ownPub = "1".repeat(64)
        assertFalse(receiver.onSyncPayload(payload, 1_700_000_000, ownPub, ownPub))
        assertTrue(inserted.isEmpty())
    }

    @Test
    fun nonSelfSenderPubkey_isRejected() = runTest {
        val inserted = mutableListOf<BitchatMessage>()
        val receiver = InternetSyncReceiver(
            isSyncEnabled = { true },
            localDeviceIdProvider = { "device-b" },
            myNicknameProvider = { "Alice" },
            hasSeenMessage = { false },
            insertPrivateMessage = { _, msg, _ -> inserted += msg; true },
            markMessageRead = {},
            markConversationRead = { true }
        )

        val payload = InternetSyncProtocol.encode(
            InternetSyncProtocol.SyncEnvelope(
                deviceId = "device-a",
                ops = listOf(
                    InternetSyncProtocol.SyncOp.MsgOp(
                        id = "m-spoof",
                        conv = "contact_${"a".repeat(64)}",
                        sender = "Bob",
                        content = "spoofed",
                        timestampMs = 1_700_000_000_000L,
                        direction = InternetSyncProtocol.Direction.IN
                    )
                )
            )
        )

        assertFalse(receiver.onSyncPayload(payload, 1_700_000_000, "2".repeat(64), "1".repeat(64)))
        assertTrue(inserted.isEmpty())
    }

    @Test
    fun dedupe_ignoresDuplicateMessagesAcrossLookbackReplays() = runTest {
        val inserted = mutableListOf<BitchatMessage>()
        val seen = mutableSetOf<String>()
        val receiver = InternetSyncReceiver(
            isSyncEnabled = { true },
            localDeviceIdProvider = { "device-b" },
            myNicknameProvider = { "Alice" },
            hasSeenMessage = { it in seen },
            insertPrivateMessage = { _, msg, _ ->
                seen += msg.id
                inserted += msg
                true
            },
            markMessageRead = {},
            markConversationRead = { true }
        )

        val payload = InternetSyncProtocol.encode(
            InternetSyncProtocol.SyncEnvelope(
                deviceId = "device-a",
                ops = listOf(
                    InternetSyncProtocol.SyncOp.MsgOp(
                        id = "m-dup",
                        conv = "contact_${"a".repeat(64)}",
                        sender = "Bob",
                        content = "hello",
                        timestampMs = 1_700_000_000_000L,
                        direction = InternetSyncProtocol.Direction.IN
                    )
                )
            )
        )

        val ownPub = "1".repeat(64)
        assertTrue(receiver.onSyncPayload(payload, 1_700_000_000, ownPub, ownPub))
        assertFalse(receiver.onSyncPayload(payload, 1_700_000_000, ownPub, ownPub))
        assertEquals(1, inserted.size)
    }

    @Test
    fun readStateApplication_marksMessagesAndConversationsRead() = runTest {
        val readMessages = mutableListOf<String>()
        val readConversations = mutableListOf<String>()
        val receiver = InternetSyncReceiver(
            isSyncEnabled = { true },
            localDeviceIdProvider = { "device-b" },
            myNicknameProvider = { "Alice" },
            hasSeenMessage = { false },
            insertPrivateMessage = { _, _, _ -> true },
            markMessageRead = { readMessages += it },
            markConversationRead = { readConversations += it; true }
        )

        val conv = "contact_${"a".repeat(64)}"
        val payload = InternetSyncProtocol.encode(
            InternetSyncProtocol.SyncEnvelope(
                deviceId = "device-a",
                ops = listOf(
                    InternetSyncProtocol.SyncOp.ReadOp(
                        conv = conv,
                        messageIds = listOf("id-1", "id-2"),
                        markAll = false,
                        timestampMs = 1_700_000_000_000L
                    ),
                    InternetSyncProtocol.SyncOp.ReadOp(
                        conv = conv,
                        markAll = true,
                        timestampMs = 1_700_000_001_000L
                    )
                )
            )
        )

        val ownPub = "1".repeat(64)
        assertTrue(receiver.onSyncPayload(payload, 1_700_000_000, ownPub, ownPub))
        assertEquals(listOf("id-1", "id-2"), readMessages)
        assertEquals(listOf(conv), readConversations)
    }

    @Test
    fun giftWrapToSelfRoundTrip_withSyntheticIdentity() = runTest {
        val sharedIdentity = NostrIdentity.generate()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val publishedWraps = mutableListOf<NostrEvent>()
        val noiseKey = ByteArray(32) { 0x42 }
        val expectedConvId = ContactIdentityResolver.contactConversationIdForNoiseKey(noiseKey)

        val publisher = InternetSyncPublisher(
            isSyncEnabled = { true },
            deviceIdProvider = { "device-a" },
            identityProvider = { sharedIdentity },
            publishGiftWrap = { publishedWraps += it },
            resolveContact = { null },
            myNicknameProvider = { "Alice" },
            debounceMs = 2_000L,
            scope = scope
        )

        val msg = BitchatMessage(
            id = "sync-msg-1",
            sender = "Alice",
            content = "Encrypted sync check",
            timestamp = Date(1_700_000_123_000L),
            isPrivate = true,
            deliveryStatus = DeliveryStatus.Sent
        )

        publisher.enqueueMessage(expectedConvId, msg, isRead = true)
        scope.advanceTimeBy(2_001L)
        scope.runCurrent()

        assertEquals(1, publishedWraps.size)
        val wrap = publishedWraps.single()
        val decrypted = NostrProtocol.decryptPrivateMessage(wrap, sharedIdentity)
        assertNotNull(decrypted)
        val (plaintext, senderPubkey, rumorTimestamp) = decrypted!!
        assertEquals(sharedIdentity.publicKeyHex, senderPubkey)
        assertTrue(plaintext.startsWith("skein-sync1:"))

        val received = mutableListOf<Triple<String, BitchatMessage, Boolean>>()
        val receiver = InternetSyncReceiver(
            isSyncEnabled = { true },
            localDeviceIdProvider = { "device-b" },
            myNicknameProvider = { "Alice-B" },
            hasSeenMessage = { false },
            insertPrivateMessage = { conv, m, forceRead ->
                received += Triple(conv, m, forceRead)
                true
            },
            markMessageRead = {},
            markConversationRead = { true }
        )

        assertTrue(
            receiver.onSyncPayload(
                payload = plaintext,
                rumorTimestampSec = rumorTimestamp,
                senderPubkeyHex = senderPubkey,
                ownPubkeyHex = sharedIdentity.publicKeyHex
            )
        )
        assertEquals(1, received.size)
        val (conv, syncedMsg, forceRead) = received.single()
        assertEquals(expectedConvId, conv)
        assertEquals("sync-msg-1", syncedMsg.id)
        assertEquals("Encrypted sync check", syncedMsg.content)
        assertEquals("Alice-B", syncedMsg.sender)
        assertTrue(forceRead)
        assertEquals(1_700_000_123_000L, syncedMsg.timestamp.time)
    }

    @Test
    fun toggleOff_publishesNothing() = runTest {
        val identity = NostrIdentity.generate()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val published = mutableListOf<NostrEvent>()
        var syncEnabled = false

        val publisher = InternetSyncPublisher(
            isSyncEnabled = { syncEnabled },
            deviceIdProvider = { "device-a" },
            identityProvider = { identity },
            publishGiftWrap = { published += it },
            resolveContact = { null },
            debounceMs = 2_000L,
            scope = scope
        )

        val msg = BitchatMessage(
            id = "msg-off",
            sender = "Alice",
            content = "Should not publish",
            timestamp = Date(1_700_000_000_000L),
            isPrivate = true
        )
        publisher.enqueueMessage("contact_${"a".repeat(64)}", msg, isRead = false)
        publisher.enqueueConversationRead("contact_${"a".repeat(64)}")
        scope.advanceTimeBy(5_000L)
        scope.runCurrent()

        assertTrue(published.isEmpty())
    }

    @Test
    fun preferences_defaultOff_andDisabledWhenHistoryOff() {
        assertFalse(InternetSyncPreferences.DEFAULT_ENABLED)
        assertFalse(InternetSyncPreferences.isEnabled(context))

        InternetSyncPreferences.setEnabled(context, true)
        MessageHistory.setEnabled(context, true)
        assertTrue(InternetSyncPreferences.isEffectivelyEnabled(context))

        MessageHistory.setEnabled(context, false)
        assertFalse(InternetSyncPreferences.isEffectivelyEnabled(context))

        MessageHistory.setEnabled(context, true)
        InternetSyncPreferences.setEnabled(context, false)
    }
}
