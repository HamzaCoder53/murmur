package com.bitchat.watch.ui

import com.bitchat.watch.link.LinkMode
import com.bitchat.watch.rendezvous.RendezvousEngine.Phase
import com.bitchat.watch.rendezvous.RendezvousEngine.Status
import com.bitchat.watch.rendezvous.RendezvousSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDiagnosticsTest {

    private fun lines(mode: LinkMode, status: Status, offsetMs: Long = 0L) =
        LinkDiagnostics.lines(mode, status, offsetMs) { "t$it" }.associate { it.label to it.value }

    @Test
    fun `tethered shows the phone carrying the mesh and nothing about scanning`() {
        val shown = lines(LinkMode.TETHERED, Status(phase = Phase.WAITING, nextWindowWallMs = 5L))

        assertEquals(mapOf("Link" to "Phone", "Scan" to "Off, phone relays"), shown)
    }

    @Test
    fun `standalone waiting on the shared schedule shows synced with the next scan time`() {
        val shown = lines(
            LinkMode.STANDALONE,
            Status(phase = Phase.WAITING, preciseTiming = true, nextWindowWallMs = 55L)
        )

        assertEquals("Own radio", shown["Link"])
        assertEquals("Synced", shown["Scan"])
        assertEquals("t55", shown["Next scan"])
        assertEquals("Precise", shown["Timing"])
    }

    @Test
    fun `recovery reads as searching`() {
        val shown = lines(
            LinkMode.STANDALONE,
            Status(phase = Phase.WAITING, mode = RendezvousSchedule.Mode.RECOVERY)
        )

        assertEquals("Searching", shown["Scan"])
        assertEquals("Approximate", shown["Timing"])
    }

    @Test
    fun `stopped engine while standalone means bluetooth is not running`() {
        val shown = lines(LinkMode.STANDALONE, Status())

        assertEquals("Bluetooth off", shown["Scan"])
        assertFalse(shown.containsKey("Timing"))
    }

    @Test
    fun `last scan and scan count appear once a window has closed`() {
        val shown = lines(
            LinkMode.STANDALONE,
            Status(phase = Phase.WAITING, lastWindowSawPeer = false, windowsCompleted = 3)
        )

        assertEquals("No one", shown["Last scan"])
        assertEquals("3", shown["Scans done"])
    }

    @Test
    fun `clock offset is signed tenths of a second`() {
        assertEquals("+0.0 s", LinkDiagnostics.formatOffset(0))
        assertEquals("+2.5 s", LinkDiagnostics.formatOffset(2_540))
        assertEquals("-1.2 s", LinkDiagnostics.formatOffset(-1_250))
    }

    @Test
    fun `precise timing is offered only when standalone waiting with approximate wakes`() {
        val approximate = Status(phase = Phase.WAITING, preciseTiming = false)

        assertTrue(LinkDiagnostics.shouldOfferPreciseTiming(LinkMode.STANDALONE, approximate))
        assertFalse(LinkDiagnostics.shouldOfferPreciseTiming(LinkMode.TETHERED, approximate))
        assertFalse(
            LinkDiagnostics.shouldOfferPreciseTiming(
                LinkMode.STANDALONE,
                approximate.copy(preciseTiming = true)
            )
        )
        assertFalse(
            LinkDiagnostics.shouldOfferPreciseTiming(
                LinkMode.STANDALONE,
                approximate.copy(phase = Phase.FOREGROUND)
            )
        )
    }
}
