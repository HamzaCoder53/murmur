package com.bitchat.desktop.nostr

import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.desktop.TestDevices
import com.bitchat.desktop.ui.ConversationId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrLocationChannelsPortTest {
    private val devices = TestDevices()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
        devices.close()
    }

    @Test
    fun `joins, sends, and verifies incoming signed ephemeral geohash events without public relays`() = runBlocking {
        val ctx = devices.context("nostr")
        val relay = DesktopNostrRelayClient(emptyList(), scope)
        val port = NostrLocationChannelsPort(
            context = ctx,
            myNickname = { "synthetic-alice" },
            relayClient = relay,
            scope = scope,
        )

        port.join("dr5r")
        assertEquals(listOf("dr5r"), port.channels.value.map { it.geohash })

        port.send("dr5r", "synthetic geohash hello")
        val sent = withTimeout(5_000) {
            port.messages.first { list -> list.any { it.text == "synthetic geohash hello" } }
        }
        assertTrue(sent.first().isMine)
        assertEquals(ConversationId.Location("dr5r"), sent.first().conversation)

        // Simulate an inbound signed Nostr event from a synthetic peer over the relay.
        val bobId = NostrIdentityBridge.deriveIdentity(forGeohash = "dr5r", context = devices.context("nostr-bob"))
        val bobEvent = NostrProtocol.createEphemeralGeohashEvent(
            content = "synthetic peer note",
            geohash = "dr5r",
            senderIdentity = bobId,
            nickname = "synthetic-bob",
        )
        val wire = JSONArray()
            .put("EVENT")
            .put("geo-dr5r")
            .put(JSONObject(bobEvent.toJsonString()))
            .toString()
        relay.handleRelayMessage(wire)

        val all = port.messages.value
        assertTrue(all.any { it.text == "synthetic peer note" && it.senderNickname == "synthetic-bob" && !it.isMine })

        port.leave("dr5r")
        assertTrue(port.channels.value.isEmpty())
    }
}
