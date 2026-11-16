package com.bitchat.desktop.storage

import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.linked.LinkEntry
import com.bitchat.android.linked.LinkFavorite
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.desktop.TestDevices
import com.bitchat.desktop.core.DesktopIdentity
import com.bitchat.desktop.core.DesktopMeshNode
import com.bitchat.desktop.core.LoopbackHub
import com.bitchat.desktop.link.DesktopLinkedAppBridge
import com.bitchat.desktop.shim.ShimRuntime
import com.bitchat.desktop.ui.AppLanguage
import com.bitchat.desktop.ui.BroadcastPower
import com.bitchat.desktop.ui.ChatMessage
import com.bitchat.desktop.ui.ConversationId
import com.bitchat.desktop.ui.DesktopUiSettings
import com.bitchat.desktop.ui.InMemoryMessagingPort
import com.bitchat.desktop.ui.PrefsSettingsPort
import com.bitchat.desktop.ui.SkeinPorts
import com.bitchat.desktop.ui.SkeinViewModel
import java.io.File
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restart behaviour of the desktop stores: history, settings, contacts and favorites synced from
 * a linked device. All identities, keys and messages are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPersistenceTest {
    private val devices = TestDevices()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        devices.close()
    }

    private fun scope() = TestScope(UnconfinedTestDispatcher()).also { scopes += it }
    private val keyFor: (String) -> ByteArray = devices.keyStore::getOrCreateKey

    private fun incoming(id: String, conv: ConversationId, text: String) =
        ChatMessage(id, conv, "2222222222222222", "peer", text, Date(1_000), isMine = false)

    @Test
    fun `history written by the view model survives a restart`() {
        val dir = File(devices.root, "hist").apply { mkdirs() }
        val messaging = InMemoryMessagingPort()
        val store1 = EncryptedHistoryStore.open(dir, keyFor)
        SkeinViewModel(SkeinPorts(messaging, history = store1), scope())
        messaging.receive(incoming("m1", ConversationId.Mesh, "synthetic one"))
        messaging.receive(incoming("p1", ConversationId.Private("2222222222222222"), "synthetic two"))
        store1.close()

        val store2 = EncryptedHistoryStore.open(dir, keyFor)
        val vm2 = SkeinViewModel(SkeinPorts(InMemoryMessagingPort(), history = store2), scope())
        assertEquals(listOf("m1"), vm2.state.value.messages.map { it.message.id })
        assertEquals(1, vm2.state.value.privateChats.size)
        store2.close()
    }

    @Test
    fun `turning history off erases it from disk`() {
        val dir = File(devices.root, "hist-off").apply { mkdirs() }
        val messaging = InMemoryMessagingPort()
        val store = EncryptedHistoryStore.open(dir, keyFor)
        val vm = SkeinViewModel(SkeinPorts(messaging, history = store), scope())
        messaging.receive(incoming("m1", ConversationId.Mesh, "synthetic"))
        store.flush()
        assertTrue(File(dir, EncryptedHistoryStore.FILE_NAME).exists())

        vm.setSaveHistory(false)
        store.flush()
        assertFalse(File(dir, EncryptedHistoryStore.FILE_NAME).exists())
        messaging.receive(incoming("m2", ConversationId.Mesh, "synthetic later"))
        store.close()
        assertTrue(EncryptedHistoryStore.open(dir, keyFor).load().isEmpty())
    }

    @Test
    fun `settings round-trip through the preference shim across a restart`() {
        val ctx = devices.context("settings")
        val saved = DesktopUiSettings(
            language = AppLanguage.entries.last(),
            broadcastPower = BroadcastPower.entries.first(),
            showInDock = false,
            saveHistory = false,
        )
        PrefsSettingsPort(ctx).save(saved)
        ShimRuntime.resetCachesForTests()
        val loaded = PrefsSettingsPort(devices.context("settings")).load()
        assertEquals(saved.language, loaded.language)
        assertEquals(saved.broadcastPower, loaded.broadcastPower)
        assertEquals(saved.showInDock, loaded.showInDock)
        assertEquals(saved.saveHistory, loaded.saveHistory)
    }

    @Test
    fun `contacts store keeps verified fingerprints and favorites across a restart`() {
        val ctx = devices.context("contacts")
        val dir = File(devices.root, "contacts-data").apply { mkdirs() }
        val knownKey = ByteArray(32) { (it + 31).toByte() }
        val knownPeer = ContactIdentityResolver.peerIdForNoiseKey(knownKey)
        val unknownPeer = "3333333333333333"
        val keys = mutableMapOf(knownPeer to knownKey)

        val first = DesktopContactsStore.open(ctx, dir, keyFor) { keys[it] }
        first.setVerified("AB".repeat(32), true)
        first.setFavorite(knownPeer, "synthetic-known", true)
        first.setFavorite(unknownPeer, "synthetic-unknown", true)
        assertTrue(knownPeer in first.favorites.value)
        assertTrue(unknownPeer in first.favorites.value)
        assertTrue(FavoritesPersistenceService.shared.getFavoriteStatus(knownKey)!!.isFavorite)
        first.close()

        val second = DesktopContactsStore.open(ctx, dir, keyFor) { keys[it] }
        assertEquals(setOf("ab".repeat(32)), second.verifiedFingerprints.value)
        assertTrue(knownPeer in second.favorites.value)
        assertTrue(unknownPeer in second.favorites.value)

        // Once the unknown peer's Noise key is learned, the favorite moves into the shared service.
        val learned = ByteArray(32) { (it + 77).toByte() }
        keys[unknownPeer] = learned
        second.promotePending(unknownPeer, "synthetic-unknown")
        assertTrue(FavoritesPersistenceService.shared.getFavoriteStatus(learned)!!.isFavorite)

        second.setFavorite(knownPeer, "synthetic-known", false)
        assertFalse(knownPeer in second.favorites.value)
        second.close()
    }

    @Test
    fun `favorites from a linked device are written to the shared favorites service`() {
        val identity = DesktopIdentity.loadOrCreate(devices.context("bridge")).apply { nickname = "desk" }
        val (node, transport) = DesktopMeshNode.overLoopback(identity, LoopbackHub())
        try {
            val bridge = DesktopLinkedAppBridge(identity, node)
            val favKey = ByteArray(32) { (it + 151).toByte() }
            val favHex = favKey.joinToString("") { "%02x".format(it) }
            bridge.applyFavorites(
                listOf(
                    LinkFavorite(favHex, "synthetic-friend", isFavorite = true, updatedAt = System.currentTimeMillis()),
                    LinkFavorite("not-a-key", "synthetic-bad", isFavorite = true, updatedAt = 0),
                ),
            )
            val rel = FavoritesPersistenceService.shared.getFavoriteStatus(favKey)!!
            assertTrue(rel.isFavorite)
            assertEquals("synthetic-friend", rel.peerNickname)
            assertTrue(bridge.favorites().any { it.noiseKeyHex == favHex && it.isFavorite })
            assertTrue(node.isFavoritePeer(ContactIdentityResolver.peerIdForNoiseKey(favKey)))

            // A stale entry from the link does not undo a newer local change.
            bridge.applyFavorites(listOf(LinkFavorite(favHex, "synthetic-friend", isFavorite = false, updatedAt = 1)))
            assertTrue(FavoritesPersistenceService.shared.getFavoriteStatus(favKey)!!.isFavorite)
        } finally {
            node.stop()
            transport.close()
        }
    }

    @Test
    fun `linked devices and the window read the same node history`() = runBlocking {
        val identity = DesktopIdentity.loadOrCreate(devices.context("shared")).apply { nickname = "desk" }
        val (node, transport) = DesktopMeshNode.overLoopback(identity, LoopbackHub())
        try {
            val bridge = DesktopLinkedAppBridge(identity, node)
            val pub = BitchatMessage(id = "s1", sender = "phone", content = "synthetic from phone", timestamp = Date(5_000), senderPeerID = "4444444444444444")
            val dm = BitchatMessage(id = "s2", sender = "phone", content = "synthetic dm", timestamp = Date(6_000), isPrivate = true, senderPeerID = "4444444444444444")
            bridge.applyEntries(
                listOf(
                    LinkEntry(message = pub, conversation = null, read = true),
                    LinkEntry(message = dm, conversation = "4444444444444444", read = true),
                ),
            )
            assertTrue(node.publicMessages.value.any { it.id == "s1" })
            assertTrue(node.privateChats.value["4444444444444444"]!!.any { it.id == "s2" })
            val entries = bridge.currentEntries()
            assertTrue(entries.any { it.message.id == "s2" && it.conversation == "4444444444444444" && it.read })

            node.sendPublic("synthetic from desk", messageID = "s3")
            assertTrue(bridge.currentEntries().any { it.message.id == "s3" })

            bridge.setNickname("desk2")
            assertEquals("desk2", identity.nickname)
        } finally {
            node.stop()
            transport.close()
        }
    }
}
