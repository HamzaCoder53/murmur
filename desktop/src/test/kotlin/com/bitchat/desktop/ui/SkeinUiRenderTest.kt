package com.bitchat.desktop.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Renders every pane off-screen in both languages, so composition errors fail the build. */
@OptIn(ExperimentalCoroutinesApi::class)
class SkeinUiRenderTest {
    @Test
    fun `every pane renders in English and Arabic`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        try {
            val messaging = InMemoryMessagingPort(myNickname = "tester")
            val peer = "cccccccccccccccc"
            messaging.addPeer(PeerSummary(peer, "carol", SessionState.ESTABLISHED), fingerprint = "c3".repeat(32))
            messaging.receive(ChatMessage("m1", ConversationId.Mesh, peer, "carol", "hi @tester", Date(), isMine = false))
            messaging.receive(ChatMessage("p1", ConversationId.Private(peer), peer, "carol", "psst", Date(), isMine = false))
            val ports = SkeinPorts(messaging, platform = InMemoryPlatformToggles(isMac = true))
            val vm = SkeinViewModel(ports, scope)
            messaging.sendPrivate(peer, "reply")
            vm.joinLocation("u4pr")

            val panes = listOf(
                { vm.selectConversation(ConversationId.Mesh) },
                { vm.selectConversation(ConversationId.Private(peer)) },
                { vm.selectConversation(ConversationId.Location("u4pr")) },
                { vm.openPeer(peer) },
                { vm.openPane(MainPane.Settings) },
                { vm.openPane(MainPane.LinkedDevices) },
                { vm.openPane(MainPane.Backups) },
            )
            for (lang in AppLanguage.entries) {
                vm.setLanguage(lang)
                for (open in panes) {
                    open()
                    ImageComposeScene(width = 1080, height = 720) { SkeinDesktopRoot(vm, ports) }.use { scene ->
                        assertNotNull(scene.render())
                    }
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
