package com.bitchat.watch.link

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.util.Log
import java.util.Locale

/**
 * Makes the watch app use the phone app's language. The phone sends its in-app language tag
 * (empty when it follows the phone's system language) whenever a watch is nearby.
 *
 * Only a change on the phone is applied, so a language the wearer picks in the watch's own
 * settings stays until the phone app's language changes again.
 */
object PhoneLanguageSync {
    private const val TAG = "PhoneLanguageSync"
    private const val PREFS = "murmur_language"
    private const val KEY_LAST_PHONE_TAG = "last_phone_tag"

    /** Languages the watch app is translated into. */
    val SUPPORTED = setOf("en", "ar")

    /** The watch locale for a phone language tag: a supported language, or empty to follow the watch. */
    fun watchTagFor(phoneTag: String): String {
        val first = phoneTag.split(',').firstOrNull()?.trim().orEmpty()
        if (first.isEmpty()) return ""
        val language = Locale.forLanguageTag(first).language
        return if (language in SUPPORTED) language else ""
    }

    fun onPhoneLanguage(context: Context, data: ByteArray) {
        val phoneTag = String(data, Charsets.UTF_8).take(64)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_LAST_PHONE_TAG) && prefs.getString(KEY_LAST_PHONE_TAG, null) == phoneTag) return
        prefs.edit().putString(KEY_LAST_PHONE_TAG, phoneTag).apply()
        val target = watchTagFor(phoneTag)
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        if (manager.applicationLocales.toLanguageTags() == target) return
        Log.i(TAG, "Phone app language changed; watch now uses '${target.ifEmpty { "system" }}'")
        manager.applicationLocales = if (target.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(target)
    }
}
