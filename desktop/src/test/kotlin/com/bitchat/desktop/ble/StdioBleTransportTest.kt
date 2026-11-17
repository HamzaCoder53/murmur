package com.bitchat.desktop.ble

import com.bitchat.desktop.platform.BleLink
import com.bitchat.desktop.platform.BleState
import com.bitchat.desktop.platform.BleTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StdioBleTransportTest {
    private class FakeEndpoint : StdioBleTransport.Endpoint {
        val helperOut = PipedOutputStream()
        override val stdout: InputStream = PipedInputStream(helperOut)
        val helperIn = PipedInputStream()
        override val stdin: OutputStream = PipedOutputStream(helperIn)
        @Volatile var exitCode = 0
        private val closedLatch = CountDownLatch(1)

        fun emitLine(json: String) {
            helperOut.write((json + "\n").toByteArray(Charsets.UTF_8))
            helperOut.flush()
        }

        fun closeStdout() {
            helperOut.close()
        }

        override fun waitForExit(): Int {
            closedLatch.await(1, TimeUnit.SECONDS)
            return exitCode
        }

        override fun close() {
            closedLatch.countDown()
            try { helperOut.close() } catch (_: Exception) { }
            try { stdin.close() } catch (_: Exception) { }
        }
    }

    @Test
    fun `parses state, linkUp, linkUpdated, frame, and linkDown events`() {
        val ep = FakeEndpoint()
        val transport = StdioBleTransport { ep }
        val states = mutableListOf<BleState>()
        val linksUp = mutableListOf<BleLink>()
        val linksUpdated = mutableListOf<BleLink>()
        val linksDown = mutableListOf<String>()
        val frames = mutableListOf<Pair<String, ByteArray>>()
        val frameLatch = CountDownLatch(1)
        val downLatch = CountDownLatch(1)

        transport.start(object : BleTransport.Listener {
            override fun onLinkUp(link: BleLink) { linksUp += link }
            override fun onLinkUpdated(link: BleLink) { linksUpdated += link }
            override fun onLinkDown(linkId: String) { linksDown += linkId; downLatch.countDown() }
            override fun onFrame(linkId: String, frame: ByteArray) { frames += linkId to frame; frameLatch.countDown() }
            override fun onStateChanged(state: BleState) { states += state }
        })

        ep.emitLine("""{"ev":"state","state":"ready"}""")
        ep.emitLine("""{"ev":"linkUp","link":"lnk-1","rssi":-55,"mtu":185,"role":"central"}""")
        ep.emitLine("""{"ev":"linkUpdated","link":"lnk-1","mtu":247}""")
        val payload = byteArrayOf(1, 2, 3, 4)
        ep.emitLine("""{"ev":"frame","link":"lnk-1","b64":"${Base64.getEncoder().encodeToString(payload)}"}""")

        assertTrue(frameLatch.await(2, TimeUnit.SECONDS))
        assertTrue(transport.isAvailable)
        assertEquals(listOf(BleState.READY), states)
        assertEquals(1, linksUp.size)
        assertEquals(185, linksUp[0].mtu)
        assertEquals(BleLink.Role.CENTRAL, linksUp[0].role)
        assertEquals(247, linksUpdated[0].mtu)
        assertArrayEquals(payload, frames[0].second)
        assertTrue(transport.send("lnk-1", byteArrayOf(9, 8)))
        assertEquals(1, transport.broadcast(byteArrayOf(7, 6)))

        ep.emitLine("""{"ev":"linkDown","link":"lnk-1"}""")
        assertTrue(downLatch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("lnk-1"), linksDown)
        assertFalse(transport.send("lnk-1", byteArrayOf(1)))
        transport.stop()
    }

    @Test
    fun `reports HELPER_CRASHED when helper stdout closes unexpectedly`() {
        val ep = FakeEndpoint().apply { exitCode = 137 }
        val transport = StdioBleTransport { ep }
        val crashedLatch = CountDownLatch(1)
        val errors = mutableListOf<String>()

        transport.start(object : BleTransport.Listener {
            override fun onLinkUp(link: BleLink) = Unit
            override fun onLinkDown(linkId: String) = Unit
            override fun onFrame(linkId: String, frame: ByteArray) = Unit
            override fun onStateChanged(state: BleState) {
                if (state == BleState.HELPER_CRASHED) crashedLatch.countDown()
            }
            override fun onError(message: String) { errors += message }
        })

        ep.closeStdout()
        assertTrue(crashedLatch.await(2, TimeUnit.SECONDS))
        assertEquals(BleState.HELPER_CRASHED, transport.state)
        assertTrue(errors.any { "137" in it })
        transport.stop()
    }

    @Test
    fun `restarts a crashed helper with backoff and gives up after the limit`() {
        val endpoints = mutableListOf<FakeEndpoint>()
        val delays = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val gaveUp = CountDownLatch(1)
        val secondReady = CountDownLatch(1)
        val transport = StdioBleTransport(maxRestarts = 2, sleepMs = { delays += it }) {
            FakeEndpoint().also { synchronized(endpoints) { endpoints += it } }
        }
        transport.start(object : BleTransport.Listener {
            override fun onLinkUp(link: BleLink) = Unit
            override fun onLinkDown(linkId: String) = Unit
            override fun onFrame(linkId: String, frame: ByteArray) = Unit
            override fun onStateChanged(state: BleState) {
                if (state == BleState.READY) secondReady.countDown()
            }
            override fun onError(message: String) { if ("gave up" in message) gaveUp.countDown() }
        })
        fun endpoint(i: Int): FakeEndpoint {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                synchronized(endpoints) { if (endpoints.size > i) return endpoints[i] }
                Thread.sleep(5)
            }
            error("helper $i was not started")
        }
        endpoint(0).closeStdout()
        // The restarted helper comes up and reports READY, which resets the restart budget.
        endpoint(1).emitLine("""{"ev":"state","state":"ready"}""")
        assertTrue(secondReady.await(2, TimeUnit.SECONDS))
        endpoint(1).closeStdout()
        endpoint(2).closeStdout()
        endpoint(3).closeStdout()
        assertTrue(gaveUp.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(1_000L, 1_000L, 2_000L), delays.toList())
        transport.stop()
    }
}
