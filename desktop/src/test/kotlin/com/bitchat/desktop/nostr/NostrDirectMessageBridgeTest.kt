package com.bitchat.desktop.nostr

import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.desktop.TestDevices
import com.bitchat.desktop.core.DesktopIdentity
import com.bitchat.desktop.core.DesktopMeshNode
import com.bitchat.desktop.core.LoopbackHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrDirectMessageBridgeTest {
    private val devices = TestDevices()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
        devices.close()
    }

    @Test
    fun `unwraps NIP-17 gift-wrapped private message into DesktopMeshNode`() {
        val aliceCtx = devices.context("alice")
        val bobCtx = devices.context("bob")
        val aliceId = NostrIdentityBridge.deriveIdentity("alice-nostr", aliceCtx)
        val bobId = NostrIdentityBridge.deriveIdentity("bob-nostr", bobCtx)

        val bobMeshId = DesktopIdentity.loadOrCreate(bobCtx).apply { nickname = "bob" }
        val (bobNode, bobTransport) = DesktopMeshNode.overLoopback(bobMeshId, LoopbackHub())
        val relay = DesktopNostrRelayClient(emptyList(), scope)
        val bridge = NostrDirectMessageBridge(bobCtx, bobNode, relay, identityOverride = bobId)

        val wrapped = NostrProtocol.createPrivateMessage(
            content = "synthetic nip17 secret",
            recipientPubkey = bobId.publicKeyHex,
            senderIdentity = aliceId,
        )
        assertTrue(wrapped.isNotEmpty())
        assertTrue(bridge.handleGiftWrap(wrapped.first(), bobId))

        val stored = bobNode.privateMessages.value
        assertTrue(stored.any { it.content == "synthetic nip17 secret" && it.isPrivate })

        bobNode.stop()
        bobTransport.close()
    }
}
