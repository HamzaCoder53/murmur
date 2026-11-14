package com.bitchat.watch.internet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.nostr.NostrEmbeddedBitChat
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.android.services.AppStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * End-to-end check of the watch's internet messaging against Bitchat's real public relays, with
 * two throwaway identities. Skipped unless explicitly enabled, because it uses the network and
 * public relays:  ./gradlew :wear:testDebugUnitTest --tests '*LiveRelayTest*' -Pmurmur.liveRelays=true
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveRelayTest {

    @Before
    fun onlyWhenEnabled() {
        assumeTrue("live relay test disabled", System.getProperty("murmur.liveRelays") == "true")
        org.robolectric.shadows.ShadowLog.stream = System.out
    }

    private fun lease() = WatchNostrClient.Lease(
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    ) { }

    @Test
    fun `a private message and its delivery receipt travel through the real relays`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val alice = NostrIdentity.generate()
        val bob = NostrIdentity.generate()
        val alicePeer = "aaaaaaaaaaaaaaaa"
        val bobPeer = "bbbbbbbbbbbbbbbb"

        val received = CompletableDeferred<BitchatMessage>()
        val bobClient = WatchNostrClient(context, scope, { bobPeer }, { "bob" }, { received.complete(it) }, { bob }, { lease() })
        val aliceClient = WatchNostrClient(context, scope, { alicePeer }, { "alice" }, { }, { alice }, { lease() })
        bobClient.setMode(WatchNostrClient.Mode.OFF)
        bobClient.start()
        aliceClient.start()

        // Alice sends exactly what the phone and watch send: an embedded Bitchat PM in a gift wrap.
        val messageID = UUID.randomUUID().toString()
        val text = "murmur live relay test ${messageID.take(8)}"
        AppStateStore.addPrivateMessage(
            "nostr_${bob.publicKeyHex.take(16)}",
            BitchatMessage(
                id = messageID, sender = "alice", content = text, timestamp = Date(), isPrivate = true,
                senderPeerID = alicePeer, deliveryStatus = DeliveryStatus.Sent
            )
        )
        val embedded = NostrEmbeddedBitChat.encodePMForNostr(text, messageID, bobPeer, alicePeer)!!
        assertTrue("no relay accepted the message", aliceClient.publish(NostrProtocol.createPrivateMessage(embedded, bob.publicKeyHex, alice)))
        println("LIVE: published gift wrap to relays")

        // Bob checks, decrypts and acknowledges.
        delay(2_000)
        bobClient.fetchOnce()
        val message = withTimeout(30_000) { received.await() }
        assertEquals(text, message.content)
        println("LIVE: bob received '${message.content}' (status ${bobClient.status.value})")

        // Alice checks until the DELIVERED receipt comes back.
        var status: DeliveryStatus? = null
        repeat(6) {
            if (status is DeliveryStatus.Delivered) return@repeat
            delay(4_000)
            aliceClient.fetchOnce()
            delay(1_000)
            status = AppStateStore.privateMessages.value.values.flatten().firstOrNull { it.id == messageID }?.deliveryStatus
        }
        println("LIVE: alice sees status $status")
        assertTrue("delivery receipt did not arrive: $status", status is DeliveryStatus.Delivered)

        // Bob opens the chat: a read receipt goes back over the internet.
        assertTrue("read receipt not accepted", bobClient.sendReadReceipt(messageID, alicePeer, alice.publicKeyHex))
        repeat(6) {
            if (status is DeliveryStatus.Read) return@repeat
            delay(4_000)
            aliceClient.fetchOnce()
            delay(1_000)
            status = AppStateStore.privateMessages.value.values.flatten().firstOrNull { it.id == messageID }?.deliveryStatus
        }
        println("LIVE: alice sees status $status")
        assertTrue("read receipt did not arrive: $status", status is DeliveryStatus.Read)
    }
}
