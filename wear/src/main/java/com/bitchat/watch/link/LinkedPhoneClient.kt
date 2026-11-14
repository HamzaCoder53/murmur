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
        private const val WATCH_PREFS = "bitchat_watch_prefs"
        private const val KEY_NICKNAME = "nickname"
        private const val MIRROR_TRANSPORT = "MIRROR"
        private const val RESTART_DELAY_MS = 2_500L

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

    /** messageID -> status signature last applied, so unchanged messages are not rewritten. */
    private val applied = HashMap<String, String>()

    fun start() {
        channel.setControlListener(::onControl)
    }

    fun stop() {
        channel.setControlListener(null)
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
        mirrorPeers = snapshot.peers.associateBy { it.peerID }
        AppStateStore.setTransportPeers(MIRROR_TRANSPORT, snapshot.peers.map { it.peerID })
        synchronized(applied) {
            snapshot.publicMessages.forEach { message ->
                if (changed(message)) AppStateStore.upsertPublicMessage(message)
            }
            snapshot.conversations.forEach { conversation ->
                conversation.messages.forEach { message ->
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

    private fun changed(message: BitchatMessage): Boolean {
        val signature = "${message.deliveryStatus}|${message.content.hashCode()}"
        if (applied[message.id] == signature) return false
        applied[message.id] = signature
        return true
    }

    // --- What the watch UI asks while mirroring ---

    fun peerNicknames(): Map<String, String> = mirrorPeers.mapValues { it.value.nickname }

    fun hasSession(peerID: String): Boolean = mirrorPeers[peerID]?.hasSession == true

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
