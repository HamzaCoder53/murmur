package com.bitchat.android.linked

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PasskeyPairingTest {

    private suspend fun kotlinx.coroutines.CoroutineScope.run(typed: String, shown: String?) : Pair<PasskeyPairing.Result, PasskeyPairing.Result> {
        val (a, b) = createPipePair()
        val responder = async {
            val hello = b.receive()!!
            PasskeyPairing.runResponder(b, hello, shown)
        }
        val initiator = PasskeyPairing.runInitiator(a, typed)
        return initiator to responder.await()
    }

    @Test
    fun `the same code gives both sides the same secret`() = runTest {
        val (i, r) = run("042137", "042137")
        assertTrue(i is PasskeyPairing.Result.Ok && r is PasskeyPairing.Result.Ok)
        assertArrayEquals((i as PasskeyPairing.Result.Ok).secret, (r as PasskeyPairing.Result.Ok).secret)
        assertEquals(32, i.secret.size)
    }

    @Test
    fun `a wrong code fails on both sides`() = runTest {
        val (i, r) = run("042136", "042137")
        assertEquals(PasskeyPairing.Result.Failed(PasskeyPairing.Failure.WRONG_CODE), i)
        assertEquals(PasskeyPairing.Result.Failed(PasskeyPairing.Failure.WRONG_CODE), r)
    }

    @Test
    fun `a wrong code is caught at the first differing bit`() = runTest {
        // 000000 vs 000001 differ in bit 0, so the attempt ends after the first round.
        val (i, r) = run("000000", "000001")
        assertTrue(i is PasskeyPairing.Result.Failed && r is PasskeyPairing.Result.Failed)
    }

    @Test
    fun `a device that is not offering a code says so`() = runTest {
        val (i, r) = run("123456", null)
        assertEquals(PasskeyPairing.Result.Failed(PasskeyPairing.Failure.NO_OFFER), i)
        assertEquals(PasskeyPairing.Result.Failed(PasskeyPairing.Failure.NO_OFFER), r)
    }

    @Test
    fun `two runs with the same code never share a secret`() = runTest {
        val (first, _) = run("777777", "777777")
        val (second, _) = run("777777", "777777")
        assertFalse(
            (first as PasskeyPairing.Result.Ok).secret.contentEquals((second as PasskeyPairing.Result.Ok).secret)
        )
    }

    @Test
    fun `codes are six digits and cover twenty bits`() {
        repeat(50) { assertTrue(PasskeyPairing.isCode(PasskeyPairing.newCode())) }
        assertFalse(PasskeyPairing.isCode("12345"))
        assertFalse(PasskeyPairing.isCode("12a456"))
        assertEquals(1, PasskeyPairing.bitsOf("999999")[19]) // 999999 needs the 20th bit
        assertTrue(PasskeyPairing.isHello("""{"v":1,"t":"pk-hello","pk":"x"}""".toByteArray()))
        assertFalse(PasskeyPairing.isHello("""{"v":1,"t":"hello"}""".toByteArray()))
    }
}
