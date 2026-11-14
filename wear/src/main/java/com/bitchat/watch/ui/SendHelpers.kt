package com.bitchat.watch.ui

import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.mesh.WearMeshService
import java.io.File
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun sendPublicMessage(mesh: WearMeshService, content: String) {
    val message = BitchatMessage(
        sender = mesh.nickname,
        content = content,
        timestamp = Date(),
        senderPeerID = mesh.myPeerID,
        deliveryStatus = DeliveryStatus.Sent
    )
    if (mesh.isMirrored) {
        // Linked and next to the phone: the phone sends it, and shows it too.
        val handed = mesh.sendPublicViaPhone(message.id, content)
        AppStateStore.addPublicMessage(
            if (handed) message else message.copy(deliveryStatus = DeliveryStatus.Failed("Phone not reachable"))
        )
        return
    }
    mesh.sendMessage(content)
    AppStateStore.addPublicMessage(message)
}

/**
 * DM send with honest delivery state. MeshCore drops pre-handshake content (it only kicks
 * off the Noise handshake), so when no session exists we must not echo "Sent": the echo
 * stays "Sending" while a retry loop waits for the session and completes the send.
 */
internal fun sendPrivateMessage(
    mesh: WearMeshService,
    peerID: String,
    recipientNickname: String,
    content: String,
    scope: CoroutineScope
) {
    val messageID = java.util.UUID.randomUUID().toString()
    if (mesh.isMirrored) {
        // Linked and next to the phone: the phone routes it (mesh, internet, or its own queue).
        val handed = mesh.sendPrivateViaPhone(messageID, peerID, recipientNickname, content)
        AppStateStore.addPrivateMessage(
            peerID,
            BitchatMessage(
                id = messageID,
                sender = mesh.nickname,
                content = content,
                timestamp = Date(),
                isPrivate = true,
                recipientNickname = recipientNickname,
                senderPeerID = mesh.myPeerID,
                deliveryStatus = if (handed) DeliveryStatus.Sending else DeliveryStatus.Failed("Phone not reachable")
            ),
            forceRead = true
        )
        return
    }
    val established = mesh.hasEstablishedSession(peerID)
    if (established) {
        mesh.sendPrivateMessageWithId(content, peerID, recipientNickname, messageID)
    } else {
        mesh.initiateNoiseHandshake(peerID)
        scope.launch {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                if (mesh.hasEstablishedSession(peerID)) {
                    mesh.sendPrivateMessageWithId(content, peerID, recipientNickname, messageID)
                    AppStateStore.updatePrivateMessageStatus(messageID, DeliveryStatus.Sent)
                    return@launch
                }
                delay(400)
            }
            // Session never came up: the echo honestly stays "Sending" (AppStateStore
            // refuses status downgrades, so it cannot be marked Failed from here).
        }
    }
    AppStateStore.addPrivateMessage(
        peerID,
        BitchatMessage(
            id = messageID,
            sender = mesh.nickname,
            content = content,
            timestamp = Date(),
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = mesh.myPeerID,
            deliveryStatus = if (established) DeliveryStatus.Sent else DeliveryStatus.Sending
        )
    )
    // Resent later if no delivery receipt arrives (link down, recipient out of range).
    mesh.trackOutgoingPrivate(messageID, peerID, recipientNickname, content)
}

/**
 * Send a recorded voice note. Global chat: broadcast file packet. DM thread: Noise-encrypted
 * private file transfer. Local echo renders immediately (content = local path, type = Audio).
 */
internal fun sendVoiceNote(mesh: WearMeshService, peerID: String?, path: String) {
    val file = File(path)
    if (!file.isFile) return
    // Voice notes are not relayed through the phone in linked mode yet.
    val unsupported = mesh.isMirrored
    val packet = BitchatFilePacket(
        fileName = file.name,
        fileSize = file.length(),
        mimeType = "audio/mp4",
        content = file.readBytes()
    )
    if (!unsupported) {
        if (peerID == null) {
            mesh.sendFileBroadcast(packet)
        } else {
            mesh.sendFilePrivateEncrypted(peerID, packet)
        }
    }
    val echo = BitchatMessage(
        sender = mesh.nickname,
        content = path,
        type = BitchatMessageType.Audio,
        timestamp = Date(),
        isPrivate = peerID != null,
        senderPeerID = mesh.myPeerID,
        deliveryStatus = if (unsupported) {
            DeliveryStatus.Failed("Voice notes need the watch away from the phone for now")
        } else {
            DeliveryStatus.Sent
        }
    )
    if (peerID == null) {
        AppStateStore.addPublicMessage(echo)
    } else {
        AppStateStore.addPrivateMessage(peerID, echo)
    }
}
