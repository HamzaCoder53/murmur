package com.bitchat.android.service

/**
 * Desktop variant (same FQN) of the phone's MeshServiceHolder, as on the watch.
 *
 * The shared DebugSettingsManager references this holder only to toggle the BLE transport from
 * the phone's debug UI. The real holder is typed against BluetoothMeshService, which desktop
 * does not compile; the desktop BLE helper (later stage) will implement [BleToggle].
 */
object MeshServiceHolder {
    interface BleToggle {
        fun setBleTransportEnabled(enabled: Boolean)
    }

    @Volatile
    var meshService: BleToggle? = null
}
