package com.bitchat.desktop.ui

import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** All data synthetic: made-up peer IDs, nicknames and fingerprints. */
@OptIn(ExperimentalCoroutinesApi::class)
class SkeinViewModelTest {
    private val scope: CoroutineScope = TestScope(UnconfinedTestDispatcher())
    private var clock = 1_000_000L
    private val now = { Date(clock++) }

    private val messaging = InMemoryMessagingPort(myNickname = "tester", clock = now)
    private val contacts = InMemoryContactsPort()
    private val history = InMemoryHistoryPort()
    private val locations = InMemoryLocationChannelsPort(myNickname = { "tester" }, clock = now)
    private val settings = InMemorySettingsPort()
    private val platform = InMemoryPlatformToggles(isMac = true)

    private val alicePeer = "aaaaaaaaaaaaaaaa"
    private val bobPeer = "bbbbbbbbbbbbbbbb"
    private val aliceFp = "a1".repeat(32)

    private fun ports() = SkeinPorts(messaging, contacts, history, locations, settings, platform)
    private fun vm() = SkeinViewModel(ports(), scope)

    private fun incoming(id: String, conv: ConversationId, from: String?, nick: String, text: String) =
        ChatMessage(id, conv, from, nick, text, now(), isMine = false)

    @After fun tearDown() = scope.cancel()

    @Test
    fun `unread counts per conversation clear when the conversation is opened`() {
        messaging.addPeer(PeerSummary(alicePeer, "alice"))
        val vm = vm()
        messaging.receive(incoming("m1", ConversationId.Mesh, alicePeer, "alice", "hi all"))
        assertEquals("open conversation does not count unread", 0, vm.state.value.mesh.unread)

        messaging.receive(incoming("p1", ConversationId.Private(alicePeer), alicePeer, "alice", "psst"))
        messaging.receive(incoming("p2", ConversationId.Private(alicePeer), alicePeer, "alice", "there?"))
        val dm = vm.state.value.privateChats.single()
        assertEquals(2, dm.unread)
        assertEquals("alice", dm.title)
        assertEquals(2, vm.state.value.people.single().unread)
        assertEquals(2, vm.state.value.totalUnread)

        vm.selectConversation(ConversationId.Private(alicePeer))
        assertEquals(0, vm.state.value.privateChats.single().unread)

        // Mesh messages that arrived while the DM was open are unread; going back clears them.
        messaging.receive(incoming("m2", ConversationId.Mesh, alicePeer, "alice", "again"))
        assertEquals(1, vm.state.value.mesh.unread)
        vm.selectConversation(ConversationId.Mesh)
        assertEquals(0, vm.state.value.mesh.unread)
        vm.selectConversation(ConversationId.Private(alicePeer))
        assertEquals("previously read stay read", 0, vm.state.value.mesh.unread)
    }

    @Test
    fun `send routes the draft to the selected conversation and clears it`() {
        messaging.addPeer(PeerSummary(alicePeer, "alice"))
        val vm = vm()
        vm.updateDraft("   ")
        assertFalse(vm.send())

        vm.updateDraft(" hello mesh ")
        assertTrue(vm.send())
        assertEquals("", vm.state.value.draft)
        val sent = vm.state.value.messages.single().message
        assertEquals("hello mesh", sent.text)
        assertTrue(sent.isMine)
        assertEquals(ConversationId.Mesh, sent.conversation)

        vm.messagePeer(alicePeer)
        vm.updateDraft("secret")
        vm.send()
        val dm = vm.state.value.messages.single().message
        assertEquals(ConversationId.Private(alicePeer), dm.conversation)
        assertEquals(DeliveryState.SENT, dm.delivery)
        assertEquals(0, vm.state.value.privateChats.single().unread)

        messaging.updateDelivery(dm.id, DeliveryState.READ)
        assertEquals(DeliveryState.READ, vm.state.value.messages.single().message.delivery)
    }

