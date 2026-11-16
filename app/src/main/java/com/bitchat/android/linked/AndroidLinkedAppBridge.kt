package com.bitchat.android.linked

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.features.file.FileUtils
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.services.NicknameProvider
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.ui.DataManager
import com.bitchat.android.wearlink.IdentityBundle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.File
import java.util.Date

/**
 * Production [LinkedAppBridge] backed by [AppStateStore], [MeshServiceHolder], [MessageRouter],
 * [FavoritesPersistenceService], [ConversationRepository], and [IdentityBundle].
 */
class AndroidLinkedAppBridge(
    context: Context,
    private val onMirrorPeersChanged: (List<LinkPeer>?) -> Unit
) : LinkedAppBridge {

    companion object {
        private const val TAG = "AndroidLinkedAppBridge"
        private const val MIRROR_TRANSPORT_ID = "linked_mirror"
    }

    private val appContext = context.applicationContext
    private val mediaDir = File(appContext.filesDir, "linked_media").apply { mkdirs() }

    override val changes: Flow<Unit> = combine(
        AppStateStore.publicMessages,
        AppStateStore.privateMessages,
        AppStateStore.channelMessages,
        AppStateStore.peers
    ) { _, _, _, _ -> }

    override fun nickname(): String {
        val fromStore = AppStateStore.nickname.value
        if (fromStore.isNotBlank()) return fromStore
        val fromPrefs = try {
            DataManager(appContext).loadNickname()
        } catch (_: Exception) {
            null
        }
        return fromPrefs?.takeIf { it.isNotBlank() } ?: run {
            val mesh = runCatching { MeshServiceHolder.getUnifiedOrCreate(appContext) }.getOrNull()
            if (mesh != null) NicknameProvider.getNickname(appContext, mesh.myPeerID) else "anon"
        }
    }

    override fun setNickname(name: String) {
        val clean = name.trim().take(LinkProtocol.MAX_NICKNAME)
        if (clean.isEmpty()) return
        try { DataManager(appContext).saveNickname(clean) } catch (_: Exception) { }
        AppStateStore.setNickname(clean)
        try {
            MeshServiceHolder.getUnifiedOrCreate(appContext).sendBroadcastAnnounce()
        } catch (_: Exception) { }
    }

    override suspend fun entriesSince(since: Long): List<LinkEntry> =
        currentEntries().filter { it.message.timestamp.time >= since }

    override fun currentEntries(): List<LinkEntry> {
        val out = ArrayList<LinkEntry>()
        AppStateStore.publicMessages.value.forEach { msg ->
            out += LinkEntry(msg, conversation = null, read = true)
        }
        AppStateStore.channelMessages.value.values.forEach { list ->
            list.forEach { msg -> out += LinkEntry(msg, conversation = null, read = true) }
        }
        AppStateStore.privateMessages.value.forEach { (convId, list) ->
            list.forEach { msg ->
                out += LinkEntry(msg, conversation = convId, read = AppStateStore.isPrivateMessageRead(msg.id))
            }
        }
        return out
    }

    override suspend fun applyEntries(entries: List<LinkEntry>): List<String> {
        if (entries.isEmpty()) return emptyList()
        val known = currentEntries().associate { it.message.id to (it.message to it.read) }
        val plan = BacklogMerge.plan(known, entries)
        val missingMedia = ArrayList<String>()

        for (entry in plan.add) {
            val msg = entry.message
            if (msg.isPrivate) {
                val convId = entry.conversation
                    ?: ContactDirectory.canonicalConversationId(msg.senderPeerID ?: msg.sender)
                AppStateStore.addPrivateMessageDurably(convId, msg, forceRead = entry.read)
                if (entry.read) AppStateStore.markPrivateMessageRead(msg.id)
            } else if (!msg.channel.isNullOrBlank()) {
                AppStateStore.addChannelMessage(msg.channel, msg)
            } else {
                AppStateStore.addPublicMessage(msg)
            }
            if (msg.type != BitchatMessageType.Message && mediaFile(msg.id) == null) {
                missingMedia += msg.id
            }
        }

        for (entry in plan.update) {
            val status = entry.message.deliveryStatus ?: continue
            if (entry.message.isPrivate) {
                AppStateStore.updatePrivateMessageStatus(entry.message.id, status)
            }
        }

        for (id in plan.markRead) {
            AppStateStore.markPrivateMessageRead(id)
        }
        return missingMedia
    }

    override fun favorites(): List<LinkFavorite> =
        FavoritesPersistenceService.shared.getAllRelationships().map { rel ->
            LinkFavorite(
                noiseKeyHex = ContactIdentityResolver.noiseKeyHex(rel.peerNoisePublicKey),
                nickname = rel.peerNickname,
                isFavorite = rel.isFavorite,
                updatedAt = rel.lastUpdated.time
            )
        }

    override fun applyFavorites(favorites: List<LinkFavorite>) {
        if (favorites.isEmpty()) return
        val identityManager = SecureIdentityStateManager(appContext)
        val dataManager = DataManager(appContext)
        favorites.forEach { fav ->
            val keyBytes = decodeHex(fav.noiseKeyHex) ?: return@forEach
            FavoritesPersistenceService.shared.updateFavoriteStatus(keyBytes, fav.nickname, fav.isFavorite)
            val fingerprint = identityManager.generateFingerprint(keyBytes)
            if (fav.isFavorite) dataManager.addFavorite(fingerprint) else dataManager.removeFavorite(fingerprint)
        }
    }

    override fun peers(): List<LinkPeer> {
        val mesh = runCatching { MeshServiceHolder.getUnifiedOrCreate(appContext) }.getOrNull()
            ?: return emptyList()
        val me = mesh.myPeerID
        val nicknames = mesh.getPeerNicknames()
        return AppStateStore.peers.value.filter { it != me }.map { id ->
            val info = mesh.getPeerInfo(id)
            val noiseKey = info?.noisePublicKey
            val relationship = noiseKey?.let {
                runCatching { FavoritesPersistenceService.shared.getFavoriteStatus(it) }.getOrNull()
            }
            LinkPeer(
                peerID = id,
                nickname = info?.nickname?.takeIf { it.isNotBlank() } ?: nicknames[id] ?: id.take(8),
                connected = info?.isConnected == true,
                hasSession = mesh.hasEstablishedSession(id),
                noiseKeyHex = noiseKey?.let { ContactIdentityResolver.noiseKeyHex(it) },
                isFavorite = relationship?.isFavorite == true,
                theyFavoritedUs = relationship?.theyFavoritedUs == true
            )
        }
    }

    override fun applyMirrorPeers(peers: List<LinkPeer>?) {
        onMirrorPeersChanged(peers)
        if (peers == null) {
            AppStateStore.setTransportPeers(MIRROR_TRANSPORT_ID, emptyList())
            AppStateStore.setTransportDirectPeers(MIRROR_TRANSPORT_ID, emptyList())
        } else {
            val connectedIds = peers.filter { it.connected }.map { it.peerID }
            AppStateStore.setTransportPeers(MIRROR_TRANSPORT_ID, connectedIds)
            AppStateStore.setTransportDirectPeers(MIRROR_TRANSPORT_ID, connectedIds)
        }
    }

    override fun setRadiosSuppressed(suppressed: Boolean) {
        try {
            MeshServiceHolder.getUnifiedOrCreate(appContext).setRadiosSuppressed(suppressed)
        } catch (e: Exception) {
            Log.w(TAG, "setRadiosSuppressed($suppressed) failed: ${e.message}")
        }
    }

    override suspend fun execute(command: LinkCommand, media: ByteArray?) {
        val mesh = runCatching { MeshServiceHolder.getUnifiedOrCreate(appContext) }.getOrNull() ?: return
        val me = mesh.myPeerID
        val myNick = nickname()
        when (command) {
            is LinkCommand.SendPublic -> {
                if (command.messageID != null && !AppStateStore.hasSeenMessage(command.messageID)) {
                    val msg = BitchatMessage(
                        id = command.messageID,
                        sender = myNick,
                        content = command.content,
                        timestamp = Date(),
                        senderPeerID = me,
                        mentions = command.mentions.takeIf { it.isNotEmpty() },
                        channel = command.channel,
                        deliveryStatus = DeliveryStatus.Sent
                    )
                    if (!command.channel.isNullOrBlank()) {
                        AppStateStore.addChannelMessage(command.channel, msg)
                    } else {
                        AppStateStore.addPublicMessage(msg)
                    }
                }
                mesh.sendMessage(command.content, command.mentions, command.channel)
            }
            is LinkCommand.SendPrivate -> {
                val convId = ContactDirectory.canonicalConversationId(command.peerID)
                val msg = BitchatMessage(
                    id = command.messageID,
                    sender = myNick,
                    content = command.content,
                    timestamp = Date(),
                    isPrivate = true,
                    recipientNickname = command.recipientNickname,
                    senderPeerID = me,
                    deliveryStatus = DeliveryStatus.Sending
                )
                if (!AppStateStore.hasSeenMessage(command.messageID)) {
                    AppStateStore.addPrivateMessageDurably(convId, msg, forceRead = true)
                }
                val route = MessageRouter.getInstance(appContext, mesh).sendPrivate(
                    content = command.content,
                    toPeerID = convId,
                    recipientNickname = command.recipientNickname,
                    messageID = command.messageID
                )
                when (route) {
                    MessageRouter.RouteResult.QUEUED -> Unit
                    MessageRouter.RouteResult.DROPPED ->
                        AppStateStore.updatePrivateMessageStatus(command.messageID, DeliveryStatus.Failed("Not sent"))
                    else ->
                        AppStateStore.updatePrivateMessageStatus(command.messageID, DeliveryStatus.Sent)
                }
            }
            is LinkCommand.ReadReceipt -> {
                AppStateStore.markPrivateMessageRead(command.messageID)
                MessageRouter.getInstance(appContext, mesh).sendReadReceipt(
                    ReadReceipt(originalMessageID = command.messageID, readerPeerID = me),
                    command.peerID
                )
            }
            is LinkCommand.SetFavorite -> setFavorite(mesh, command.peerID, command.isFavorite)
            is LinkCommand.Announce -> setNickname(command.nickname)
            is LinkCommand.SendFile -> {
                val bytes = media ?: return
                val file = saveMediaFile(command.messageID, command.fileName, bytes)
                val packet = BitchatFilePacket(
                    fileName = file.name,
                    fileSize = file.length(),
                    mimeType = command.mimeType,
                    content = bytes
                )
                if (command.peerID.isNullOrBlank()) {
                    mesh.sendFileBroadcast(packet)
                } else {
                    mesh.sendFilePrivate(command.peerID, packet)
                }
            }
        }
    }

    private fun setFavorite(mesh: MeshService, peerID: String, isFavorite: Boolean) {
        val info = mesh.getPeerInfo(peerID)
        val contact = ContactDirectory.resolve(peerID)
        val noiseKey = info?.noisePublicKey ?: contact.noisePublicKey ?: return
        val nick = info?.nickname?.takeIf { it.isNotBlank() }
            ?: contact.displayName ?: mesh.getPeerNicknames()[peerID] ?: peerID.take(8)
        val fingerprint = SecureIdentityStateManager(appContext).generateFingerprint(noiseKey)
        val dataManager = DataManager(appContext)
        if (isFavorite) dataManager.addFavorite(fingerprint) else dataManager.removeFavorite(fingerprint)
        FavoritesPersistenceService.shared.updateFavoriteStatus(noiseKey, nick, isFavorite)
        try {
            MessageRouter.getInstance(appContext, mesh).sendFavoriteNotification(peerID, isFavorite)
        } catch (_: Exception) { }
    }

    override fun mediaFile(messageID: String): Pair<String, ByteArray>? {
        val msg = findMessage(messageID) ?: return null
        if (msg.type == BitchatMessageType.Message) return null
        val file = File(msg.content)
        if (!file.isFile || file.length() > LinkProtocol.MAX_MEDIA_BYTES) return null
        return try {
            file.name to file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun onMediaReceived(messageID: String, fileName: String, data: ByteArray) {
        val file = saveMediaFile(messageID, fileName, data)
        val existing = findMessage(messageID) ?: return
        val updated = existing.copy(content = file.absolutePath)
        if (updated.isPrivate) {
            val convId = ContactDirectory.canonicalConversationId(
                updated.senderPeerID ?: updated.recipientNickname ?: updated.sender
            )
            AppStateStore.addPrivateMessageDurably(convId, updated, forceRead = AppStateStore.isPrivateMessageRead(messageID))
        } else if (!updated.channel.isNullOrBlank()) {
            AppStateStore.addChannelMessage(updated.channel, updated)
        } else {
            AppStateStore.addPublicMessage(updated)
        }
    }

    override fun identityBundle(): ByteArray? {
        val contents = IdentityBundle.read(appContext, nickname()) ?: return null
        return IdentityBundle.encode(contents)
    }

    override suspend fun adoptPairing(
        identity: ByteArray,
        entries: List<LinkEntry>,
        favorites: List<LinkFavorite>,
        media: Map<String, Pair<String, ByteArray>>
    ): Boolean {
        val contents = IdentityBundle.decode(identity) ?: return false
        if (!IdentityBundle.replace(appContext, contents)) return false
        if (contents.nickname.isNotBlank()) {
            try { DataManager(appContext).saveNickname(contents.nickname) } catch (_: Exception) { }
            AppStateStore.setNickname(contents.nickname)
        }
        val savedPaths = HashMap<String, String>()
        media.forEach { (id, pair) ->
            val file = saveMediaFile(id, pair.first, pair.second)
            savedPaths[id] = file.absolutePath
        }
        val rewritten = entries.map { entry ->
            val path = savedPaths[entry.message.id]
            if (path != null) entry.copy(message = entry.message.copy(content = path)) else entry
        }
        applyEntries(rewritten)
        applyFavorites(favorites)
        return true
    }

    override suspend fun eraseIdentity() {
        try { AppStateStore.panicClearPrivateConversations() } catch (_: Exception) { }
        try { AppStateStore.clear() } catch (_: Exception) { }
        try { ConversationRepository.getInstance(appContext).clearAllAndWait() } catch (_: Exception) { }
        try { FileUtils.clearAllMedia(appContext) } catch (_: Exception) { }
        try { mediaDir.deleteRecursively(); mediaDir.mkdirs() } catch (_: Exception) { }
        try { SeenMessageStore.getInstance(appContext).clear() } catch (_: Exception) { }
        try { MessageRouter.tryGetInstance()?.clearAll() } catch (_: Exception) { }
        try { FavoritesPersistenceService.shared.clearAllFavorites() } catch (_: Exception) { }
        try { IdentityBundle.erase(appContext) } catch (_: Exception) { }
    }

    override fun restartProcess() {
        try {
            val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (intent != null) appContext.startActivity(intent)
        } catch (_: Exception) { }
        Process.killProcess(Process.myPid())
    }

    override fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        val combined = when {
            model.isEmpty() -> "Android"
            manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
        return combined.take(LinkProtocol.MAX_DEVICE_NAME)
    }

    private fun findMessage(messageID: String): BitchatMessage? =
        AppStateStore.publicMessages.value.firstOrNull { it.id == messageID }
            ?: AppStateStore.channelMessages.value.values.asSequence().flatten().firstOrNull { it.id == messageID }
            ?: AppStateStore.privateMessages.value.values.asSequence().flatten().firstOrNull { it.id == messageID }

    private fun saveMediaFile(messageID: String, fileName: String, data: ByteArray): File {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "media.bin" }
        val safeId = messageID.replace(Regex("[^A-Za-z0-9._-]"), "_").take(32)
        val file = File(mediaDir, "${safeId}_$safeName")
        file.writeBytes(data)
        return file
    }

    private fun decodeHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) {
            null
        }
    }
}
