package com.bitchat.android.mesh

import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How loudly this device advertises over Bluetooth (Skein addition, shared by phone and watch).
 *
 * - The wearer picks a level, or Auto, which follows the battery-aware power profile.
 * - Every [BEACON_PERIOD_MS] the device advertises at full power for [BEACON_LENGTH_MS], so
 *   people at the edge of range can still find it now and then.
 * - In Auto, a weak link to someone already connected (signal below [WEAK_RSSI_DBM]) raises the
 *   level to full until the signal has been good again for [WEAK_HOLD_MS], so a conversation
 *   between two people far apart does not drop.
 *
 * Only the advertising signal is set here. Once connected, Android gives apps no control over
 * transmit power; the Bluetooth chip manages that itself.
 */
object RadioPower {
    enum class Setting { AUTO, LOW, MEDIUM, HIGH }

    const val BEACON_PERIOD_MS = 5 * 60_000L
    const val BEACON_LENGTH_MS = 10_000L
    const val WEAK_RSSI_DBM = -85
    const val STRONG_RSSI_DBM = -75
    const val WEAK_HOLD_MS = 2 * 60_000L

    private const val PREFS = "murmur_radio"
    private const val KEY_SETTING = "advertise_power"

    private val _setting = MutableStateFlow(Setting.AUTO)
    val setting: StateFlow<Setting> = _setting.asStateFlow()

    /** Inputs that change the level; advertising restarts when this changes. */
    data class State(val setting: Setting, val beacon: Boolean, val weakLink: Boolean)

    private val _state = MutableStateFlow(State(Setting.AUTO, beacon = false, weakLink = false))
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var loaded = false
    private var lastWeakMs = 0L

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val stored = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SETTING, null)
        val value = Setting.entries.firstOrNull { it.name == stored } ?: Setting.AUTO
        _setting.value = value
        _state.value = _state.value.copy(setting = value)
    }

    fun set(context: Context, value: Setting) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SETTING, value.name).apply()
        loaded = true
        _setting.value = value
        _state.value = _state.value.copy(setting = value)
    }

    fun setBeacon(on: Boolean) {
        _state.value = _state.value.copy(beacon = on)
    }

    /**
     * Report the weakest signal among directly connected devices (null when none is known).
     * Weak below [WEAK_RSSI_DBM]; back to normal only after [WEAK_HOLD_MS] without a weak
     * reading and once the weakest link is at least [STRONG_RSSI_DBM] (or gone).
     */
    @Synchronized
    fun onWeakestLink(rssi: Int?, nowMs: Long) {
        val current = _state.value.weakLink
        val next = nextWeakLink(current, rssi, nowMs, lastWeakMs)
        if (rssi != null && rssi < WEAK_RSSI_DBM) lastWeakMs = nowMs
        if (next != current) _state.value = _state.value.copy(weakLink = next)
    }

    internal fun nextWeakLink(current: Boolean, rssi: Int?, nowMs: Long, lastWeakMs: Long): Boolean = when {
        rssi != null && rssi < WEAK_RSSI_DBM -> true
        !current -> false
        nowMs - lastWeakMs < WEAK_HOLD_MS -> true
        rssi == null || rssi >= STRONG_RSSI_DBM -> false
        else -> true
    }

    /** The AdvertiseSettings TX level to use, given the power profile's own choice for Auto. */
    fun txLevel(state: State = _state.value, profileLevel: Int): Int = when {
        state.beacon -> AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
        state.setting == Setting.AUTO && state.weakLink -> AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
        state.setting == Setting.AUTO -> profileLevel
        state.setting == Setting.LOW -> AdvertiseSettings.ADVERTISE_TX_POWER_LOW
        state.setting == Setting.MEDIUM -> AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
        else -> AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
    }
}
