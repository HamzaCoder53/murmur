package com.bitchat.desktop.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.desktop.platform.Notifier
import com.bitchat.desktop.platform.TrayController

/**
 * Turns incoming private messages into OS notifications and the tray unread badge.
 *
 * - The app is not in front (window hidden in the tray, or not focused): notify and count.
 * - The app is in front but another tab is open: count only.
 * - The app is in front on the DM tab: nothing; the user is reading.
 *
 * Notifications carry only the sender's nickname, never message text.
 */
class PrivateMessageAlerts(
    private val notifier: Notifier,
    private val tray: TrayController,
) {
    private val lock = Any()
    private var unread = 0
    private var appInFront = false
    private var viewingDms = false

    val unreadCount: Int get() = synchronized(lock) { unread }

    fun onIncoming(message: BitchatMessage, senderLabel: String) {
        val (notify, count) = synchronized(lock) {
            if (appInFront && viewingDms) return
            unread++
            !appInFront to unread
        }
        tray.setUnreadCount(count)
        if (notify) notifier.notify(message.id, "Skein", "New private message from $senderLabel")
    }

    /** The window became visible and focused, or stopped being so. */
    fun setAppInFront(inFront: Boolean) = update { appInFront = inFront }

    /** The DM tab was opened or left. */
    fun setViewingDms(viewing: Boolean) = update { viewingDms = viewing }

    private fun update(change: () -> Unit) {
        val cleared = synchronized(lock) {
            change()
            if (appInFront && viewingDms && unread > 0) {
                unread = 0
                true
            } else {
                false
            }
        }
        if (cleared) tray.setUnreadCount(0)
    }
}
