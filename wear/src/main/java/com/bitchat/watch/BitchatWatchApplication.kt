package com.bitchat.watch

import android.app.Application
import com.bitchat.android.mesh.PowerManager
import com.bitchat.android.services.MessageHistory
import com.bitchat.watch.notification.WearNotificationCoordinator
import com.bitchat.watch.ui.WearPeerIdentityState

class BitchatWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PowerManager.getInstance(applicationContext)
        // Restore saved private chats (if "Save message history" is on) before the mesh starts.
        try { MessageHistory.initialize(applicationContext) } catch (_: Exception) { }
        WearNotificationCoordinator.getInstance(applicationContext)
        WearPeerIdentityState.initialize(applicationContext)
    }
}
