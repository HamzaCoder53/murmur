package com.bitchat.watch.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkModeControllerTest {

    private val reachable = MutableStateFlow(false)
    private val changes = ArrayList<LinkMode>()

    private fun TestScope.controller() = LinkModeController(
        scope = backgroundScope,
        phoneReachable = reachable,
        onModeChanged = { changes += it },
        tetherDelayMs = 2_000,
        untetherDelayMs = 10_000
    ).also {
        it.start()
        runCurrent()
    }

    @Test
    fun `starts standalone without announcing a change`() = runTest {
        val controller = controller()

        assertEquals(LinkMode.STANDALONE, controller.mode.value)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `a phone that stays reachable tethers after the delay`() = runTest {
        val controller = controller()

        reachable.value = true
        advanceTimeBy(1_999)
        assertEquals(LinkMode.STANDALONE, controller.mode.value)
        advanceTimeBy(2)

        assertEquals(LinkMode.TETHERED, controller.mode.value)
        assertEquals(listOf(LinkMode.TETHERED), changes)
    }

    @Test
    fun `a phone that vanishes before the delay never tethers`() = runTest {
        val controller = controller()

        reachable.value = true
        advanceTimeBy(1_000)
        reachable.value = false
        advanceTimeBy(30_000)

        assertEquals(LinkMode.STANDALONE, controller.mode.value)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `a brief dropout while tethered does not start the watch radio`() = runTest {
        val controller = controller()
        reachable.value = true
        advanceTimeBy(2_001)

        reachable.value = false
        advanceTimeBy(9_000)
        reachable.value = true
        advanceTimeBy(30_000)

        assertEquals(LinkMode.TETHERED, controller.mode.value)
        assertEquals(listOf(LinkMode.TETHERED), changes)
    }

    @Test
    fun `losing the phone for the full delay goes standalone`() = runTest {
        val controller = controller()
        reachable.value = true
        advanceTimeBy(2_001)

        reachable.value = false
        advanceTimeBy(10_001)

        assertEquals(LinkMode.STANDALONE, controller.mode.value)
        assertEquals(listOf(LinkMode.TETHERED, LinkMode.STANDALONE), changes)
    }

    @Test
    fun `stop reports standalone and ignores later reachability`() = runTest {
        val controller = controller()
        reachable.value = true
        advanceTimeBy(2_001)

        controller.stop()
        reachable.value = false
        advanceTimeBy(30_000)

        assertEquals(LinkMode.STANDALONE, controller.mode.value)
        assertEquals(listOf(LinkMode.TETHERED), changes)
    }
}
