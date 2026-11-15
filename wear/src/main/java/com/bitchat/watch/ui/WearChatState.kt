package com.bitchat.watch.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide UI state for the watch app: unread DM counters and the currently open DM thread.
 */
object WearChatState {
    /**
     * One key per conversation: a favorite's chat is stored under its contact ID while the
     * people list shows its mesh ID, and both must count as the same thread.
     */
    fun conversationKey(peerID: String): String = try {
        com.bitchat.android.services.ContactDirectory.canonicalConversationId(peerID).lowercase()
    } catch (_: Exception) {
        peerID.lowercase()
    }

    /** Whether [peerID] is the thread on screen now. */
    fun isOpen(peerID: String): Boolean =
        appInForeground && openDmPeer?.let { conversationKey(it) == conversationKey(peerID) } == true

    private val _unreadDms = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadDms: StateFlow<Map<String, Int>> = _unreadDms.asStateFlow()

    @Volatile
    var appInForeground: Boolean = false
        private set

    @Volatile
    var openDmPeer: String? = null

    @Synchronized
    fun onPrivateMessageArrived(peerID: String) {
        if (isOpen(peerID)) return
        val key = conversationKey(peerID)
        _unreadDms.value = _unreadDms.value + (key to ((_unreadDms.value[key] ?: 0) + 1))
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
        _unreadDms.value = _unreadDms.value - conversationKey(peerID)
        onDmShown?.invoke(peerID)
    }

    /** Mirror the linked phone's unread count for a conversation (unless it is open here). */
    @Synchronized
    fun setUnread(peerID: String, count: Int) {
        if (isOpen(peerID)) return
        val key = conversationKey(peerID)
        _unreadDms.value = if (count <= 0) _unreadDms.value - key else _unreadDms.value + (key to count)
    }

    @Synchronized
    fun closeDm() {
        openDmPeer = null
    }

    fun unreadCount(peerID: String): Int = _unreadDms.value[conversationKey(peerID)] ?: 0
}
