package com.bitchat.watch.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.bitchat.watch.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How a new private message announces itself on the watch: vibrate, sound, both or neither,
 * with Skein's own "stitch" vibration and "thread pluck" sound.
 *
 * Android fixes a channel's sound and vibration once it is created, so each style has its own
 * channel and switching style moves to that channel (and removes the others).
 */
object AlertSettings {
    enum class Style(@StringRes val labelRes: Int, val vibrate: Boolean, val sound: Boolean) {
        BOTH(R.string.alerts_both, vibrate = true, sound = true),
        VIBRATE(R.string.alerts_vibrate, vibrate = true, sound = false),
        SOUND(R.string.alerts_sound, vibrate = false, sound = true),
        SILENT(R.string.alerts_silent, vibrate = false, sound = false)
    }

    /** Two quick taps and a longer pull, like a stitch being drawn tight. */
    val STITCH_PATTERN = longArrayOf(0, 35, 70, 35, 110, 140)

    val DEFAULT = Style.VIBRATE

    private const val PREFS = "murmur_alerts"
    private const val KEY_STYLE = "style"
    private const val CHANNEL_PREFIX = "skein_messages_"
    private const val LEGACY_CHANNEL_ID = "bitchat_watch_messages"

    private val _style = MutableStateFlow(DEFAULT)
    val style: StateFlow<Style> = _style.asStateFlow()

    @Volatile
    private var loaded = false

    fun channelId(style: Style): String = CHANNEL_PREFIX + style.name.lowercase()

    fun soundUri(context: Context): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath("raw")
        .appendPath("skein_message")
        .build()

    fun current(context: Context): Style {
        if (!loaded) {
            loaded = true
            val stored = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STYLE, null)
            _style.value = Style.entries.firstOrNull { it.name == stored } ?: DEFAULT
        }
        return _style.value
    }

    fun set(context: Context, value: Style) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STYLE, value.name).apply()
        loaded = true
        _style.value = value
        ensureChannel(context)
    }

    /** Make sure the channel for the chosen style exists and the others are gone. */
    fun ensureChannel(context: Context): String {
        val app = context.applicationContext
        val style = current(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        val id = channelId(style)
        val channel = NotificationChannel(
            id,
            app.getString(R.string.message_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = app.getString(R.string.message_channel_description)
            setShowBadge(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            enableVibration(style.vibrate)
            vibrationPattern = if (style.vibrate) STITCH_PATTERN else null
            setSound(
                if (style.sound) soundUri(app) else null,
                if (style.sound) {
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                } else {
                    null
                }
            )
        }
        manager.createNotificationChannel(channel)
        (Style.entries.map(::channelId) + LEGACY_CHANNEL_ID)
            .filter { it != id }
            .forEach { runCatching { manager.deleteNotificationChannel(it) } }
        return id
    }

    /** Let the wearer feel and hear a style when picking it. */
    fun preview(context: Context, style: Style) {
        val app = context.applicationContext
        if (style.vibrate) {
            runCatching {
                app.getSystemService(Vibrator::class.java)
                    ?.vibrate(VibrationEffect.createWaveform(STITCH_PATTERN, -1))
            }
        }
        if (style.sound) {
            runCatching { RingtoneManager.getRingtone(app, soundUri(app))?.play() }
        }
    }
}
