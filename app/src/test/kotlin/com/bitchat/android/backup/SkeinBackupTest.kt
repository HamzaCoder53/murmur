package com.bitchat.android.backup

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.wearlink.IdentityBundle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class SkeinBackupTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun syntheticIdentity(): IdentityBundle.Contents = IdentityBundle.Contents(
        nickname = "alice",
        identityStrings = mapOf(
            "static_private_key" to "cHJpdmF0ZQ==\n",
            "static_public_key" to "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=\n",
            "nostr_private_key" to "ab".repeat(32)
        ),
        identitySets = mapOf("verified_fingerprints" to setOf("fp1")),
        cryptoStrings = mapOf("ed25519_signing_private_key" to "c2lnbg==\n")
    )

    private fun sampleMessage(
        id: String,
        content: String,
        type: BitchatMessageType = BitchatMessageType.Message,
        status: DeliveryStatus? = DeliveryStatus.Read("bob", Date(1_700_000_000_000L))
    ) = BitchatMessage(
        id = id,
        sender = "alice",
        content = content,
        type = type,
        timestamp = Date(1_700_000_000_000L),
        isPrivate = true,
        recipientNickname = "bob",
        senderPeerID = "peer-alice",
        deliveryStatus = status
    )

    private class FakeBackupTarget(
        private val root: File,
        initialIDs: Set<String> = emptySet()
    ) : BackupTarget {
        val existing = initialIDs.toMutableSet()
        val inserted = mutableListOf<Triple<String, BitchatMessage, Boolean>>()
        var erased = false
        var restoredIdentity: IdentityBundle.Contents? = null
        var reloaded = false

        override suspend fun existingMessageIDs(): Set<String> = existing.toSet()

        override suspend fun insert(
            conversation: BackupConversation,
            message: BitchatMessage,
            isRead: Boolean
        ): Boolean {
            inserted += Triple(conversation.id, message, isRead)
            existing += message.id
            return true
        }

        override suspend fun eraseChats(): Boolean {
            erased = true
            existing.clear()
            inserted.clear()
            return true
        }

        override suspend fun replaceIdentity(identity: IdentityBundle.Contents): Boolean {
            restoredIdentity = identity
            return true
        }

        override fun mediaDirectory(type: BitchatMessageType): File = File(root, "app-media/${type.name.lowercase()}")

        override suspend fun reloadChats() {
            reloaded = true
        }
    }

    @Test
    fun `archive round trip preserves chats media and identity`() {
        val mediaSrc = tempDir.newFile("photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val identity = syntheticIdentity()
        val identityBytes = IdentityBundle.encode(identity)
        val msgText = sampleMessage("m1", "hello synthetic")
        val msgPhoto = sampleMessage("m2", mediaSrc.absolutePath, type = BitchatMessageType.Image)
        val conv = BackupConversation("conv-1", "Bob", listOf(msgText, msgPhoto), setOf("m1"))
        val content = BackupContent(listOf(conv), identityBytes, SkeinArchive.Kind.EXPORT) { msg ->
            if (msg.id == "m2") mediaSrc else null
        }

        val encrypted = ByteArrayOutputStream()
        SkeinCrypto.encrypt(encrypted, SkeinCrypto.KeySource.Passphrase("correct-horse-battery".toCharArray())).use {
            SkeinArchive.write(it, content, now = 1_700_000_123_000L)
        }

        val staging = tempDir.newFolder("staging")
        val imported = SkeinArchive.read(
            ByteArrayInputStream(encrypted.toByteArray()),
            SkeinCrypto.KeySource.Passphrase("correct-horse-battery".toCharArray()),
            staging
        )

        assertEquals(1_700_000_123_000L, imported.manifest.createdAt)
        assertTrue(imported.manifest.includesIdentity)
        assertEquals(1, imported.manifest.conversationCount)
        assertEquals(2, imported.manifest.messageCount)
        assertEquals(1, imported.manifest.mediaCount)
        assertEquals(1, imported.conversations.size)
        assertEquals(listOf("m1", "m2"), imported.conversations[0].messages.map { it.id })
        assertEquals(setOf("m1"), imported.conversations[0].readMessageIDs)
        assertArrayEquals(identityBytes, imported.identity)
        val stagedPhoto = imported.stagedMedia["m2"]
        assertNotNull(stagedPhoto)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), stagedPhoto!!.readBytes())
    }

    @Test
    fun `wrong passphrase fails cleanly`() {
        val conv = BackupConversation("conv-1", "Bob", listOf(sampleMessage("m1", "secret")), emptySet())
        val content = BackupContent(listOf(conv), null, SkeinArchive.Kind.EXPORT) { null }
        val encrypted = ByteArrayOutputStream()
        SkeinCrypto.encrypt(encrypted, SkeinCrypto.KeySource.Passphrase("right-passphrase".toCharArray())).use {
            SkeinArchive.write(it, content)
        }

        val staging = tempDir.newFolder("staging-wrong")
        try {
            SkeinArchive.read(
                ByteArrayInputStream(encrypted.toByteArray()),
                SkeinCrypto.KeySource.Passphrase("wrong-passphrase".toCharArray()),
                staging
            )
            fail("Expected SkeinFormatException")
        } catch (e: SkeinFormatException) {
            assertTrue(e.wrongKey)
        }
        assertFalse(staging.exists())
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val conv = BackupConversation("conv-1", "Bob", listOf(sampleMessage("m1", "secret")), emptySet())
        val content = BackupContent(listOf(conv), null, SkeinArchive.Kind.EXPORT) { null }
        val encrypted = ByteArrayOutputStream()
        SkeinCrypto.encrypt(encrypted, SkeinCrypto.KeySource.RawKey(ByteArray(32) { 7 })).use {
            SkeinArchive.write(it, content)
        }
        val bytes = encrypted.toByteArray()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 0xFF).toByte()

        val staging = tempDir.newFolder("staging-tampered")
        try {
            SkeinArchive.read(
                ByteArrayInputStream(bytes),
                SkeinCrypto.KeySource.RawKey(ByteArray(32) { 7 }),
                staging
            )
            fail("Expected SkeinFormatException")
        } catch (_: SkeinFormatException) {
            // expected
        }
        assertFalse(staging.exists())
    }

    @Test
    fun `path traversal entry in zip is rejected`() {
        val rawKey = SkeinCrypto.KeySource.RawKey(ByteArray(32) { 9 })
        val encrypted = ByteArrayOutputStream()
        SkeinCrypto.encrypt(encrypted, rawKey).use { out ->
            ZipOutputStream(out).use { zip ->
                val manifest = SkeinArchive.encodeManifest(
                    BackupManifest(SkeinArchive.FORMAT_VERSION, 1000L, SkeinArchive.Kind.EXPORT, false, 1, 0, 0)
                )
                zip.putNextEntry(ZipEntry(SkeinArchive.MANIFEST))
                zip.write(manifest)
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("../evil.txt"))
                zip.write("pwned".toByteArray())
                zip.closeEntry()
            }
        }

        val staging = tempDir.newFolder("staging-traversal")
        try {
            SkeinArchive.read(ByteArrayInputStream(encrypted.toByteArray()), rawKey, staging)
            fail("Expected SkeinFormatException for path traversal")
        } catch (_: SkeinFormatException) {
            // expected
        }
        assertFalse(File(tempDir.root, "evil.txt").exists())
    }

    @Test
    fun `merge skips duplicate message ids and ignores identity`() = runTest {
        val mediaSrc = tempDir.newFile("note.m4a").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val identityBytes = IdentityBundle.encode(syntheticIdentity())
        val m1 = sampleMessage("m1", "already-here")
        val m2 = sampleMessage("m2", mediaSrc.absolutePath, type = BitchatMessageType.Audio)
        val conv = BackupConversation("conv-1", "Bob", listOf(m1, m2), setOf("m2"))
        val content = BackupContent(listOf(conv), identityBytes, SkeinArchive.Kind.EXPORT) {
            if (it.id == "m2") mediaSrc else null
        }

        val key = SkeinCrypto.KeySource.RawKey(ByteArray(32) { 3 })
        val encrypted = ByteArrayOutputStream()
        SkeinCrypto.encrypt(encrypted, key).use { SkeinArchive.write(it, content) }

        val staging = tempDir.newFolder("staging-merge")
        val archive = SkeinArchive.read(ByteArrayInputStream(encrypted.toByteArray()), key, staging)
        val target = FakeBackupTarget(tempDir.root, initialIDs = setOf("m1"))

        val result = BackupImporter.apply(archive, ImportMode.MERGE, target, staging)

        assertEquals(1, result.added)
        assertEquals(1, result.skipped)
        assertEquals(0, result.failed)
        assertFalse(result.identityRestored)
        assertFalse(target.erased)
        assertNull(target.restoredIdentity)
        assertTrue(target.reloaded)
        assertEquals(1, target.inserted.size)
        val (_, insertedMsg, isRead) = target.inserted.single()
        assertEquals("m2", insertedMsg.id)
        assertTrue(isRead)
        assertTrue(insertedMsg.content.startsWith(target.mediaDirectory(BitchatMessageType.Audio).absolutePath))
        assertTrue(File(insertedMsg.content).isFile)
    }

    @Test
    fun `replace erases chats and restores identity`() = runTest {
        val identity = syntheticIdentity()
        val identityBytes = IdentityBundle.encode(identity)
        val m1 = sampleMessage("m1", "restored")
        val conv = BackupConversation("conv-1", "Bob", listOf(m1), emptySet())
        val content = BackupContent(listOf(conv), identityBytes, SkeinArchive.Kind.TRANSFER) { null }

        val key = SkeinCrypto.KeySource.RawKey(ByteArray(32) { 5 })
        val encrypted = ByteArrayOutputStream()
        SkeinCrypto.encrypt(encrypted, key).use { SkeinArchive.write(it, content) }

        val staging = tempDir.newFolder("staging-replace")
        val archive = SkeinArchive.read(ByteArrayInputStream(encrypted.toByteArray()), key, staging)
        val target = FakeBackupTarget(tempDir.root, initialIDs = setOf("old-msg"))

        val result = BackupImporter.apply(archive, ImportMode.REPLACE, target, staging)

        assertEquals(1, result.added)
        assertEquals(0, result.skipped)
        assertTrue(result.identityRestored)
        assertTrue(target.erased)
        assertEquals(identity, target.restoredIdentity)
        assertFalse(target.reloaded)
    }

    @Test
    fun `transfer invitation round trips and rejects malformed`() {
        val inv = TransferProtocol.newInvitation(listOf("192.168.1.10:45678"))
        val encoded = inv.encode()
        assertEquals(inv, TransferProtocol.parseInvitation(encoded))
        assertNull(TransferProtocol.parseInvitation("https://example.com"))
        assertNull(TransferProtocol.parseInvitation("skein-move:1?a=not-an-ip:99999&c=ABC234&k=bad"))
    }

    @Test
    fun `transfer handshake and stream succeeds over piped streams`() {
        val conv = BackupConversation("conv-1", "Bob", listOf(sampleMessage("m1", "moved")), emptySet())
        val identity = syntheticIdentity()
        val content = BackupContent(listOf(conv), IdentityBundle.encode(identity), SkeinArchive.Kind.TRANSFER) { null }
        val invitation = TransferProtocol.newInvitation(listOf("192.168.1.10:40000"))
        val archiveFile = tempDir.newFile("outgoing.skein")
        SkeinCrypto.encrypt(archiveFile.outputStream(), SkeinCrypto.KeySource.RawKey(TransferProtocol.archiveKey(invitation.key))).use {
            SkeinArchive.write(it, content)
        }
        val receivedFile = tempDir.newFile("incoming.skein")
        val staging = tempDir.newFolder("staging-transfer")

        val oldToNewIn = PipedInputStream(64 * 1024)
        val oldToNewOut = PipedOutputStream(oldToNewIn)
        val newToOldIn = PipedInputStream(64 * 1024)
        val newToOldOut = PipedOutputStream(newToOldIn)

        val exec = Executors.newSingleThreadExecutor()
        try {
            val serverFuture = exec.submit<Boolean> {
                TransferProtocol.serve(newToOldIn, oldToNewOut, invitation, archiveFile)
            }
            val imported = TransferProtocol.receive(oldToNewIn, newToOldOut, invitation, receivedFile) { f ->
                f.inputStream().use {
                    SkeinArchive.read(it, SkeinCrypto.KeySource.RawKey(TransferProtocol.archiveKey(invitation.key)), staging)
                }
            }
            assertTrue(serverFuture.get(5, TimeUnit.SECONDS))
            assertEquals("m1", imported.conversations.single().messages.single().id)
            assertEquals(identity, IdentityBundle.decode(imported.identity!!))
        } finally {
            exec.shutdownNow()
        }
    }

    @Test
    fun `transfer handshake rejects wrong key`() {
        val invitation = TransferProtocol.newInvitation(listOf("192.168.1.10:40000"))
        val wrongKeyInvitation = invitation.copy(key = ByteArray(32) { 42 })
        val archiveFile = tempDir.newFile("outgoing-wrong.skein").apply { writeBytes(ByteArray(64)) }
        val receivedFile = tempDir.newFile("incoming-wrong.skein")

        val oldToNewIn = PipedInputStream(64 * 1024)
        val oldToNewOut = PipedOutputStream(oldToNewIn)
        val newToOldIn = PipedInputStream(64 * 1024)
        val newToOldOut = PipedOutputStream(newToOldIn)

        val exec = Executors.newSingleThreadExecutor()
        try {
            val serverFuture = exec.submit<Throwable?> {
                try {
                    TransferProtocol.serve(newToOldIn, oldToNewOut, invitation, archiveFile)
                    null
                } catch (t: Throwable) {
                    t
                }
            }
            try {
                TransferProtocol.receive(oldToNewIn, newToOldOut, wrongKeyInvitation, receivedFile) {
                    fail("Should not verify")
                }
                fail("Expected TransferAuthException")
            } catch (_: TransferAuthException) {
                // expected
            } finally {
                newToOldOut.close()
                oldToNewIn.close()
            }
            assertNotNull(serverFuture.get(5, TimeUnit.SECONDS))
        } finally {
            exec.shutdownNow()
        }
    }
}
