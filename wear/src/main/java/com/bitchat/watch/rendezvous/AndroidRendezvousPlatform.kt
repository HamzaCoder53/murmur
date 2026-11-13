package com.bitchat.watch.rendezvous

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Real-device backing for [RendezvousEngine.Platform]: AlarmManager wakes that survive Doze, and a
 * partial wake lock that keeps the CPU up for the length of one scan window.
 *
 * Exact wakes need the "Alarms & reminders" special access, which newer Android versions leave off
 * until the user grants it. Without it the wake is requested as inexact-while-idle: it still wakes
 * the watch, but possibly late, and the engine then scans a full-length window from whenever it
 * actually woke.
 */
class AndroidRendezvousPlatform(context: Context) : RendezvousEngine.Platform {

    private companion object {
        const val TAG = "RendezvousPlatform"
        const val WAKE_LOCK_TAG = "Murmur:RendezvousWindow"
        const val REQUEST_CODE = 5501
    }

    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val handler = Handler(Looper.getMainLooper())
    private val wakeLock: PowerManager.WakeLock =
        (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }

    private val wakeIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            Intent(appContext, RendezvousAlarmReceiver::class.java)
                .setAction(RendezvousAlarmReceiver.ACTION_WAKE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun canScheduleExactWakes(): Boolean = try {
        alarmManager.canScheduleExactAlarms()
    } catch (_: Exception) {
        false
    }

    override fun scheduleWake(wallTimeMs: Long, exact: Boolean) {
        try {
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wallTimeMs, wakeIntent)
                return
            }
        } catch (e: SecurityException) {
            // Access was revoked between the check and the call; fall through to inexact.
            Log.w(TAG, "Exact wake refused, using inexact: ${e.message}")
        }
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wallTimeMs, wakeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not schedule rendezvous wake: ${e.message}")
        }
    }

    override fun cancelWake() {
        try {
            alarmManager.cancel(wakeIntent)
        } catch (_: Exception) {
        }
    }

    override fun acquireWakeLock(timeoutMs: Long) {
        try {
            wakeLock.acquire(timeoutMs)
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock not acquired: ${e.message}")
        }
    }

    override fun releaseWakeLock() {
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (_: Exception) {
        }
    }

    override fun runAfter(delayMs: Long, action: () -> Unit): () -> Unit {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return { handler.removeCallbacks(runnable) }
    }

    override fun isAppInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}

/**
 * Receives the rendezvous wake and hands it to the running engine. The system holds a wake lock
 * for the duration of onReceive, and the engine takes its own before returning.
 */
class RendezvousAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_WAKE = "com.bitchat.watch.rendezvous.WAKE"

        /** Set by the mesh service while a standalone engine is running. */
        @Volatile
        var engine: RendezvousEngine? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKE) return
        // No engine means the process was restarted for this alarm; the sticky foreground service
        // re-arms the schedule when it comes back, so there is nothing to do here.
        engine?.onWake()
    }
}
