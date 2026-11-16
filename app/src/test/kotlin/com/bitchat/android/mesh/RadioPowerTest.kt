package com.bitchat.android.mesh

import android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
import android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_LOW
import android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
import android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW
import com.bitchat.android.mesh.RadioPower.Setting
import com.bitchat.android.mesh.RadioPower.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPowerTest {

    private fun level(setting: Setting, beacon: Boolean = false, weak: Boolean = false, profile: Int = ADVERTISE_TX_POWER_ULTRA_LOW) =
        RadioPower.txLevel(State(setting, beacon, weak), profile)

    @Test
    fun `auto follows the power profile`() {
        assertEquals(ADVERTISE_TX_POWER_ULTRA_LOW, level(Setting.AUTO))
        assertEquals(ADVERTISE_TX_POWER_MEDIUM, level(Setting.AUTO, profile = ADVERTISE_TX_POWER_MEDIUM))
    }

    @Test
    fun `a chosen level is used as is`() {
        assertEquals(ADVERTISE_TX_POWER_LOW, level(Setting.LOW))
        assertEquals(ADVERTISE_TX_POWER_MEDIUM, level(Setting.MEDIUM))
        assertEquals(ADVERTISE_TX_POWER_HIGH, level(Setting.HIGH))
    }

    @Test
    fun `the five-minute beacon is full power whatever the setting`() {
        Setting.entries.forEach { assertEquals(ADVERTISE_TX_POWER_HIGH, level(it, beacon = true)) }
    }

    @Test
    fun `a weak link boosts auto but not a chosen level`() {
        assertEquals(ADVERTISE_TX_POWER_HIGH, level(Setting.AUTO, weak = true))
        assertEquals(ADVERTISE_TX_POWER_LOW, level(Setting.LOW, weak = true))
    }

    @Test
    fun `weak link turns on below the threshold and holds for a while`() {
        val t = 1_000_000L
        assertTrue(RadioPower.nextWeakLink(false, -90, t, 0L))
        assertFalse(RadioPower.nextWeakLink(false, -80, t, 0L))
        // Strong again, but still inside the hold time.
        assertTrue(RadioPower.nextWeakLink(true, -60, t + 30_000, t))
        // Hold time over and strong: back to normal.
        assertFalse(RadioPower.nextWeakLink(true, -60, t + RadioPower.WEAK_HOLD_MS + 1, t))
        // Hold time over but only middling: stay boosted.
        assertTrue(RadioPower.nextWeakLink(true, -80, t + RadioPower.WEAK_HOLD_MS + 1, t))
        // Nobody connected any more: back to normal after the hold.
        assertFalse(RadioPower.nextWeakLink(true, null, t + RadioPower.WEAK_HOLD_MS + 1, t))
    }
}
