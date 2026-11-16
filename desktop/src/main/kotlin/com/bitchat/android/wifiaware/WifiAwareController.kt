package com.bitchat.android.wifiaware

/**
 * Desktop variant (same FQN) of the phone's WifiAwareController. Wi-Fi Aware (NAN) is an
 * Android-only radio API; the shared DebugSettingsManager toggle is a no-op on desktop.
 */
object WifiAwareController {
    @Suppress("UNUSED_PARAMETER")
    fun setEnabled(value: Boolean) = Unit
}
