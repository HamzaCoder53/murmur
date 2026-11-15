package com.bitchat.android.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The "Save message history" switch, shared by the phone and the watch.
 *
 * On (the default): private conversations are written to the encrypted on-device database and
 * restored when the app starts. Off: nothing is written, and the saved history on this device is
 * erased. Public chat is never saved either way.
 */
object MessageHistory {
    private const val TAG = "MessageHistory"
    private const val PREFS_NAME = "murmur_message_history"
    private const val KEY_SAVE_PRIVATE = "save_private_history"
    const val DEFAULT_ENABLED = true
    private const val SECOND_ERASE_DELAY_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SAVE_PRIVATE, DEFAULT_ENABLED)

    /** Call once at app start, before transports can deliver messages. */
    fun initialize(context: Context) {
        val enabled = isEnabled(context)
        _enabled.value = enabled
        if (enabled) {
            AppStateStore.initializeConversationPersistence(context)
        } else {
            AppStateStore.detachConversationPersistence()
            // History was switched off earlier. Erase again in case that wipe never finished.
            scope.launch { erase(context) }
        }
    }

    /**
     * Turning it on starts saving from now on; turning it off erases everything saved on this
     * device. Messages already on screen stay until the app closes either way.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled == _enabled.value) return
        prefs(context).edit().putBoolean(KEY_SAVE_PRIVATE, enabled).apply()
        _enabled.value = enabled
        if (enabled) {
            AppStateStore.initializeConversationPersistence(context)
        } else {
            AppStateStore.detachConversationPersistence()
            scope.launch { erase(context) }
        }
    }

    private suspend fun erase(context: Context) {
        eraseOnce(context)
        // A write that was already under way when history was switched off can land after the
        // first wipe. Wipe once more shortly after, unless history was switched back on.
        kotlinx.coroutines.delay(SECOND_ERASE_DELAY_MS)
        if (!_enabled.value) eraseOnce(context)
    }

    private suspend fun eraseOnce(context: Context) {
        try {
            val cleared = ConversationRepository.getInstance(context.applicationContext).clearAllAndWait()
            if (!cleared) Log.w(TAG, "Saved history could not be fully erased")
        } catch (e: Exception) {
            Log.w(TAG, "Saved history could not be erased: ${e.message}")
        }
    }
}
