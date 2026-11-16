package com.bitchat.android.sync

import android.content.Context
import com.bitchat.android.services.MessageHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Preference store for "Sync over internet".
 *
 * Default is OFF. When [MessageHistory] is disabled, internet sync is effectively disabled as well.
 */
object InternetSyncPreferences {
    private const val PREFS_NAME = "murmur_internet_sync"
    private const val KEY_ENABLED = "internet_sync_enabled"
    private const val KEY_DEVICE_ID = "sync_device_id"
    const val DEFAULT_ENABLED = false

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var cachedDeviceId: String? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun initialize(context: Context) {
        _enabled.value = isEnabled(context)
        getOrCreateDeviceId(context)
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun isEffectivelyEnabled(context: Context): Boolean =
        isEnabled(context) && MessageHistory.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
        if (!enabled) {
            InternetSyncBridge.onSyncDisabled()
        }
    }

    fun getOrCreateDeviceId(context: Context): String {
        cachedDeviceId?.let { return it }
        synchronized(this) {
            cachedDeviceId?.let { return it }
            val sp = prefs(context)
            val existing = sp.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
            if (existing != null) {
                cachedDeviceId = existing
                return existing
            }
            val generated = UUID.randomUUID().toString()
            sp.edit().putString(KEY_DEVICE_ID, generated).apply()
            cachedDeviceId = generated
            return generated
        }
    }

    internal fun resetForTesting() {
        _enabled.value = DEFAULT_ENABLED
        cachedDeviceId = null
    }
}