    @Test
    fun `location channels join, receive, send and leave`() {
        val vm = vm()
        assertFalse("invalid geohash rejected", vm.joinLocation("not-a-geohash!"))
        assertTrue(vm.joinLocation("#U4PR"))
        val loc = ConversationId.Location("u4pr")
        assertEquals(loc, vm.state.value.selected)
        assertEquals("#u4pr", vm.state.value.selectedTitle)

        vm.updateDraft("hello channel")
        vm.send()
        assertEquals(loc, vm.state.value.messages.single().message.conversation)

        vm.selectConversation(ConversationId.Mesh)
        locations.receive(incoming("l1", loc, null, "zed", "yo"))
        assertEquals(1, vm.state.value.locationChannels.single().unread)

        vm.selectConversation(loc)
        vm.leaveLocation("u4pr")
        assertEquals(ConversationId.Mesh, vm.state.value.selected)
        assertTrue("left channel leaves the sidebar despite its messages", vm.state.value.locationChannels.isEmpty())
    }

    @Test
    fun `stand-in location channels and history are reported unavailable`() {
        val vm = SkeinViewModel(
            ports().copy(
                history = InMemoryHistoryPort(isAvailable = false),
                locations = InMemoryLocationChannelsPort(isAvailable = false),
            ),
            scope,
        )
        assertFalse(vm.state.value.locationChannelsAvailable)
        assertFalse(vm.state.value.historyAvailable)
        assertFalse("joining is refused", vm.joinLocation("u4pr"))
        assertTrue(vm().state.value.locationChannelsAvailable)
    }

    @Test
    fun `messages arriving while the window is hidden stay unread until it is shown`() {
        messaging.addPeer(PeerSummary(alicePeer, "alice"))
        val vm = vm()
        vm.setWindowActive(false)
        messaging.receive(incoming("m1", ConversationId.Mesh, alicePeer, "alice", "while away"))
        assertEquals(1, vm.state.value.mesh.unread)
        assertEquals(1, vm.state.value.totalUnread)

        vm.setWindowActive(true)
        assertEquals(0, vm.state.value.mesh.unread)
        messaging.receive(incoming("m2", ConversationId.Mesh, alicePeer, "alice", "back"))
        assertEquals(0, vm.state.value.mesh.unread)
    }

    @Test
    fun `mentions highlight messages addressed to me and drive composer suggestions`() {
        messaging.addPeer(PeerSummary(alicePeer, "alice"))
        messaging.addPeer(PeerSummary(bobPeer, "albert"))
        val vm = vm()
        vm.selectConversation(ConversationId.Private(bobPeer))
        messaging.receive(incoming("m1", ConversationId.Mesh, alicePeer, "alice", "hey @tester look"))
        messaging.receive(incoming("m2", ConversationId.Mesh, alicePeer, "alice", "@albert hi"))
        assertTrue(vm.state.value.mesh.mentionsMe)

        vm.selectConversation(ConversationId.Mesh)
        val rows = vm.state.value.messages
        assertTrue(rows[0].mentionsMe)
        assertEquals(listOf("tester"), rows[0].mentions)
        assertFalse(rows[1].mentionsMe)
        assertEquals(listOf("albert"), rows[1].mentions)

        vm.updateDraft("ping @al")
        assertEquals(listOf("albert", "alice"), vm.state.value.mentionSuggestions)
        vm.pickMention("alice")
        assertEquals("ping @alice ", vm.state.value.draft)
        assertTrue(vm.state.value.mentionSuggestions.isEmpty())
    }

    @Test
    fun `favorites sort first and toggle on and off`() {
        messaging.addPeer(PeerSummary(alicePeer, "alice"))
        messaging.addPeer(PeerSummary(bobPeer, "bob"))
        val vm = vm()
        assertEquals(listOf("alice", "bob"), vm.state.value.people.map { it.nickname })
        vm.toggleFavorite(bobPeer)
        assertEquals(listOf("bob", "alice"), vm.state.value.people.map { it.nickname })
        assertTrue(vm.state.value.people.first().isFavorite)
        vm.toggleFavorite(bobPeer)
        assertFalse(vm.state.value.people.any { it.isFavorite })
    }

