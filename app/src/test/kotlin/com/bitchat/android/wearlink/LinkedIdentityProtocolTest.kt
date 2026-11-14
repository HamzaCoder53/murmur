package com.bitchat.android.wearlink

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class LinkedIdentityProtocolTest {

    private fun bundle(identity: Map<String, String> = mapOf(
        "static_private_key" to "cHJpdmF0ZQ==\n",
        "static_public_key" to "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=\n",
        "nostr_private_key" to "ab".repeat(32)
    )) = IdentityBundle.Contents(
        nickname = "hamza",
        identityStrings = identity,
        identitySets = mapOf("verified_fingerprints" to setOf("fp1", "fp2")),
        cryptoStrings = mapOf("ed25519_signing_private_key" to "c2lnbg==\n")
    )

    @Test
    fun `identity bundle round trips every stored value exactly`() {
        val original = bundle()

        val decoded = IdentityBundle.decode(IdentityBundle.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `identity bundle peer id is the first 16 hex of sha256 of the static public key`() {
        // SHA-256 of bytes 0x00..0x1f
        assertEquals("630dcd2966c43366", bundle().peerID)
    }

    @Test
    fun `identity bundle without a noise key is rejected`() {
        val broken = bundle(identity = mapOf("nostr_private_key" to "ab".repeat(32)))

        assertNull(IdentityBundle.decode(IdentityBundle.encode(broken)))
    }

    @Test
    fun `identity bundle ignores keys it does not know`() {
        val withExtra = bundle(identity = bundle().identityStrings + ("cached_peer_noise_keys" to "x"))

        val decoded = IdentityBundle.decode(IdentityBundle.encode(withExtra))!!

        assertTrue("cached_peer_noise_keys" !in decoded.identityStrings)
    }

    @Test
    fun `garbage is not an identity bundle`() {
        assertNull(IdentityBundle.decode("not json".toByteArray()))
        assertNull(IdentityBundle.decode("""{"v":99}""".toByteArray()))
    }

    private fun msg(id: String, status: DeliveryStatus? = DeliveryStatus.Sent, content: String = "hi $id") =
        BitchatMessage(
            id = id, sender = "alice", content = content, timestamp = Date(1_700_000_000_000L + id.hashCode()),
            isPrivate = true, senderPeerID = "1122334455667788", recipientNickname = "bob",
            deliveryStatus = status
        )

    @Test
    fun `snapshot round trips peers messages and delivery states`() {
        val delivered = DeliveryStatus.Delivered("1122334455667788", Date(1_700_000_001_000L))
        val snapshot = MirrorProtocol.Snapshot(
            myPeerID = "aaaaaaaaaaaaaaaa",
            myNickname = "hamza",
            peers = listOf(MirrorProtocol.Peer("1122334455667788", "alice", connected = true, hasSession = true)),
            publicMessages = listOf(msg("p1").copy(isPrivate = false)),
            conversations = listOf(
                MirrorProtocol.Conversation("conv", "1122334455667788", listOf(msg("d1", delivered), msg("d2", DeliveryStatus.Failed("x"))))
            )
        )

        val decoded = MirrorProtocol.decodeSnapshot(MirrorProtocol.encodeSnapshot(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `media becomes a short placeholder in the mirror`() {
        val photo = msg("img").copy(type = BitchatMessageType.Image, content = "/data/user/0/app/files/x.jpg")

        val decoded = MirrorProtocol.decodeMessage(MirrorProtocol.encodeMessage(photo))

        assertEquals("[photo]", decoded.content)
    }

    @Test
    fun `an oversized snapshot is trimmed to fit and keeps the newest messages`() {
        val big = "x".repeat(2_000)
        val messages = (1..200).map { msg("m$it", content = big) }
        val snapshot = MirrorProtocol.Snapshot("me", "n", emptyList(), messages, emptyList())

        val bytes = MirrorProtocol.encodeSnapshot(snapshot)
        val decoded = MirrorProtocol.decodeSnapshot(bytes)!!

        assertTrue(bytes.size <= MirrorProtocol.MAX_PAYLOAD_BYTES)
        assertEquals("m200", decoded.publicMessages.last().id)
    }

    @Test
    fun `commands round trip`() {
        val pub = MirrorProtocol.Command.SendPublic("id1", "hello")
        val dm = MirrorProtocol.Command.SendPrivate("id2", "secret", "1122334455667788", "alice")

        assertEquals(pub, MirrorProtocol.decodeCommand(MirrorProtocol.encodeCommand(pub)))
        assertEquals(dm, MirrorProtocol.decodeCommand(MirrorProtocol.encodeCommand(dm)))
    }

    @Test
    fun `a nickname change round trips and is capped`() {
        val long = MirrorProtocol.Command.SetNickname("a".repeat(50))

        val decoded = MirrorProtocol.decodeCommand(MirrorProtocol.encodeCommand(long))

        assertEquals(MirrorProtocol.Command.SetNickname("a".repeat(MirrorProtocol.MAX_NICKNAME)), decoded)
    }

    @Test
    fun `an empty command is rejected`() {
        assertNull(MirrorProtocol.decodeCommand(MirrorProtocol.encodeCommand(MirrorProtocol.Command.SendPublic("id", " "))))
    }

    @Test
    fun `an empty backlog decodes`() {
        assertNotNull(MirrorProtocol.decodeBacklog(MirrorProtocol.encodeBacklog(emptyList(), emptyList())))
    }
}
