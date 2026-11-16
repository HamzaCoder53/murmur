package com.bitchat.android.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.wearlink.IdentityBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class AndroidBackupTest {

    private fun syntheticIdentity(): ByteArray = IdentityBundle.encode(
        IdentityBundle.Contents(
            nickname = "alice",
            identityStrings = mapOf(
                "static_private_key" to "01".repeat(32),
                "static_public_key" to "02".repeat(32)
            ),
            identitySets = mapOf(
                "verified_fingerprints" to setOf("a".repeat(64))
            ),
            cryptoStrings = mapOf(
                "ed25519_signing_private_key" to "03".repeat(32)
            )
        )
    )

    private fun syntheticConversations(): List<BackupConversation> = listOf(
        BackupConversation(
            id = "peer-1",
            displayName = "Bob",
            messages = listOf(
                BitchatMessage(
                    id = "msg-1",
                    sender = "Bob",
                    content = "hello from backup",
                    timestamp = Date(1_700_000_000_000L),
                    isRelay = false,
                    isPrivate = true,
                    senderPeerID = "0102030405060708",
                    type = BitchatMessageType.Message
                )
            ),
            readMessageIDs = setOf("msg-1")
        )
    )

    @Test
    fun `android backup archive round trips identity and chats`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val staging = File(context.cacheDir, "ab-roundtrip").apply { deleteRecursively() }
        val out = ByteArrayOutputStream()
        AndroidBackupArchive.write(
            out,
            BackupContent(syntheticConversations(), syntheticIdentity(), SkeinArchive.Kind.EXPORT) { null }
        )

        val imported = AndroidBackupArchive.read(ByteArrayInputStream(out.toByteArray()), staging)
        assertNotNull(imported.identity)
        assertEquals(1, imported.conversations.size)
        assertEquals("msg-1", imported.conversations.first().messages.first().id)
        assertTrue("msg-1" in imported.conversations.first().readMessageIDs)
        staging.deleteRecursively()
    }

    @Test(expected = SkeinFormatException::class)
    fun `android backup archive rejects tampered or wrong magic bytes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val staging = File(context.cacheDir, "ab-bad").apply { deleteRecursively() }
        try {
            AndroidBackupArchive.read(ByteArrayInputStream("BADMAGIC".toByteArray() + ByteArray(64)), staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun `restoreIfPending applies replace and deletes pending file`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pending = AndroidBackupRestorer.pendingRestoreFile(context)
        val staging = File(context.cacheDir, "ab-restore-staging").apply { deleteRecursively() }
        pending.parentFile?.mkdirs()
        pending.outputStream().use { out ->
            AndroidBackupArchive.write(
                out,
                BackupContent(syntheticConversations(), syntheticIdentity(), SkeinArchive.Kind.EXPORT) { null }
            )
        }

        val inserted = mutableListOf<String>()
        var erased = false
        var restoredIdentity: IdentityBundle.Contents? = null
        val target = object : BackupTarget {
            override suspend fun existingMessageIDs() = emptySet<String>()
            override suspend fun insert(conversation: BackupConversation, message: BitchatMessage, isRead: Boolean): Boolean {
                inserted += message.id
                return true
            }
            override suspend fun eraseChats(): Boolean { erased = true; return true }
            override suspend fun replaceIdentity(identity: IdentityBundle.Contents): Boolean {
                restoredIdentity = identity
                return true
            }
            override fun mediaDirectory(type: BitchatMessageType) = staging
            override suspend fun reloadChats() = Unit
        }

        val ok = AndroidBackupRestorer.restoreIfPending(context, target, staging)
        assertTrue(ok)
        assertTrue(erased)
        assertEquals(listOf("msg-1"), inserted)
        assertEquals("alice", restoredIdentity?.nickname)
        assertFalse(pending.exists())
    }

    @Test
    fun `android backup preferences default off and clear on panic`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AndroidBackupPreferences.clearOnPanic(context)
        assertFalse(AndroidBackupPreferences.isEnabled(context))

        AndroidBackupPreferences.setEnabled(context, true)
        assertTrue(AndroidBackupPreferences.isEnabled(context))
        val staged = AndroidBackupRestorer.stagedBackupFile(context).apply {
            parentFile?.mkdirs()
            writeText("temp")
        }
        assertTrue(staged.exists())

        AndroidBackupPreferences.clearOnPanic(context)
        assertFalse(AndroidBackupPreferences.isEnabled(context))
        assertFalse(staged.exists())
    }
}
