package com.bitchat.watch.link

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.wearlink.IdentityBundle
import com.bitchat.android.wearlink.MirrorProtocol
import com.bitchat.android.wearlink.WearLinkChannel
import com.bitchat.android.wearlink.WearLinkContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Watch side of "linked identity": this watch uses its phone's identity.
 *
 * Next to the phone, the watch stays off the mesh and mirrors the phone: it shows the chat state
 * the phone sends and asks the phone to send messages for it. Away from the phone it runs the mesh
 * itself as the same user, and hands the phone a backlog of what happened when they meet again.
 */
class LinkedPhoneClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val channel: WearLinkChannel,
    private val myPeerID: () -> String
) {
    enum class State { NOT_LINKED, NO_PHONE, REQUESTING, DENIED, LINKED, RESTARTING }

    companion object {
        private const val TAG = "LinkedPhone"
        private const val PREFS = "murmur_link"
        private const val KEY_LINKED = "linked"
        private const val KEY_STANDALONE_SINCE = "standalone_since_ms"
        private const val KEY_PENDING_NICKNAME = "pending_nickname"
        private const val WATCH_PREFS = "bitchat_watch_prefs"
        private const val KEY_NICKNAME = "nickname"
        private const val MIRROR_TRANSPORT = "MIRROR"
        private const val RESTART_DELAY_MS = 2_500L
        private const val MEDIA_DIR = "mirror_media"

        fun isLinked(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LINKED, false)
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val linked: Boolean = prefs.getBoolean(KEY_LINKED, false)

    private val _state = MutableStateFlow(if (linked) State.LINKED else State.NOT_LINKED)
    val state: StateFlow<State> = _state.asStateFlow()

    /** True while next to the phone: the phone is on the mesh, the watch mirrors it. */
    @Volatile
    var mirroring = false
        private set

    @Volatile
    private var mirrorPeers: Map<String, MirrorProtocol.Peer> = emptyMap()

    @Volatile
    var phoneNickname: String? = null
        private set

    /** Called when the phone renamed the shared identity; the watch adopts the name. */
    @Volatile
    var onNicknameFromPhone: ((String) -> Unit)? = null

    /** The watch renamed the shared identity: tell the phone now, or when it is next reachable. */
    fun onLocalNicknameChanged(name: String) {
        if (!linked) return
        prefs.edit().putString(KEY_PENDING_NICKNAME, name).apply()
        sendPendingNickname()
    }

    private fun sendPendingNickname() {
        val pending = prefs.getString(KEY_PENDING_NICKNAME, null) ?: return
        sendCommand(MirrorProtocol.Command.SetNickname(pending))
    }

    /** messageID -> status signature last applied, so unchanged messages are not rewritten. */
    private val applied = HashMap<String, String>()

    /** A photo or voice note waiting for its file from the phone. */
    private data class PendingMedia(
        val conversationPeerID: String?,
        val message: BitchatMessage,
        val media: MirrorProtocol.Media
    )

    private val pendingMedia = java.util.concurrent.ConcurrentHashMap<String, PendingMedia>()
    private val requestedMedia = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Voice notes recorded on this watch, by message ID: shown from here, never fetched back. */
    private val ownMedia = java.util.concurrent.ConcurrentHashMap<String, java.io.File>()

    private val mediaDir: java.io.File get() = java.io.File(context.filesDir, MEDIA_DIR)

    private fun mirrorFile(messageID: String, extension: String): java.io.File = java.io.File(
        mediaDir,
        messageID.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64) +
            if (extension.isBlank()) "" else ".$extension"
    )

    fun start() {
        channel.setControlListener(::onControl)
        if (linked) {
            channel.setFileListener(
                destination = { _, path ->
                    if (!path.startsWith(WearLinkContract.FILE_MEDIA)) null else {
                        val id = path.removePrefix(WearLinkContract.FILE_MEDIA)
                        pendingMedia[id]?.let { mirrorFile(id, it.media.extension) }
                    }
                },
                onReceived = { _, path, file -> onMediaReceived(path.removePrefix(WearLinkContract.FILE_MEDIA), file) }
            )
        }
    }

    fun stop() {
        channel.setControlListener(null)
        channel.setFileListener(null, null)
    }

    private fun phoneNode(): String? = channel.reachableNodes.value.firstOrNull()

    // --- Linking ---

    fun requestLink() {
        if (linked) return
        val node = phoneNode()
        if (node == null) {
            _state.value = State.NO_PHONE
            return
        }
        val name = Build.MODEL ?: "Watch"
        if (channel.sendControl(node, WearLinkContract.CTL_LINK_REQUEST, name.toByteArray(Charsets.UTF_8))) {
            _state.value = State.REQUESTING
        } else {
            _state.value = State.NO_PHONE
        }
    }

    /** Drop the phone's identity: the watch gets a fresh identity of its own and no saved chats. */
    fun unlink() {
        phoneNode()?.let { channel.sendControl(it, WearLinkContract.CTL_UNLINK, ByteArray(0)) }
        resetAndRestart(eraseIdentity = true)
    }

    private fun onControl(nodeId: String, path: String, data: ByteArray) {
        when (path) {
            WearLinkContract.CTL_LINK_IDENTITY -> if (!linked) adoptIdentity(data)
            WearLinkContract.CTL_LINK_DENIED -> if (!linked) _state.value = State.DENIED
            WearLinkContract.CTL_WIPE -> if (linked) {
                Log.w(TAG, "Phone ran panic: erasing the shared identity")
                resetAndRestart(eraseIdentity = true)
            }
            WearLinkContract.CTL_MIRROR_STATE -> if (linked && mirroring) {
                // Off the main thread: a first snapshot can hold hundreds of messages.
                scope.launch { MirrorProtocol.decodeSnapshot(data)?.let(::applySnapshot) }
            }
        }
    }

    private fun adoptIdentity(data: ByteArray) {
        val contents = IdentityBundle.decode(data)
        if (contents == null) {
            Log.e(TAG, "Phone sent an unreadable identity")
            _state.value = State.DENIED
            return
        }
        scope.launch {
            // Chats saved here belong to the identity being replaced.
            eraseSavedChats()
            if (!IdentityBundle.replace(context, contents)) {
                Log.e(TAG, "Could not store the phone's identity")
                _state.value = State.DENIED
                return@launch
            }
            context.getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_NICKNAME, contents.nickname.take(32))
                .commit()
            prefs.edit().putBoolean(KEY_LINKED, true).putLong(KEY_STANDALONE_SINCE, 0L).commit()
            restartSoon()
        }
    }

    private fun resetAndRestart(eraseIdentity: Boolean) {
        scope.launch {
            eraseSavedChats()
            if (eraseIdentity) IdentityBundle.erase(context)
            context.getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE).edit().remove(KEY_NICKNAME).commit()
            prefs.edit().clear().commit()
            restartSoon()
        }
    }

    private suspend fun eraseSavedChats() {
        try {
            ConversationRepository.getInstance(context.applicationContext).clearAllAndWait()
        } catch (e: Exception) {
            Log.w(TAG, "Could not erase saved chats: ${e.message}")
        }
    }

    /**
     * Running services hold the old keys in memory, so the process restarts. The sticky foreground
     * service brings the mesh back with the new identity.
     */
    private suspend fun restartSoon() {
        _state.value = State.RESTARTING
        delay(RESTART_DELAY_MS)
        Process.killProcess(Process.myPid())
    }

    // --- Mirroring ---

    /** The phone is reachable again: stop meshing, send the backlog, start mirroring. */
    fun onTethered() {
        if (!linked) return
        mirroring = true
        val node = phoneNode() ?: return
        val since = prefs.getLong(KEY_STANDALONE_SINCE, 0L)
        val backlog = if (since > 0) buildBacklog(since) else MirrorProtocol.encodeBacklog(emptyList(), emptyList())
        channel.sendControl(node, WearLinkContract.CTL_MIRROR_HELLO, backlog)
        prefs.edit().putLong(KEY_STANDALONE_SINCE, 0L).apply()
        sendPendingNickname()
    }

    /** Away from the phone: the watch runs the mesh itself from now on. */
    fun onStandalone() {
        if (!linked) return
        mirroring = false
        mirrorPeers = emptyMap()
        AppStateStore.clearTransportPeers(MIRROR_TRANSPORT)
        prefs.edit().putLong(KEY_STANDALONE_SINCE, System.currentTimeMillis()).apply()
    }

    private fun buildBacklog(since: Long): ByteArray {
        val publicMessages = AppStateStore.publicMessages.value.filter {
            it.channel == null && it.timestamp.time >= since
        }
        val me = myPeerID()
        val conversations = AppStateStore.privateMessages.value.mapNotNull { (key, messages) ->
            val recent = messages.filter { it.timestamp.time >= since }
            if (recent.isEmpty()) {
                null
            } else {
                val peerID = messages.lastOrNull { it.senderPeerID != null && it.senderPeerID != me }
                    ?.senderPeerID ?: key
                MirrorProtocol.Conversation(key, peerID, recent)
            }
        }
        return MirrorProtocol.encodeBacklog(publicMessages, conversations)
    }

    private fun applySnapshot(snapshot: MirrorProtocol.Snapshot) {
        if (snapshot.myPeerID != myPeerID()) return
        phoneNickname = snapshot.myNickname
        val pending = prefs.getString(KEY_PENDING_NICKNAME, null)
        when {
            // The phone took the watch's new name.
            pending != null && pending == snapshot.myNickname ->
                prefs.edit().remove(KEY_PENDING_NICKNAME).apply()
            // The phone has not seen the watch's change yet; keep the watch's name.
            pending != null -> sendPendingNickname()
            snapshot.myNickname.isNotBlank() -> onNicknameFromPhone?.invoke(snapshot.myNickname)
        }
        mirrorPeers = snapshot.peers.associateBy { it.peerID }
        syncFavorites(snapshot.peers)
        AppStateStore.setTransportPeers(MIRROR_TRANSPORT, snapshot.peers.map { it.peerID })
        synchronized(applied) {
            snapshot.publicMessages.forEach { raw ->
                val message = withMedia(raw, snapshot.media[raw.id], conversationPeerID = null)
                if (changed(message)) AppStateStore.upsertPublicMessage(message)
            }
            snapshot.conversations.forEach { conversation ->
                com.bitchat.watch.ui.WearChatState.setUnread(conversation.peerID, conversation.unread)
                conversation.messages.forEach { raw ->
                    val message = withMedia(raw, snapshot.media[raw.id], conversation.peerID)
                    if (changed(message)) {
                        AppStateStore.upsertPrivateMessage(
                            conversation.peerID,
                            message,
                            forceRead = message.senderPeerID == snapshot.myPeerID
                        )
                    }
                }
            }
        }
    }

    /**
     * A photo or voice note shows its file once it is on the watch; until then the placeholder
     * text stays and the file is requested from the phone (once).
     */
    private fun withMedia(
        message: BitchatMessage,
        media: MirrorProtocol.Media?,
        conversationPeerID: String?
    ): BitchatMessage {
        if (media == null || media.kind == MirrorProtocol.MediaKind.FILE) return message
        val local = ownMedia[message.id] ?: mirrorFile(message.id, media.extension).takeIf { it.isFile }
        if (local != null) return message.copy(type = typeOf(media.kind), content = local.absolutePath)
        pendingMedia[message.id] = PendingMedia(conversationPeerID, message, media)
        if (requestedMedia.add(message.id)) sendCommand(MirrorProtocol.Command.FetchMedia(message.id))
        return message
    }

    private fun typeOf(kind: MirrorProtocol.MediaKind) = when (kind) {
        MirrorProtocol.MediaKind.IMAGE -> com.bitchat.android.model.BitchatMessageType.Image
        MirrorProtocol.MediaKind.AUDIO -> com.bitchat.android.model.BitchatMessageType.Audio
        MirrorProtocol.MediaKind.FILE -> com.bitchat.android.model.BitchatMessageType.File
    }

    private fun onMediaReceived(messageID: String, file: java.io.File) {
        val pending = pendingMedia.remove(messageID) ?: return
        val message = pending.message.copy(type = typeOf(pending.media.kind), content = file.absolutePath)
        synchronized(applied) {
            changed(message)
            if (pending.conversationPeerID == null) {
                AppStateStore.upsertPublicMessage(message)
            } else {
                AppStateStore.upsertPrivateMessage(pending.conversationPeerID, message, forceRead = true)
            }
        }
    }

    /** Upload a voice note recorded on the watch; the phone sends it and shows it too. */
    fun sendVoice(messageID: String, peerID: String?, recipientNickname: String, file: java.io.File): Boolean {
        val node = phoneNode() ?: return false
        ownMedia[messageID] = file
        if (!channel.sendFile(node, WearLinkContract.FILE_UPLOAD + messageID, file)) return false
        val extension = file.extension.ifBlank { "m4a" }
        return sendCommand(MirrorProtocol.Command.SendVoice(messageID, peerID.orEmpty(), recipientNickname, extension))
    }

    private fun changed(message: BitchatMessage): Boolean {
        val signature = "${message.deliveryStatus}|${message.content.hashCode()}"
        if (applied[message.id] == signature) return false
        applied[message.id] = signature
        return true
    }

    // --- What the watch UI asks while mirroring ---

    fun peerNicknames(): Map<String, String> = mirrorPeers.mapValues { it.value.nickname }

    fun hasSession(peerID: String): Boolean = mirrorPeers[peerID]?.hasSession == true

    /** The peer's Noise key as the phone knows it (the watch is not on the mesh to learn it). */
    fun noiseKey(peerID: String): ByteArray? = mirrorPeers[peerID]?.noiseKeyHex?.let { hex ->
        runCatching { hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
    }

    /** Tell the phone this conversation was read on the watch. */
    fun markRead(peerID: String): Boolean = sendCommand(MirrorProtocol.Command.MarkRead(peerID))

    /** Ask the phone to favorite or unfavorite a peer. */
    fun setFavorite(peerID: String, isFavorite: Boolean): Boolean =
        sendCommand(MirrorProtocol.Command.SetFavorite(peerID, isFavorite))

    /** Keep the watch's copy of favorites in step with the phone's. */
    private fun syncFavorites(peers: List<MirrorProtocol.Peer>) {
        val favorites = runCatching { com.bitchat.android.favorites.FavoritesPersistenceService.shared }.getOrNull() ?: return
        peers.forEach { peer ->
            val key = noiseKey(peer.peerID) ?: return@forEach
            val local = favorites.getFavoriteStatus(key)
            if ((local?.isFavorite == true) != peer.isFavorite) {
                favorites.updateFavoriteStatus(key, peer.nickname, peer.isFavorite)
            }
            if ((local?.theyFavoritedUs == true) != peer.theyFavoritedUs) {
                favorites.updatePeerFavoritedUs(key, peer.theyFavoritedUs)
            }
        }
    }

    /** Ask the phone to send [content] as a public message. */
    fun sendPublic(messageID: String, content: String): Boolean = sendCommand(
        MirrorProtocol.Command.SendPublic(messageID, content)
    )

    /** Ask the phone to send [content] privately; the phone routes it (mesh, internet or queue). */
    fun sendPrivate(messageID: String, peerID: String, recipientNickname: String, content: String): Boolean =
        sendCommand(MirrorProtocol.Command.SendPrivate(messageID, content, peerID, recipientNickname))

    private fun sendCommand(command: MirrorProtocol.Command): Boolean {
        val node = phoneNode() ?: return false
        return channel.sendControl(node, WearLinkContract.CTL_MIRROR_COMMAND, MirrorProtocol.encodeCommand(command))
    }
}
