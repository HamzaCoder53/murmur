package com.bitchat.desktop.link

import com.bitchat.android.backup.BackupConversation
import com.bitchat.android.backup.BackupTarget
import com.bitchat.android.backup.ImportMode
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.wearlink.IdentityBundle
import com.bitchat.desktop.TestDevices
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date

class DesktopLinkAndBackupTest {
    private val devices = TestDevices()

    @After
    fun tearDown() = devices.close()

    @Test
    fun `TcpLinkTransport exchanges length-prefixed frames over loopback`() = runBlocking {
        TcpLinkTransport.listen(0).use { server ->
            val client = TcpLinkTransport.connect("127.0.0.1", server.localPort)
            val accepted = TcpLinkTransport(server.accept())
            client.use { c ->
                accepted.use { s ->
                    val payload = "synthetic-link-frame".toByteArray()
                    assertTrue(c.send(payload))
                    assertArrayEquals(payload, s.receive())
                }
            }
        }
    }

    @Test
    fun `DesktopBackupService exports and imports encrypted archive`() = runBlocking {
        val archive = File(devices.root, "backup.skein")
        val staging = File(devices.root, "staging").apply { mkdirs() }
        val msg = BitchatMessage(
            id = "msg-1",
            sender = "synthetic-alice",
            content = "synthetic backup hello",
            timestamp = Date(1_700_000_000_000L),
            isPrivate = false,
            senderPeerID = "0123456789abcdef",
        )
        DesktopBackupService.exportArchive(
            outFile = archive,
            passphrase = "synthetic-passphrase",
            conversations = listOf(BackupConversation("public", "Public", listOf(msg), emptySet())),
        )
        assertTrue(archive.isFile)

        val imported = mutableListOf<BitchatMessage>()
        val target = object : BackupTarget {
            override suspend fun existingMessageIDs(): Set<String> = emptySet()
            override suspend fun eraseChats(): Boolean = true
            override suspend fun insert(conversation: BackupConversation, message: BitchatMessage, isRead: Boolean): Boolean {
                imported += message
                return true
            }
            override suspend fun replaceIdentity(identity: IdentityBundle.Contents): Boolean = true
            override fun mediaDirectory(type: BitchatMessageType): File = File(devices.root, "media").apply { mkdirs() }
            override suspend fun reloadChats() = Unit
        }
        val res = DesktopBackupService.importArchive(archive, "synthetic-passphrase", ImportMode.MERGE, target, staging)
        assertEquals(1, res.added)
        assertEquals("synthetic backup hello", imported.single().content)
    }
}
