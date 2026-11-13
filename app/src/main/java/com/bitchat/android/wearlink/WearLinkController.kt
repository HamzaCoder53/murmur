package com.bitchat.android.wearlink

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Owns the phone's [WearLinkMeshService], the way WifiAwareController owns the Wi-Fi Aware
 * service. The link only exists on phones with Google Play services, which is what carries the
 * Wearable Data Layer; everywhere else every call here is a no-op.
 */
object WearLinkController {
    private const val TAG = "WearLinkController"

    @Volatile
    private var service: WearLinkMeshService? = null

    fun getService(): WearLinkMeshService? = service

    @Synchronized
    fun startIfPossible(context: Context) {
        if (service?.isRunning() == true) return
        val appContext = context.applicationContext
        if (!hasPlayServices(appContext)) {
            Log.i(TAG, "Google Play services unavailable; watch link disabled")
            return
        }
        try {
            // A stopped service is single-use, so always start from a fresh instance.
            service = WearLinkMeshService(appContext).also { it.startServices() }
        } catch (t: Throwable) {
            // Includes linkage errors on builds or devices missing the Wearable client classes.
            Log.w(TAG, "Watch link failed to start: ${t.message}")
            service = null
        }
    }

    @Synchronized
    fun stop() {
        val stopped = service ?: return
        service = null
        try {
            stopped.stopServices()
        } catch (e: Exception) {
            Log.w(TAG, "Watch link failed to stop cleanly: ${e.message}")
        }
    }

    private fun hasPlayServices(context: Context): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS
    } catch (_: Throwable) {
        false
    }
}
