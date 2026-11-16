package com.bitchat.desktop.core

import com.bitchat.android.linked.FrameIo
import com.bitchat.android.linked.PasskeyPairing
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typed-code pairing (the 6-digit code used to link a phone, watch or desktop) end to end on the
 * desktop JVM. The full behaviour matrix runs in the mirrored app PasskeyPairingTest.
 */
class PasskeyPairingOnJvmTest {
    private class Pipe(private val inbox: Channel<ByteArray>, private val outbox: Channel<ByteArray>) : FrameIo {
        override suspend fun send(frame: ByteArray): Boolean = outbox.trySend(frame).isSuccess
        override suspend fun receive(): ByteArray? = inbox.receiveCatching().getOrNull()
        override fun close() {
            outbox.close()
        }
    }

    private fun pipes(): Pair<FrameIo, FrameIo> {
        val ab = Channel<ByteArray>(Channel.UNLIMITED)
        val ba = Channel<ByteArray>(Channel.UNLIMITED)
        return Pipe(ba, ab) to Pipe(ab, ba)
    }

    @Test
    fun `desktop initiator and responder derive the same secret from the same code`() = runBlocking {
        val code = PasskeyPairing.newCode()
        val (a, b) = pipes()
        val (initiator, responder) = coroutineScope {
            val r = async { PasskeyPairing.runResponder(b, b.receive()!!, code) }
            PasskeyPairing.runInitiator(a, code) to r.await()
        }
        assertTrue(initiator is PasskeyPairing.Result.Ok)
        assertTrue(responder is PasskeyPairing.Result.Ok)
        val s1 = (initiator as PasskeyPairing.Result.Ok).secret
        val s2 = (responder as PasskeyPairing.Result.Ok).secret
        assertEquals(32, s1.size)
        assertArrayEquals(s1, s2)
    }

    @Test
    fun `a mistyped code fails on both sides`() = runBlocking {
        val (a, b) = pipes()
        val (initiator, responder) = coroutineScope {
            val r = async { PasskeyPairing.runResponder(b, b.receive()!!, "123456") }
            PasskeyPairing.runInitiator(a, "123457") to r.await()
        }
        assertEquals(PasskeyPairing.Result.Failed(PasskeyPairing.Failure.WRONG_CODE), initiator)
        assertEquals(PasskeyPairing.Result.Failed(PasskeyPairing.Failure.WRONG_CODE), responder)
    }
}
