package com.bitchat.android.lan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitchat.android.ui.debug.DebugPreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the phone's [LanMeshService], the way WifiAwareController and WearLinkController own theirs.
 *
 * Off by default. The service runs only while the user's toggle is on and, on Android 17 and
 * later, the local network permission is granted; otherwise every call here is a no-op.
 */
object LanController {
    private const val TAG = "LanController"

    /** API 37 == Android 17, which enforces local network access for apps targeting it. */
    private const val LOCAL_NETWORK_PERMISSION_SDK = 37

    // Holds only the application context (startIfPossible passes applicationContext).
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var service: LanMeshService? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun getService(): LanMeshService? = service

    /** Loads the persisted toggle. Does not start anything; the mesh service does that. */
    fun initialize() {
        _enabled.value = persistedEnabled()
    }

    private fun persistedEnabled(): Boolean =
        try { DebugPreferenceManager.getLanMeshEnabled(_enabled.value) } catch (_: Exception) { _enabled.value }

    /** Runtime permissions the feature needs on this device; empty before Android 17. */
    fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_PERMISSION_SDK) {
            listOf(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            emptyList()
        }

    fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Persists the toggle and starts or stops the transport to match. */
    fun setEnabled(context: Context, value: Boolean) {
        try { DebugPreferenceManager.setLanMeshEnabled(value) } catch (_: Exception) { }
        _enabled.value = value
        if (value) startIfPossible(context) else stop()
    }

    @Synchronized
    fun startIfPossible(context: Context) {
        _enabled.value = persistedEnabled()
        if (!_enabled.value) return
        if (service?.isRunning() == true) return
        val app = context.applicationContext
        if (!hasPermissions(app)) {
            Log.i(TAG, "Local network permission not granted; local Wi-Fi mesh stays off")
            return
        }
        try {
            // A stopped service is single-use, so always start from a fresh instance.
            service = LanMeshService(app).also { it.startServices() }
            _running.value = true
        } catch (t: Throwable) {
            Log.w(TAG, "Local Wi-Fi mesh failed to start: ${t.message}")
            service = null
            _running.value = false
        }
    }

    @Synchronized
    fun stop() {
        val stopped = service ?: return
        service = null
        _running.value = false
        try {
            stopped.stopServices()
        } catch (e: Exception) {
            Log.w(TAG, "Local Wi-Fi mesh failed to stop cleanly: ${e.message}")
        }
    }
}
