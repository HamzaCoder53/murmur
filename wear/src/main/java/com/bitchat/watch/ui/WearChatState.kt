package com.bitchat.watch.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide UI state for the watch app: unread DM counters and the currently open DM thread.
 */
object WearChatState {
    private val _unreadDms = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadDms: StateFlow<Map<String, Int>> = _unreadDms.asStateFlow()

    @Volatile
    var appInForeground: Boolean = false
        private set

    @Volatile
    var openDmPeer: String? = null

    @Synchronized
    fun onPrivateMessageArrived(peerID: String) {
        if (appInForeground && openDmPeer == peerID) return
        _unreadDms.value = _unreadDms.value + (peerID to ((_unreadDms.value[peerID] ?: 0) + 1))
    }

    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
    }

    /** Told when a DM thread is shown, so the mesh service can mark it read and send receipts. */
    @Volatile
    var onDmShown: ((String) -> Unit)? = null

    @Synchronized
    fun openDm(peerID: String) {
        openDmPeer = peerID
        _unreadDms.value = _unreadDms.value - peerID
        onDmShown?.invoke(peerID)
    }

    /** Mirror the linked phone's unread count for a conversation (unless it is open here). */
    @Synchronized
    fun setUnread(peerID: String, count: Int) {
        if (appInForeground && openDmPeer == peerID) return
        _unreadDms.value = if (count <= 0) _unreadDms.value - peerID else _unreadDms.value + (peerID to count)
    }

    @Synchronized
    fun closeDm() {
        openDmPeer = null
    }

    fun unreadCount(peerID: String): Int = _unreadDms.value[peerID] ?: 0
}
