package com.bitchat.android.linked

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class PipeFrameIo(
    private val incoming: Channel<ByteArray>,
    private val outgoing: Channel<ByteArray>
) : FrameIo {
    @Volatile
    var isClosed = false

    override suspend fun send(frame: ByteArray): Boolean {
        if (isClosed) return false
        return outgoing.trySend(frame).isSuccess
    }

    override suspend fun receive(): ByteArray? {
        if (isClosed) return null
        return incoming.receiveCatching().getOrNull()
    }

    override fun close() {
        isClosed = true
        incoming.close()
        outgoing.close()
    }
}

internal fun createPipePair(): Pair<FrameIo, FrameIo> {
    val aToB = Channel<ByteArray>(Channel.UNLIMITED)
    val bToA = Channel<ByteArray>(Channel.UNLIMITED)
    return PipeFrameIo(bToA, aToB) to PipeFrameIo(aToB, bToA)
}

@RunWith(RobolectricTestRunner::class)
class LinkQrTest {
    @Test
    fun `encode and parse round trips valid payload`() {
        val devId = "0123456789abcdef0123456789abcdef"
        val secret = ByteArray(32) { it.toByte() }
        val encoded = LinkQr.encode(devId, secret, "192.0.2.10", 45678)
        val parsed = LinkQr.parse(encoded)
        assertEquals(devId, parsed?.deviceId)
        assertArrayEquals(secret, parsed?.secret)
        assertEquals("192.0.2.10", parsed?.host)
        assertEquals(45678, parsed?.port)
    }

    @Test
    fun `parse rejects wrong scheme or malformed secret`() {
        assertNull(LinkQr.parse("https://example.com?d=0123456789abcdef0123456789abcdef&s=AAAA"))
        assertNull(LinkQr.parse("skein-link://v1?d=short&s=AAAA"))
        assertNull(LinkQr.parse("skein-link://v1?d=0123456789abcdef0123456789abcdef&s=short"))
    }
}

@RunWith(RobolectricTestRunner::class)
class LinkHandshakeAndFrameTest {
    private val serverId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val clientId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    @Test
    fun `pair handshake succeeds with matching secret and authenticates frames`() = runTest {
        val secret = ByteArray(32) { (it + 1).toByte() }
        val (clientIo, serverIo) = createPipePair()
        val keys = object : LinkHandshake.ServerKeys {
            override fun pairingSecret() = secret
            override fun linkKey(deviceId: String) = null
        }

        val serverJob = async { LinkHandshake.runServer(serverIo, serverId, keys) }
        val clientRes = LinkHandshake.runClient(clientIo, clientId, LinkHandshake.Mode.PAIR, { secret }, serverId)
        val serverRes = serverJob.await()

        assertTrue(clientRes is LinkHandshake.Result.Ok)
        assertTrue(serverRes is LinkHandshake.Result.Ok)
        clientRes as LinkHandshake.Result.Ok
        serverRes as LinkHandshake.Result.Ok
        assertArrayEquals(clientRes.linkKey, serverRes.linkKey)

        val clientSession = LinkSession(serverId, clientIo, clientRes.cipher, dialed = true)
        val serverSession = LinkSession(clientId, serverIo, serverRes.cipher, dialed = false)
        assertTrue(clientSession.send(LinkFrame.Ping))
        assertEquals(LinkFrame.Ping, serverSession.receive())
    }

    @Test
    fun `pair handshake fails with wrong secret`() = runTest {
        val secret = ByteArray(32) { 1 }
        val wrong = ByteArray(32) { 2 }
        val (clientIo, serverIo) = createPipePair()
        val keys = object : LinkHandshake.ServerKeys {
            override fun pairingSecret() = secret
            override fun linkKey(deviceId: String) = null
        }

        val serverJob = async { LinkHandshake.runServer(serverIo, serverId, keys) }
        val clientRes = LinkHandshake.runClient(clientIo, clientId, LinkHandshake.Mode.PAIR, { wrong }, serverId)
        val serverRes = serverJob.await()

        assertEquals(LinkHandshake.Result.Failed(LinkHandshake.Failure.BAD_PROOF), clientRes)
        assertTrue(serverRes is LinkHandshake.Result.Failed)
    }

    @Test
    fun `link handshake rejects unknown device and accepts known key`() = runTest {
        val linkKey = ByteArray(32) { 9 }
        val (c1, s1) = createPipePair()
        val emptyKeys = object : LinkHandshake.ServerKeys {
            override fun pairingSecret() = null
            override fun linkKey(deviceId: String) = null
        }
        val s1Job = async { LinkHandshake.runServer(s1, serverId, emptyKeys) }
        val c1Res = LinkHandshake.runClient(c1, clientId, LinkHandshake.Mode.LINK, { linkKey })
        val s1Res = s1Job.await()
        assertEquals(LinkHandshake.Result.Failed(LinkHandshake.Failure.UNKNOWN_DEVICE), s1Res)
        assertTrue(c1Res is LinkHandshake.Result.Failed)

        val (c2, s2) = createPipePair()
        val validKeys = object : LinkHandshake.ServerKeys {
            override fun pairingSecret() = null
            override fun linkKey(deviceId: String) = if (deviceId == clientId) linkKey else null
        }
        val s2Job = async { LinkHandshake.runServer(s2, serverId, validKeys) }
        val c2Res = LinkHandshake.runClient(c2, clientId, LinkHandshake.Mode.LINK, { linkKey })
        val s2Res = s2Job.await()
        assertTrue(c2Res is LinkHandshake.Result.Ok)
        assertTrue(s2Res is LinkHandshake.Result.Ok)
    }

    @Test
    fun `unauthenticated tampered or replayed frames fail closed`() = runTest {
        val sendKey = ByteArray(32) { 3 }
        val recvKey = ByteArray(32) { 4 }
        val sender = FrameCipher(sendKey, recvKey)
        val receiver = FrameCipher(recvKey, sendKey)

        val frame0 = sender.seal("hello".toByteArray())
        val frame1 = sender.seal("world".toByteArray())

        // Plaintext / garbage rejected
        assertNull(receiver.open("plaintext-not-gcm".toByteArray()))
        // Out-of-order rejected
        assertNull(receiver.open(frame1))
        // Tampered frame rejected
        val tampered = frame0.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0xFF).toByte() }
        assertNull(receiver.open(tampered))
        // Authentic frame0 accepted once, replay rejected
        assertEquals("hello", String(receiver.open(frame0)!!))
        assertNull(receiver.open(frame0))
    }

    @Test
    fun `link session closes immediately on unauthenticated frame`() = runTest {
        val aToB = Channel<ByteArray>(Channel.UNLIMITED)
        val bToA = Channel<ByteArray>(Channel.UNLIMITED)
        val receiverIo = PipeFrameIo(aToB, bToA)
        val session = LinkSession(serverId, receiverIo, FrameCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 }), dialed = false)

        aToB.trySend("forged-frame".toByteArray())
        assertNull(session.receive())
        assertTrue(session.closed)
    }
}
