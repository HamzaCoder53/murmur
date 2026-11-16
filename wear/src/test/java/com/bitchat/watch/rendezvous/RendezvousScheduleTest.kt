package com.bitchat.watch.rendezvous

import com.bitchat.watch.rendezvous.RendezvousSchedule.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendezvousScheduleTest {

    private val minute = 60_000L
    private val base = 1_000 * minute // an exact minute boundary

    @Test
    fun `window is five seconds centred on the minute`() {
        assertEquals(Window(base + 57_500, base + 62_500), RendezvousSchedule.currentOrNextWindow(base + 30_000))
    }

    @Test
    fun `time inside the window before the boundary returns the open window`() {
        val window = RendezvousSchedule.currentOrNextWindow(base + 58_000)

        assertEquals(base + 57_500, window.openMs)
        assertTrue(window.contains(base + 58_000))
    }

    @Test
    fun `time inside the window after the boundary returns the same window`() {
        assertEquals(Window(base - 2_500, base + 2_500), RendezvousSchedule.currentOrNextWindow(base + 1_000))
    }

    @Test
    fun `less than a second left skips to the next minute instead of a sliver`() {
        assertEquals(Window(base + 57_500, base + 62_500), RendezvousSchedule.currentOrNextWindow(base + 1_800))
    }

    @Test
    fun `window end is exclusive so the close instant schedules the next minute`() {
        val window = RendezvousSchedule.currentOrNextWindow(base + 2_500)

        assertEquals(base + 57_500, window.openMs)
        assertFalse(window.contains(base + 62_500))
    }

    @Test
    fun `every minute has exactly one window`() {
        val opens = (0 until 5).map { RendezvousSchedule.currentOrNextWindow(base + it * minute + 10_000).openMs }

        assertEquals((0 until 5).map { base + it * minute + 57_500 }, opens)
    }

    @Test
    fun `works before the epoch-aligned minute too`() {
        assertEquals(Window(-2_500, 2_500), RendezvousSchedule.currentOrNextWindow(-1_000))
    }
}
