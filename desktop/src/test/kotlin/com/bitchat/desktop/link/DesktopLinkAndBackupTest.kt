package com.bitchat.desktop.link

import com.bitchat.android.backup.BackupConversation
import com.bitchat.android.backup.BackupTarget
import com.bitchat.android.backup.ImportMode
import com.bitchat.android.backup.SkeinFormatException
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.linked.InMemoryLinkStore
import com.bitchat.android.linked.LinkCommand
import com.bitchat.android.linked.LinkCoordinator
import com.bitchat.android.linked.LinkEntry
import com.bitchat.android.linked.LinkFavorite
import com.bitchat.android.linked.LinkPeer
import com.bitchat.android.linked.LinkProtocol
import com.bitchat.android.linked.LinkQr
import com.bitchat.android.linked.LinkedAppBridge
import com.bitchat.android.linked.SocketFrameIo
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.wearlink.IdentityBundle
import com.bitchat.desktop.TestDevices
import com.bitchat.desktop.core.DesktopIdentity
import com.bitchat.desktop.core.DesktopMeshNode
import com.bitchat.desktop.core.LoopbackHub
import com.bitchat.desktop.platform.DiscoveredService
import com.bitchat.desktop.platform.LocalDiscovery
import com.bitchat.desktop.platform.NoopLocalDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

class DesktopLinkAndBackupTest {
    private val devices = TestDevices()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val idA = "00000000000000000000000000000001"
    private val idB = "00000000000000000000000000000002"

    @Before
    fun setUp() = AppStateStore.clearAll()

    @After
    fun tearDown() {
        scope.cancel()
        AppStateStore.clearAll()
        devices.close()
    }

    private fun message(id: String, content: String, private: Boolean = true) = BitchatMessage(
        id = id,
        sender = "synthetic-alice",
        content = content,
        timestamp = Date(1_700_000_000_000L),
        isPrivate = private,
        senderPeerID = "0123456789abcdef",
    )

    private fun desktop(name: String): Triple<DesktopIdentity, DesktopMeshNode, DesktopLinkedAppBridge> {
        val identity = DesktopIdentity.loadOrCreate(devices.context(name))
        val (node, _) = DesktopMeshNode.overLoopback(identity, LoopbackHub())
        node.messageSink = { msg, conv ->
            if (conv == null) AppStateStore.addPublicMessage(msg) else AppStateStore.addPrivateMessage(conv, msg)
        }
        return Triple(identity, node, DesktopLinkedAppBridge(identity, node))
    }

    // --- Frame transport (shared SocketFrameIo) ---

