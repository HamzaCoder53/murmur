package com.bitchat.android.wearlink

import android.Manifest
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bitchat.android.R
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.services.NicknameProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Phone side of "linked identity": a watch that has taken this phone's identity.
 *
 * - Linking: a watch asks; the phone shows a notification and sends the identity only if the
 *   user taps Allow.
 * - Mirroring: while a linked watch is reachable, the phone pushes it the chat state and carries
 *   out the messages it sends, so both show one conversation history.
 * - Panic: the phone tells linked watches to erase the shared identity.
 */
class LinkedWatchCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val channel: WearLinkChannel
) {
    companion object {
        private const val TAG = "LinkedWatch"
        private const val PREFS = "murmur_linked_watches"
        private const val KEY_NODES = "nodes"
        private const val NOTIFICATION_CHANNEL = "murmur_watch_link"
        private const val NOTIFICATION_ID = 7301
        private const val REFRESH_MS = 10_000L
        private const val PUBLIC_LIMIT = 60
        private const val CONVERSATION_LIMIT = 20
        private const val PER_CONVERSATION_LIMIT = 40

        const val ACTION_APPROVE = "xfuturehb.murmur.WATCH_LINK_APPROVE"
        const val ACTION_DENY = "xfuturehb.murmur.WATCH_LINK_DENY"
        const val EXTRA_NODE = "node"

        /** Set while the phone's watch link is running, for the approval receiver. */
        @Volatile
        var current: LinkedWatchCoordinator? = null
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var pushJob: Job? = null

    fun isLinked(nodeId: String): Boolean = nodeId in linkedNodes()

    private fun linkedNodes(): Set<String> = prefs.getStringSet(KEY_NODES, emptySet()).orEmpty()

    private fun setLinked(nodeId: String, linked: Boolean) {
        val nodes = linkedNodes().toMutableSet()
        if (linked) nodes += nodeId else nodes -= nodeId
        prefs.edit().putStringSet(KEY_NODES, nodes).apply()
    }

    fun start() {
        current = this
        channel.setControlListener(::onControl)
        if (pushJob == null) pushJob = scope.launch { pushLoop() }
    }

    fun stop() {
        if (current === this) current = null
        channel.setControlListener(null)
        pushJob?.cancel()
        pushJob = null
    }

    /** The watch on [nodeId] is sending mesh packets again, so it no longer holds this identity. */
    fun onPacketFromNode(nodeId: String) {
        if (isLinked(nodeId)) {
            Log.i(TAG, "Linked watch is acting as a separate peer again; unlinking it")
            setLinked(nodeId, false)
        }
    }

    // --- Control messages ---

    private fun onControl(nodeId: String, path: String, data: ByteArray) {
        when (path) {
            WearLinkContract.CTL_LINK_REQUEST -> showApproval(nodeId, String(data, Charsets.UTF_8))
            WearLinkContract.CTL_UNLINK -> setLinked(nodeId, false)
            WearLinkContract.CTL_MIRROR_HELLO -> if (isLinked(nodeId)) {
                scope.launch {
                    MirrorProtocol.decodeBacklog(data)?.let { mergeBacklog(it) }
                    pushSnapshot(setOf(nodeId))
                }
            }
            WearLinkContract.CTL_MIRROR_COMMAND -> if (isLinked(nodeId)) {
                MirrorProtocol.decodeCommand(data)?.let { command -> scope.launch { execute(command) } }
            }
        }
    }

    // --- Linking ---

    private fun showApproval(nodeId: String, watchName: String) {
        val manager = NotificationManagerCompat.from(appContext)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Watch linking",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        fun action(action: String, request: Int) = PendingIntent.getBroadcast(
            appContext,
            request,
            Intent(appContext, WatchLinkApprovalReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_NODE, nodeId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val name = watchName.ifBlank { "Your watch" }.take(40)
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Link $name?")
            .setContentText("It will use this phone's Murmur identity and see your chats.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$name wants to use this phone's Murmur identity. It will be able to read " +
                        "and send your messages, including when away from the phone. Only allow " +
                        "this for your own watch."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, "Deny", action(ACTION_DENY, 1))
            .addAction(0, "Allow", action(ACTION_APPROVE, 2))
            .build()
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            manager.notify(NOTIFICATION_ID, notification)
        } else {
            Log.w(TAG, "Cannot ask to link a watch: notifications are not allowed")
            channel.sendControl(nodeId, WearLinkContract.CTL_LINK_DENIED, ByteArray(0))
        }
    }

    fun approve(nodeId: String) {
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
        scope.launch {
            val mesh = MeshServiceHolder.getUnifiedOrCreate(appContext)
            val nickname = NicknameProvider.getNickname(appContext, mesh.myPeerID)
            val contents = IdentityBundle.read(appContext, nickname)
            if (contents == null) {
                Log.e(TAG, "This phone has no identity to share")
                channel.sendControl(nodeId, WearLinkContract.CTL_LINK_DENIED, ByteArray(0))
                return@launch
            }
            setLinked(nodeId, true)
            channel.sendControl(nodeId, WearLinkContract.CTL_LINK_IDENTITY, IdentityBundle.encode(contents))
            Log.i(TAG, "Shared identity with a watch")
        }
    }

    fun deny(nodeId: String) {
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
        channel.sendControl(nodeId, WearLinkContract.CTL_LINK_DENIED, ByteArray(0))
    }

    /** Panic: every linked watch must erase the shared identity. */
    fun wipeLinkedWatches() {
        linkedNodes().forEach { channel.sendControl(it, WearLinkContract.CTL_WIPE, ByteArray(0)) }
        prefs.edit().remove(KEY_NODES).apply()
    }

    // --- Mirroring ---

    @OptIn(FlowPreview::class)
    private suspend fun pushLoop() = coroutineScope {
        // Children of this scope, so cancelling the push job stops both.
        launch {
            combine(AppStateStore.publicMessages, AppStateStore.privateMessages, AppStateStore.peers) { _, _, _ -> }
                .debounce(400)
                .collect { pushSnapshot(reachableLinkedNodes()) }
        }
        // Sessions and connection state change without touching those flows.
        while (true) {
            delay(REFRESH_MS)
            pushSnapshot(reachableLinkedNodes())
        }
    }

    private fun reachableLinkedNodes(): Set<String> = linkedNodes() intersect channel.reachableNodes.value

    private fun pushSnapshot(nodes: Set<String>) {
        if (nodes.isEmpty()) return
        val snapshot = try {
            buildSnapshot()
        } catch (e: Exception) {
            Log.w(TAG, "Could not build chat snapshot: ${e.message}")
            return
        }
        val data = MirrorProtocol.encodeSnapshot(snapshot)
        nodes.forEach { channel.sendControl(it, WearLinkContract.CTL_MIRROR_STATE, data) }
    }

    private fun buildSnapshot(): MirrorProtocol.Snapshot {
        val mesh = MeshServiceHolder.getUnifiedOrCreate(appContext)
        val me = mesh.myPeerID
        val nicknames = mesh.getPeerNicknames()
        val peers = AppStateStore.peers.value.filter { it != me }.map { id ->
            MirrorProtocol.Peer(
                peerID = id,
                nickname = nicknames[id] ?: id.take(8),
                connected = mesh.getPeerInfo(id)?.isConnected == true,
                hasSession = mesh.hasEstablishedSession(id)
            )
        }
        val conversations = AppStateStore.privateMessages.value.entries
            .filter { it.value.isNotEmpty() }
            .sortedByDescending { entry -> entry.value.maxOf { it.timestamp.time } }
            .take(CONVERSATION_LIMIT)
            .map { (key, messages) ->
                val peerID = messages.lastOrNull { it.senderPeerID != null && it.senderPeerID != me }
                    ?.senderPeerID ?: key
                MirrorProtocol.Conversation(key, peerID, messages.takeLast(PER_CONVERSATION_LIMIT))
            }
        return MirrorProtocol.Snapshot(
            myPeerID = me,
            myNickname = NicknameProvider.getNickname(appContext, me),
            peers = peers,
            publicMessages = AppStateStore.publicMessages.value.filter { it.channel == null }.takeLast(PUBLIC_LIMIT),
            conversations = conversations
        )
    }

    /** Send a message the watch wrote, exactly as if it had been typed on the phone. */
    private suspend fun execute(command: MirrorProtocol.Command) {
        val mesh = MeshServiceHolder.getUnifiedOrCreate(appContext)
        val me = mesh.myPeerID
        val nickname = NicknameProvider.getNickname(appContext, me)
        when (command) {
            is MirrorProtocol.Command.SendPublic -> {
                if (AppStateStore.hasSeenMessage(command.messageID)) return
                AppStateStore.addPublicMessage(
                    BitchatMessage(
                        id = command.messageID,
                        sender = nickname,
                        content = command.content,
                        timestamp = Date(),
                        senderPeerID = me,
                        deliveryStatus = DeliveryStatus.Sent
                    )
                )
                mesh.sendMessage(command.content, emptyList(), null)
            }
            is MirrorProtocol.Command.SendPrivate -> {
                val conversationID = ContactDirectory.canonicalConversationId(command.peerID)
                val message = BitchatMessage(
                    id = command.messageID,
                    sender = nickname,
                    content = command.content,
                    timestamp = Date(),
                    isPrivate = true,
                    recipientNickname = command.recipientNickname,
                    senderPeerID = me,
                    deliveryStatus = DeliveryStatus.Sending
                )
                // Already known when the watch's backlog recorded it: route it again anyway (the
                // watch never got a delivery receipt); recipients discard duplicates by ID.
                if (!AppStateStore.hasSeenMessage(command.messageID) &&
                    !AppStateStore.addPrivateMessageDurably(conversationID, message, forceRead = true)
                ) return
                val route = MessageRouter.getInstance(appContext, mesh).sendPrivate(
                    content = command.content,
                    toPeerID = conversationID,
                    recipientNickname = command.recipientNickname,
                    messageID = command.messageID
                )
                if (route != MessageRouter.RouteResult.QUEUED && route != MessageRouter.RouteResult.DROPPED) {
                    AppStateStore.updatePrivateMessageStatus(command.messageID, DeliveryStatus.Sent)
                }
            }
        }
    }

    /** Record what the watch sent and received while it was away from the phone. */
    private suspend fun mergeBacklog(backlog: MirrorProtocol.Snapshot) {
        val me = MeshServiceHolder.getUnifiedOrCreate(appContext).myPeerID
        backlog.publicMessages.forEach { AppStateStore.addPublicMessage(it) }
        backlog.conversations.forEach { conversation ->
            conversation.messages.forEach { message ->
                AppStateStore.addPrivateMessageDurably(
                    peerID = conversation.peerID,
                    msg = message,
                    forceRead = message.senderPeerID == me
                )
            }
        }
    }
}

/** Handles Allow / Deny on the "Link your watch?" notification. */
class WatchLinkApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val node = intent.getStringExtra(LinkedWatchCoordinator.EXTRA_NODE) ?: return
        val coordinator = LinkedWatchCoordinator.current ?: return
        when (intent.action) {
            LinkedWatchCoordinator.ACTION_APPROVE -> coordinator.approve(node)
            LinkedWatchCoordinator.ACTION_DENY -> coordinator.deny(node)
        }
    }
}
