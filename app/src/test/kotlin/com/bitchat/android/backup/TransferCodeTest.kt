package com.bitchat.android.backup

import com.bitchat.android.linked.PasskeyPairing
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

@RunWith(RobolectricTestRunner::class)
class TransferCodeTest {

    private val invitation = TransferProtocol.Invitation(ByteArray(32) { it.toByte() }, "ABCDEF", listOf("192.0.2.10:4242"))

    @Test
    fun `the invitation survives sealing with the code secret and nothing else opens it`() {
        val secret = ByteArray(32) { 7 }
        val sealed = TransferCode.seal(secret, invitation)
        assertEquals(invitation, TransferCode.open(secret, sealed))
        assertNull(TransferCode.open(ByteArray(32) { 8 }, sealed))
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()
        assertNull(TransferCode.open(secret, sealed))
    }

    @Test
    fun `passkey pairing runs over socket-style streams`() = runTest {
        val aOut = PipedOutputStream(); val bIn = PipedInputStream(aOut, 65536)
        val bOut = PipedOutputStream(); val aIn = PipedInputStream(bOut, 65536)
        val newPhone = TransferCode.StreamFrameIo(DataInputStream(aIn), DataOutputStream(aOut))
        val oldPhone = TransferCode.StreamFrameIo(DataInputStream(bIn), DataOutputStream(bOut))
        val responder = async(kotlinx.coroutines.Dispatchers.IO) {
            val hello = oldPhone.receive()!!
            PasskeyPairing.runResponder(oldPhone, hello, "314159")
        }
        val initiator = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            PasskeyPairing.runInitiator(newPhone, "314159")
        }
        val r = responder.await()
        assertTrue(initiator is PasskeyPairing.Result.Ok && r is PasskeyPairing.Result.Ok)
        assertArrayEquals((initiator as PasskeyPairing.Result.Ok).secret, (r as PasskeyPairing.Result.Ok).secret)
    }
}