    private fun <T> loopbackPair(block: suspend (client: Socket, server: Socket) -> T): T =
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { c ->
                server.accept().use { s -> runBlocking { block(c, s) } }
            }
        }

    @Test
    fun `SocketFrameIo exchanges length-prefixed frames over loopback`() = loopbackPair { c, s ->
        val a = SocketFrameIo(c)
        val b = SocketFrameIo(s)
        val payload = "synthetic-link-frame".toByteArray()
        assertTrue(a.send(payload))
        assertArrayEquals(payload, b.receive())
    }

    @Test
    fun `SocketFrameIo refuses empty and oversize frames on send`() = loopbackPair { c, _ ->
        val a = SocketFrameIo(c)
        assertEquals(512 * 1024, SocketFrameIo.MAX_FRAME_BYTES)
        assertFalse(a.send(ByteArray(0)))
        assertFalse(a.send(ByteArray(SocketFrameIo.MAX_FRAME_BYTES + 1)))
        assertTrue(a.send(ByteArray(SocketFrameIo.MAX_FRAME_BYTES)))
    }

    @Test
    fun `SocketFrameIo closes on an oversize or zero length prefix without reading the body`() {
        for (badLength in listOf(Int.MAX_VALUE, SocketFrameIo.MAX_FRAME_BYTES + 1, 0, -1)) {
            loopbackPair { c, s ->
                DataOutputStream(c.getOutputStream()).apply { writeInt(badLength); flush() }
                val io = SocketFrameIo(s)
                assertNull(io.receive())
                // Closed for good: nothing more is read or sent.
                assertNull(io.receive())
                assertFalse(io.send(byteArrayOf(1)))
            }
        }
    }

    // --- Backups ---

    private fun recordingTarget(imported: MutableList<BitchatMessage>) = object : BackupTarget {
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

    @Test
    fun `DesktopBackupService exports and imports encrypted archive`() = runBlocking {
        val archive = File(devices.root, "backup.skein")
        val staging = File(devices.root, "staging")
        DesktopBackupService.exportArchive(
            outFile = archive,
            passphrase = "synthetic-passphrase",
            conversations = listOf(BackupConversation("peer", "Peer", listOf(message("msg-1", "synthetic backup hello")), emptySet())),
        )
        assertTrue(archive.isFile)

        val imported = mutableListOf<BitchatMessage>()
        val res = DesktopBackupService.importArchive(archive, "synthetic-passphrase", ImportMode.MERGE, recordingTarget(imported), staging)
        assertEquals(1, res.added)
        assertEquals("synthetic backup hello", imported.single().content)
    }

    @Test
    fun `wrong passphrase fails closed before anything is imported`() = runBlocking {
        val archive = File(devices.root, "backup.skein")
        DesktopBackupService.exportArchive(
            archive,
            "synthetic-passphrase",
            listOf(BackupConversation("peer", null, listOf(message("msg-1", "synthetic")), emptySet())),
        )
        val imported = mutableListOf<BitchatMessage>()
        try {
            DesktopBackupService.importArchive(archive, "not-the-passphrase", ImportMode.REPLACE, recordingTarget(imported), File(devices.root, "staging"))
            fail("expected SkeinFormatException")
        } catch (e: SkeinFormatException) {
            assertTrue(e.wrongKey)
        }
        assertTrue(imported.isEmpty())
    }

    @Test
    fun `short passphrases are refused and leave no file`() {
        val archive = File(devices.root, "backup.skein")
        try {
            DesktopBackupService.exportArchive(archive, "short", emptyList())
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(archive.exists())
    }

    @Test
    fun `identity export restores the identity on replace, chats-only export does not`() = runBlocking {
        val (identity, _, _) = desktop("a")
        val ctx = identity.context
        AppStateStore.addPrivateMessage("peer", message("msg-1", "synthetic restored"))

        val withId = File(devices.root, "with-id.skein")
        val chatsOnly = File(devices.root, "chats.skein")
        DesktopBackupService.exportCurrent(ctx, identity.nickname, withId, "synthetic-passphrase", DesktopBackupService.Scope.CHATS_AND_IDENTITY)
        DesktopBackupService.exportCurrent(ctx, identity.nickname, chatsOnly, "synthetic-passphrase", DesktopBackupService.Scope.CHATS_ONLY)

        val target = DesktopBackupService.AppStateTarget(devices.context("b"))
        val r1 = DesktopBackupService.importArchive(chatsOnly, "synthetic-passphrase", ImportMode.REPLACE, target, File(devices.root, "s1"))
        assertFalse(r1.identityRestored)
        assertEquals("synthetic restored", AppStateStore.privateMessages.value["peer"]?.single()?.content)

        val r2 = DesktopBackupService.importArchive(withId, "synthetic-passphrase", ImportMode.REPLACE, target, File(devices.root, "s2"))
        assertTrue(r2.identityRestored)
        assertEquals(
            IdentityBundle.read(ctx, "n")?.peerID,
            IdentityBundle.read(devices.context("b"), "n")?.peerID,
        )
    }

    // --- Bridge ---

    @Test
    fun `bridge stores favorites from linked devices and ignores malformed keys`() {
        val (_, _, bridge) = desktop("a")
        FavoritesPersistenceService.shared.clearAllFavorites()
        val key = "ab".repeat(32)
        bridge.applyFavorites(
            listOf(
                LinkFavorite(key, "synthetic-bob", isFavorite = true, updatedAt = 1L),
                LinkFavorite("zz".repeat(32), "bad", isFavorite = true, updatedAt = 1L),
                LinkFavorite("abcd", "short", isFavorite = true, updatedAt = 1L),
            ),
        )
        val favs = bridge.favorites()
        assertEquals(listOf(key), favs.map { it.noiseKeyHex })
        assertTrue(favs.single().isFavorite)
    }

    @Test
    fun `bridge media storage is collision-free and size-limited`() = runBlocking {
        val (_, _, bridge) = desktop("a")
        bridge.onMediaReceived("a", "x.bin", byteArrayOf(1))
        bridge.onMediaReceived("a_b", "y.bin", byteArrayOf(2))
        assertEquals("x.bin", bridge.mediaFile("a")?.first)
        assertArrayEquals(byteArrayOf(2), bridge.mediaFile("a_b")?.second)
        assertNull(bridge.mediaFile("a/b"))

        bridge.onMediaReceived("big", "big.bin", ByteArray((LinkProtocol.MAX_MEDIA_BYTES + 1).toInt()))
        assertNull(bridge.mediaFile("big"))
    }

    @Test
    fun `bridge keeps the follower's message id when executing a send`() = runBlocking {
        val (_, _, bridge) = desktop("a")
        bridge.execute(LinkCommand.SendPublic("cmd-1", "synthetic relay", emptyList(), null), null)
        bridge.execute(LinkCommand.SendPublic("cmd-1", "synthetic relay", emptyList(), null), null)
        assertEquals(listOf("cmd-1"), AppStateStore.publicMessages.value.map { it.id })
        // A file command without its bytes is refused, not half-applied.
        bridge.execute(LinkCommand.SendFile("f-1", null, "x.bin", "application/octet-stream", "file"), null)
        assertNull(bridge.mediaFile("f-1"))
    }

    @Test
    fun `eraseIdentity removes keys, chats, favorites and media`() = runBlocking {
        val (identity, _, bridge) = desktop("a")
        val ctx = identity.context
        assertNotNull(IdentityBundle.read(ctx, identity.nickname))
        AppStateStore.addPrivateMessage("peer", message("msg-1", "synthetic"))
        bridge.applyFavorites(listOf(LinkFavorite("cd".repeat(32), "synthetic-carol", true, 1L)))
        bridge.onMediaReceived("m", "x.bin", byteArrayOf(1))

        bridge.eraseIdentity()

        assertNull(IdentityBundle.read(ctx, identity.nickname))
        assertTrue(AppStateStore.privateMessages.value.isEmpty())
        assertTrue(bridge.favorites().isEmpty())
        assertNull(bridge.mediaFile("m"))
    }

    @Test
    fun `device name never discloses the host name`() {
        val (_, _, bridge) = desktop("a")
        assertTrue(bridge.deviceName().startsWith("Skein on "))
    }

    // --- Pairing between desktop and the shared coordinator over loopback TCP ---

    private class NewDeviceBridge : LinkedAppBridge {
        override val changes = MutableSharedFlow<Unit>()
        @Volatile var adopted: ByteArray? = null
        val entries = CopyOnWriteArrayList<LinkEntry>()
        override fun nickname() = "synthetic-new"
        override fun setNickname(name: String) = Unit
        override suspend fun entriesSince(since: Long) = emptyList<LinkEntry>()
        override fun currentEntries() = emptyList<LinkEntry>()
        override suspend fun applyEntries(entries: List<LinkEntry>) = emptyList<String>()
        override fun favorites() = emptyList<LinkFavorite>()
        override fun applyFavorites(favorites: List<LinkFavorite>) = Unit
        override fun peers() = emptyList<LinkPeer>()
        override fun applyMirrorPeers(peers: List<LinkPeer>?) = Unit
        override fun setRadiosSuppressed(suppressed: Boolean) = Unit
        override suspend fun execute(command: LinkCommand, media: ByteArray?) = Unit
        override fun mediaFile(messageID: String): Pair<String, ByteArray>? = null
        override suspend fun onMediaReceived(messageID: String, fileName: String, data: ByteArray) = Unit
        override fun identityBundle(): ByteArray? = null
        override suspend fun adoptPairing(
            identity: ByteArray,
            entries: List<LinkEntry>,
            favorites: List<LinkFavorite>,
            media: Map<String, Pair<String, ByteArray>>,
        ): Boolean {
            this.entries += entries
            adopted = identity
            return true
        }
        override suspend fun eraseIdentity() = Unit
        override fun restartProcess() = Unit
        override fun deviceName() = "Synthetic Phone"
    }

    /** Every advert is visible to every browser, at 127.0.0.1. */
    private class InMemoryDiscovery : LocalDiscovery {
        private val adverts = CopyOnWriteArrayList<DiscoveredService>()
        private val browsers = CopyOnWriteArrayList<(DiscoveredService) -> Unit>()
        override fun advertise(serviceType: String, name: String, port: Int): AutoCloseable {
            val s = DiscoveredService(name, "127.0.0.1", port)
            adverts += s
            browsers.forEach { it(s) }
            return AutoCloseable { adverts -= s }
        }
        override fun browse(serviceType: String, onFound: (DiscoveredService) -> Unit, onLost: (String) -> Unit): AutoCloseable {
            browsers += onFound
            adverts.forEach(onFound)
            return AutoCloseable { browsers -= onFound }
        }
    }

    private suspend fun approveWhenAsked(existing: DesktopLinkController) {
        withTimeout(20_000) {
            while (existing.pairing.value !is LinkCoordinator.Pairing.AwaitingApproval) delay(20)
        }
        existing.respondToPairing(true)
    }

    private suspend fun awaitAdopted(newBridge: NewDeviceBridge): ByteArray = withTimeout(20_000) {
        while (newBridge.adopted == null) delay(20)
        newBridge.adopted!!
    }

    @Test
    fun `a new device pairs with desktop by QR and receives its identity and chats`() = runBlocking {
        val (identity, _, bridge) = desktop("a")
        AppStateStore.addPrivateMessage("peer", message("msg-1", "synthetic handed over"))
        val existing = DesktopLinkController(scope, InMemoryLinkStore(idA), bridge, NoopLocalDiscovery)
        val newBridge = NewDeviceBridge()
        val joining = DesktopLinkController(scope, InMemoryLinkStore(idB), newBridge, NoopLocalDiscovery)
        try {
            val offered = LinkQr.parse(existing.startPairingQr()!!)!!
            // Loopback address: the test must not depend on this machine's LAN.
            val qr = LinkQr.encode(offered.deviceId, offered.secret, "127.0.0.1", existing.listeningPort)
            assertTrue(joining.joinWithQr(qr))
            approveWhenAsked(existing)

            val adopted = IdentityBundle.decode(awaitAdopted(newBridge))!!
            assertEquals(identity.peerID, adopted.peerID)
            assertTrue(newBridge.entries.any { it.message.content == "synthetic handed over" })
            withTimeout(5_000) { while (existing.devices.value.none { it.deviceId == idB }) delay(20) }
        } finally {
            existing.stop()
            joining.stop()
        }
    }

    @Test
    fun `a new device pairs with desktop by six-digit code found through discovery`() = runBlocking {
        val (identity, _, bridge) = desktop("a")
        val discovery = InMemoryDiscovery()
        val existing = DesktopLinkController(scope, InMemoryLinkStore(idA), bridge, discovery)
        val newBridge = NewDeviceBridge()
        val joining = DesktopLinkController(scope, InMemoryLinkStore(idB), newBridge, discovery)
        try {
            assertNotNull(existing.startPairingQr())
            val code = existing.offeredCode()!!
            assertTrue(joining.joinWithCode(code))
            approveWhenAsked(existing)
            assertEquals(identity.peerID, IdentityBundle.decode(awaitAdopted(newBridge))!!.peerID)
        } finally {
            existing.stop()
            joining.stop()
        }
    }

    @Test
    fun `a wrong six-digit code fails without pairing`() = runBlocking {
        val (_, _, bridge) = desktop("a")
        val discovery = InMemoryDiscovery()
        val existing = DesktopLinkController(scope, InMemoryLinkStore(idA), bridge, discovery)
        val newBridge = NewDeviceBridge()
        val joining = DesktopLinkController(scope, InMemoryLinkStore(idB), newBridge, discovery)
        try {
            existing.startPairingQr()
            val right = existing.offeredCode()!!
            val wrong = ((right.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
            assertTrue(joining.joinWithCode(wrong))
            withTimeout(20_000) { while (joining.pairing.value !is LinkCoordinator.Pairing.Failed) delay(20) }
            assertNull(newBridge.adopted)
            assertTrue(existing.devices.value.isEmpty())
        } finally {
            existing.stop()
            joining.stop()
        }
    }
}
