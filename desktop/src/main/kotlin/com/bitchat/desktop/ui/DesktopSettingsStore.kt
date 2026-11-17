package com.bitchat.desktop.ui

import android.content.Context
import java.io.File

/**
 * Persisted desktop UI settings (non-secret, so a plain preference file under the data dir).
 * OS-owned state (start at login, crash-report opt-in) is read back from its controller instead
 * of being mirrored here, so the UI never shows a value the OS did not accept.
 */
class DesktopSettingsStore(dataDir: File) {
    private val prefs = Context(dataDir).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Saved(
        val language: AppLanguage = AppLanguage.ENGLISH,
        val showInDock: Boolean = true,
        val autostartPrompted: Boolean = false,
    )

    fun load(): Saved = Saved(
        language = AppLanguage.entries.firstOrNull { it.code == prefs.getString(KEY_LANGUAGE, null) }
            ?: AppLanguage.ENGLISH,
        showInDock = prefs.getBoolean(KEY_SHOW_IN_DOCK, true),
        autostartPrompted = prefs.getBoolean(KEY_AUTOSTART_PROMPTED, false),
    )

    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.code).commit()
    }

    fun setShowInDock(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_IN_DOCK, show).commit()
    }

    fun setAutostartPrompted() {
        prefs.edit().putBoolean(KEY_AUTOSTART_PROMPTED, true).commit()
    }

    private companion object {
        const val PREFS_NAME = "skein_desktop_settings"
        const val KEY_LANGUAGE = "language"
        const val KEY_SHOW_IN_DOCK = "show_in_dock"
        const val KEY_AUTOSTART_PROMPTED = "autostart_prompted"
    }
}
