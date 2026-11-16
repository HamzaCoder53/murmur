package com.bitchat.android.sync

import android.content.Context
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.services.AppStateStore

/**
 * Process-wide bridge between shared [AppStateStore] hooks and the phone-only Nostr sync
 * publisher/receiver. Keeps [com.bitchat.android.sync] compilable in `:wear` without pulling in
 * phone-only Nostr relay dependencies.
 */
object InternetSyncBridge {
    @Volatile
    private var publisher: InternetSyncPublisher? = null

    @Volatile
    private var receiver: InternetSyncReceiver? = null

    fun initializePhone(
        context: Context,
        identityProvider: () -> NostrIdentity?,
        publishGiftWrap: (NostrEvent) -> Unit
    ) {
        val appContext = context.applicationContext
        InternetSyncPreferences.initialize(appContext)
        val enabledCheck = { InternetSyncPreferences.isEffectivelyEnabled(appContext) }
        val deviceId = { InternetSyncPreferences.getOrCreateDeviceId(appContext) }

        publisher = InternetSyncPublisher(
            isSyncEnabled = enabledCheck,
            deviceIdProvider = deviceId,
            identityProvider = identityProvider,
            publishGiftWrap = publishGiftWrap,
            myNicknameProvider = { AppStateStore.nickname.value }
        )

        receiver = InternetSyncReceiver(
            isSyncEnabled = enabledCheck,
            localDeviceIdProvider = deviceId,
            myNicknameProvider = { AppStateStore.nickname.value },
            hasSeenMessage = { AppStateStore.hasSeenMessage(it) },
            insertPrivateMessage = { conversationID, message, forceRead ->
                AppStateStore.addPrivateMessageDurably(
                    peerID = conversationID,
                    msg = message,
                    forceRead = forceRead,
                    syncToInternet = false
                )
            },
            markMessageRead = { messageID ->
                AppStateStore.markPrivateMessageRead(messageID, syncToInternet = false)
            },
            markConversationRead = { conversationID ->
                AppStateStore.setPrivateConversationRead(conversationID, true, syncToInternet = false)
            }
        )
    }

    fun onPrivateMessageAdded(peerID: String, message: BitchatMessage, isRead: Boolean) {
        publisher?.enqueueMessage(peerID, message, isRead)
    }

    fun onPrivateMessageRead(conversationID: String, messageID: String) {
        publisher?.enqueueMessageRead(conversationID, messageID)
    }

    fun onPrivateConversationRead(conversationID: String) {
        publisher?.enqueueConversationRead(conversationID)
    }

    fun onSyncDisabled() {
        publisher?.clearPending()
    }

    suspend fun onSyncPayload(
        payload: String,
        rumorTimestampSec: Int,
        senderPubkeyHex: String,
        ownPubkeyHex: String
    ): Boolean {
        return receiver?.onSyncPayload(
            payload = payload,
            rumorTimestampSec = rumorTimestampSec,
            senderPubkeyHex = senderPubkeyHex,
            ownPubkeyHex = ownPubkeyHex
        ) ?: false
    }
}
