package com.bitchat.android.services

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop variant (same FQN) of the "Save message history" switch. It keeps the phone's
 * preference file and key so a later stage can attach real conversation persistence, but this
 * stage stores private messages in memory only (see AppStateStore), so there is nothing to
 * attach or erase yet.
 */
object MessageHistory {
    private const val PREFS_NAME = "murmur_message_history"
    private const val KEY_SAVE_PRIVATE = "save_private_history"
    const val DEFAULT_ENABLED = true

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SAVE_PRIVATE, DEFAULT_ENABLED)

    fun initialize(context: Context) {
        _enabled.value = isEnabled(context)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled == _enabled.value) return
        prefs(context).edit().putBoolean(KEY_SAVE_PRIVATE, enabled).apply()
        _enabled.value = enabled
        if (!enabled) com.bitchat.android.sync.InternetSyncBridge.onSyncDisabled()
    }
}