    @Test
    fun `peer detail shows both fingerprints and marks the peer verified`() {
        messaging.addPeer(PeerSummary(alicePeer, "alice"), fingerprint = aliceFp)
        messaging.addPeer(PeerSummary(bobPeer, "bob"))
        val vm = vm()
        vm.openPeer(alicePeer)
        val d = vm.state.value.peerDetail!!
        assertEquals(messaging.myFingerprint, d.myFingerprint)
        assertEquals(aliceFp, d.theirFingerprint)
        assertFalse(d.isVerified)

        vm.setVerified(alicePeer, true)
        assertTrue(vm.state.value.peerDetail!!.isVerified)
        assertTrue(vm.state.value.people.first { it.peerID == alicePeer }.isVerified)

        vm.setVerified(alicePeer, false)
        assertFalse(vm.state.value.peerDetail!!.isVerified)

        // Without a known fingerprint nothing can be verified.
        vm.openPeer(bobPeer)
        assertNull(vm.state.value.peerDetail!!.theirFingerprint)
        vm.setVerified(bobPeer, true)
        assertFalse(vm.state.value.peerDetail!!.isVerified)

        vm.startSession(bobPeer)
        assertEquals(SessionState.ESTABLISHED, vm.state.value.peerDetail!!.session)
        vm.closePane()
        assertEquals(MainPane.Conversation, vm.state.value.pane)
    }

    @Test
    fun `settings changes reach the platform and are persisted`() {
        val vm = vm()
        vm.setLanguage(AppLanguage.ARABIC)
        vm.setBroadcastPower(BroadcastPower.HIGH)
        vm.setShowInDock(false)
        vm.setStartAtLogin(true)
        vm.setCrashReports(true)
        val s = vm.state.value.settings
        assertEquals(AppLanguage.ARABIC, s.language)
        assertEquals(stringsFor(AppLanguage.ARABIC), vm.state.value.strings)
        assertEquals(BroadcastPower.HIGH, platform.power)
        assertFalse(platform.dockVisible)
        assertTrue(platform.autostart)
        assertTrue(platform.crash)
        assertEquals(s, settings.load())

        // A new view model reads the saved settings and the live platform state.
        platform.autostart = false
        val reopened = vm().state.value.settings
        assertEquals(AppLanguage.ARABIC, reopened.language)
        assertFalse(reopened.startAtLogin)
    }

    @Test
    fun `nickname is validated and applied`() {
        val vm = vm()
        assertFalse(vm.setNickname("   "))
        assertFalse(vm.setNickname("x".repeat(MAX_NICKNAME_CHARS + 1)))
        assertTrue(vm.setNickname("  newname "))
        assertEquals("newname", messaging.nickname())
        assertEquals("newname", vm.state.value.nickname)
    }

    @Test
    fun `history is saved, restored as read, and deleted when turned off`() {
        messaging.addPeer(PeerSummary(alicePeer, "alice"))
        val vm = vm()
        messaging.receive(incoming("p1", ConversationId.Private(alicePeer), alicePeer, "alice", "saved"))
        assertEquals(listOf("p1"), history.load().map { it.id })

        // A fresh run with no live messages restores history, already read.
        val restartedMessaging = InMemoryMessagingPort(myNickname = "tester", clock = now)
        val restarted = SkeinViewModel(SkeinPorts(restartedMessaging, contacts, history, locations, settings, platform), scope)
        val dm = restarted.state.value.privateChats.single()
        assertEquals("alice", dm.title)
        assertEquals(0, dm.unread)

        vm.setSaveHistory(false)
        assertTrue(history.load().isEmpty())
        messaging.receive(incoming("p2", ConversationId.Private(alicePeer), alicePeer, "alice", "not saved"))
        assertTrue(history.load().isEmpty())

        vm.setSaveHistory(true)
        assertEquals(setOf("p1", "p2"), history.load().map { it.id }.toSet())
    }

    @Test
    fun `history merge keeps the live copy and orders by time`() {
        val old = ChatMessage("x", ConversationId.Mesh, bobPeer, "bob", "t", Date(5), isMine = true, delivery = DeliveryState.SENT)
        val live = old.copy(delivery = DeliveryState.READ)
        val earlier = old.copy(id = "y", timestamp = Date(1))
        val merged = mergeHistory(listOf(old, earlier), listOf(live))
        assertEquals(listOf("y", "x"), merged.map { it.id })
        assertEquals(DeliveryState.READ, merged.last().delivery)
    }

    @Test
    fun `panes open and close back to the conversation`() {
        val vm = vm()
        vm.openPane(MainPane.Settings)
        assertEquals(MainPane.Settings, vm.state.value.pane)
        vm.openPane(MainPane.LinkedDevices)
        assertEquals(MainPane.LinkedDevices, vm.state.value.pane)
        vm.openPane(MainPane.Backups)
        vm.closePane()
        assertEquals(MainPane.Conversation, vm.state.value.pane)
    }
}
