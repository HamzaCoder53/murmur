package com.bitchat.watch.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.linked.BacklogMerge
import com.bitchat.android.linked.LinkCommand
import com.bitchat.android.linked.LinkCoordinator
import com.bitchat.android.linked.LinkEntry
import com.bitchat.android.linked.LinkFavorite
import com.bitchat.android.linked.LinkPeer
import com.bitchat.android.linked.LinkProtocol
import com.bitchat.android.linked.LinkRole
import com.bitchat.android.linked.LinkTransport
import com.bitchat.android.linked.LinkedAppBridge
import com.bitchat.android.linked.PasskeyPairing
import com.bitchat.android.linked.PrefsLinkStore
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.wearlink.IdentityBundle
import com.bitchat.watch.mesh.WearMeshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.io.File
import java.net.Socket
import java.util.Date

/**
 * Linking the watch to a phone by typing the 6-digit code shown in the phone's Settings → Linked
 * devices, for a watch that is not paired with that phone through Wear OS.
 *
 * The watch joins the phone's linked-device group (shared identity) over local Wi-Fi. Whenever
 * the phone is reachable on the same Wi-Fi the watch mirrors it, like a Wear OS–linked watch:
 * its own Bluetooth mesh is off and it sends through the phone. Apart, it runs on its own with
 * the shared identity and hands over what it collected when they meet again.
 */
object CodeLink {
    private const val TAG = "CodeLink"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var store: PrefsLinkStore? = null
    @Volatile private var coordinator: LinkCoordinator? = null
    @Volatile private var transport: LinkTransport? = null
    @Volatile private var wifi: Network? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    private val _mirrorPeers = MutableStateFlow<List<LinkPeer>?>(null)
    /** The phone's mesh peers while following it; null otherwise. */
    val mirrorPeers: StateFlow<List<LinkPeer>?> = _mirrorPeers.asStateFlow()

    private val _pairing = MutableStateFlow<LinkCoordinator.Pairing>(LinkCoordinator.Pairing.Idle)
    val pairing: StateFlow<LinkCoordinator.Pairing> get() = coordinator?.pairing ?: _pairing.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (coordinator != null) return
        val app = context.applicationContext
        try {
            val s = PrefsLinkStore(app, isWatch = true)
            val c = LinkCoordinator(scope, s, WatchLinkedAppBridge(app) { _mirrorPeers.value = it })
            val t = LinkTransport(app, scope, s, c) { wifi?.socketFactory?.createSocket() ?: Socket() }
            store = s
            coordinator = c
            transport = t
        } catch (e: Throwable) {
            Log.w(TAG, "Code linking unavailable: ${e.message}")
        }
    }

    /** Linked to a phone by code (or holding wipes still to deliver). */
    fun isLinked(context: Context): Boolean {
        initialize(context)
        return store?.devices()?.isNotEmpty() == true
    }

    fun isFollowing(): Boolean = coordinator?.role?.value is LinkRole.Follower

    /** Mesh service start: resume the link if this watch is code-linked. */
    @Synchronized
    fun startIfNeeded(context: Context) {
        initialize(context)
        val c = coordinator ?: return
        if (!c.hasWork()) return
        startRunning(context)
    }

    @Synchronized
    fun stop(context: Context) {
        transport?.stop()
        coordinator?.stop()
        releaseWifi(context)
        _mirrorPeers.value = null
    }

    /** Pair by typing the code shown on the phone. Both must be on the same Wi-Fi. */
    fun joinWithCode(context: Context, code: String): Boolean {
        if (!PasskeyPairing.isCode(code)) return false
        initialize(context)
        val t = transport ?: return false
        startRunning(context)
        t.connectWithCode(code)
        return true
    }

    fun cancelPairing() {
        coordinator?.cancelPairing()
    }

    /** Leave the phone's group: erases the shared identity on this watch and restarts. */
    suspend fun unlink() {
        coordinator?.leaveGroup()
    }

    fun forward(command: LinkCommand, media: ByteArray? = null): Boolean =
        coordinator?.forward(command, media) == true

    suspend fun panicWipeAll() {
        coordinator?.panicWipeAll()
    }

    private fun startRunning(context: Context) {
        requestWifi(context)
        coordinator?.start()
        transport?.let { if (!it.isRunning()) it.start() }
    }

    /**
     * Keep Wi-Fi up while code-linked: a watch next to its own phone otherwise routes through
     * Bluetooth and turns Wi-Fi off, and the link to this phone only runs over Wi-Fi.
     */
    private fun requestWifi(context: Context) {
        if (wifiCallback != null) return
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifi = network
            }

            override fun onLost(network: Network) {
                if (wifi == network) wifi = null
            }
        }
        try {
            cm.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
            wifiCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi request failed: ${e.javaClass.simpleName}")
        }
    }

    private fun releaseWifi(context: Context) {
        val callback = wifiCallback ?: return
        wifiCallback = null
        wifi = null
        runCatching {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(callback)
        }
    }
}

/** [LinkedAppBridge] for the watch: its chat state lives in AppStateStore, its mesh in [WearMeshService]. */
internal class WatchLinkedAppBridge(
    context: Context,
    private val onMirrorPeersChanged: (List<LinkPeer>?) -> Unit
) : LinkedAppBridge {
    private companion object {
        const val MIRROR_TRANSPORT_ID = "code_link_mirror"
        const val WATCH_PREFS = "bitchat_watch_prefs"
        const val KEY_NICKNAME = "nickname"
    }

    private val app = context.applicationContext
    private val mediaDir = File(app.filesDir, "linked_media").apply { mkdirs() }

    private fun mesh(): WearMeshService? = WearMeshService.peek()

    override val changes: Flow<Unit> = combine(
        AppStateStore.publicMessages,
        AppStateStore.privateMessages,
        AppStateStore.channelMessages,
        AppStateStore.peers
    ) { _, _, _, _ -> }

    override fun nickname(): String = mesh()?.nickname
        ?: app.getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE).getString(KEY_NICKNAME, null)
        ?: "anon"

    override fun setNickname(name: String) {
        val clean = name.trim().take(LinkProtocol.MAX_NICKNAME)
        if (clean.isEmpty()) return
        val mesh = mesh()
        if (mesh != null) {
            mesh.applyLinkedNickname(clean)
        } else {
            app.getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NICKNAME, clean).apply()
        }
    }

    override suspend fun entriesSince(since: Long): List<LinkEntry> =
        currentEntries().filter { it.message.timestamp.time >= since }

    override fun currentEntries(): List<LinkEntry> {
        val out = ArrayList<LinkEntry>()
        AppStateStore.publicMessages.value.forEach { out += LinkEntry(it, conversation = null, read = true) }
        AppStateStore.channelMessages.value.values.forEach { list ->
            list.forEach { out += LinkEntry(it, conversation = null, read = true) }
        }
        AppStateStore.privateMessages.value.forEach { (convId, list) ->
            list.forEach { out += LinkEntry(it, conversation = convId, read = AppStateStore.isPrivateMessageRead(it.id)) }
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
            when {
                msg.isPrivate -> {
                    val convId = entry.conversation
                        ?: ContactDirectory.canonicalConversationId(msg.senderPeerID ?: msg.sender)
                    AppStateStore.addPrivateMessageDurably(convId, msg, forceRead = entry.read)
                    if (entry.read) AppStateStore.markPrivateMessageRead(msg.id)
                }
                !msg.channel.isNullOrBlank() -> AppStateStore.addChannelMessage(msg.channel, msg)
                else -> AppStateStore.addPublicMessage(msg)
            }
            if (msg.type != BitchatMessageType.Message && mediaFile(msg.id) == null) missingMedia += msg.id
        }
        for (entry in plan.update) {
            val status = entry.message.deliveryStatus ?: continue
            if (entry.message.isPrivate) AppStateStore.updatePrivateMessageStatus(entry.message.id, status)
        }
        plan.markRead.forEach(AppStateStore::markPrivateMessageRead)
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
        favorites.forEach { fav ->
            val key = ContactIdentityResolver.bytesFromHex(fav.noiseKeyHex) ?: return@forEach
            FavoritesPersistenceService.shared.updateFavoriteStatus(key, fav.nickname, fav.isFavorite)
        }
    }

    override fun peers(): List<LinkPeer> {
        val mesh = mesh() ?: return emptyList()
        val me = mesh.myPeerID
        return AppStateStore.peers.value.filter { it != me }.map { id ->
            val info = mesh.getPeerInfo(id)
            val noiseKey = info?.noisePublicKey
            val relationship = noiseKey?.let { runCatching { FavoritesPersistenceService.shared.getFavoriteStatus(it) }.getOrNull() }
            LinkPeer(
                peerID = id,
                nickname = info?.nickname?.takeIf { it.isNotBlank() } ?: id.take(8),
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
        val ids = peers?.filter { it.connected }?.map { it.peerID }.orEmpty()
        AppStateStore.setTransportPeers(MIRROR_TRANSPORT_ID, ids)
        AppStateStore.setTransportDirectPeers(MIRROR_TRANSPORT_ID, ids)
    }

    override fun setRadiosSuppressed(suppressed: Boolean) {
        mesh()?.setCodeLinkFollowing(suppressed)
    }

    /** Only when this watch leads (no linked phone in reach, another linked watch following). */
    override suspend fun execute(command: LinkCommand, media: ByteArray?) {
        val mesh = mesh() ?: return
        when (command) {
            is LinkCommand.SendPublic -> {
                if (command.messageID != null && !AppStateStore.hasSeenMessage(command.messageID)) {
                    AppStateStore.addPublicMessage(
                        BitchatMessage(
                            id = command.messageID, sender = nickname(), content = command.content,
                            timestamp = Date(), senderPeerID = mesh.myPeerID, deliveryStatus = DeliveryStatus.Sent
                        )
                    )
                }
                mesh.sendMessage(command.content, command.mentions)
            }
            is LinkCommand.SendPrivate -> {
                mesh.sendPrivateMessageWithId(command.content, command.peerID, command.recipientNickname, command.messageID)
                mesh.trackOutgoingPrivate(command.messageID, command.peerID, command.recipientNickname, command.content)
            }
            is LinkCommand.ReadReceipt -> AppStateStore.markPrivateMessageRead(command.messageID)
            is LinkCommand.Announce -> setNickname(command.nickname)
            else -> Unit
        }
    }

    override fun mediaFile(messageID: String): Pair<String, ByteArray>? {
        val msg = findMessage(messageID) ?: return null
        if (msg.type == BitchatMessageType.Message) return null
        val file = File(msg.content)
        if (!file.isFile || file.length() > LinkProtocol.MAX_MEDIA_BYTES) return null
        return runCatching { file.name to file.readBytes() }.getOrNull()
    }

    override suspend fun onMediaReceived(messageID: String, fileName: String, data: ByteArray) {
        val file = saveMediaFile(messageID, fileName, data)
        val existing = findMessage(messageID) ?: return
        val updated = existing.copy(content = file.absolutePath)
        when {
            updated.isPrivate -> AppStateStore.upsertPrivateMessage(
                ContactDirectory.canonicalConversationId(updated.senderPeerID ?: updated.sender),
                updated,
                forceRead = AppStateStore.isPrivateMessageRead(messageID)
            )
            !updated.channel.isNullOrBlank() -> AppStateStore.addChannelMessage(updated.channel, updated)
            else -> AppStateStore.upsertPublicMessage(updated)
        }
    }

    override fun identityBundle(): ByteArray? =
        IdentityBundle.read(app, nickname())?.let(IdentityBundle::encode)

    override suspend fun adoptPairing(
        identity: ByteArray,
        entries: List<LinkEntry>,
        favorites: List<LinkFavorite>,
        media: Map<String, Pair<String, ByteArray>>
    ): Boolean {
        val contents = IdentityBundle.decode(identity) ?: return false
        // Chats saved here belong to the identity being replaced.
        runCatching { ConversationRepository.getInstance(app).clearAllAndWait() }
        if (!IdentityBundle.replace(app, contents)) return false
        if (contents.nickname.isNotBlank()) {
            app.getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_NICKNAME, contents.nickname.take(LinkProtocol.MAX_NICKNAME)).commit()
        }
        val paths = media.mapValues { (id, pair) -> saveMediaFile(id, pair.first, pair.second).absolutePath }
        applyEntries(entries.map { e -> paths[e.message.id]?.let { e.copy(message = e.message.copy(content = it)) } ?: e })
        applyFavorites(favorites)
        return true
    }

    override suspend fun eraseIdentity() {
        runCatching { AppStateStore.panicClearPrivateConversations() }
        runCatching { AppStateStore.clear() }
        runCatching { ConversationRepository.getInstance(app).clearAllAndWait() }
        runCatching { mediaDir.deleteRecursively(); mediaDir.mkdirs() }
        runCatching { FavoritesPersistenceService.shared.clearAllFavorites() }
        runCatching { IdentityBundle.erase(app) }
        app.getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE).edit().remove(KEY_NICKNAME).commit()
    }

    /** The sticky foreground service brings the mesh back with the new identity. */
    override fun restartProcess() {
        Process.killProcess(Process.myPid())
    }

    override fun deviceName(): String = (Build.MODEL?.trim().orEmpty().ifEmpty { "Watch" }).take(LinkProtocol.MAX_DEVICE_NAME)

    private fun findMessage(id: String): BitchatMessage? =
        AppStateStore.publicMessages.value.firstOrNull { it.id == id }
            ?: AppStateStore.channelMessages.value.values.asSequence().flatten().firstOrNull { it.id == id }
            ?: AppStateStore.privateMessages.value.values.asSequence().flatten().firstOrNull { it.id == id }

    private fun saveMediaFile(messageID: String, fileName: String, data: ByteArray): File {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "media.bin" }
        val safeId = messageID.replace(Regex("[^A-Za-z0-9._-]"), "_").take(32)
        return File(mediaDir, "${safeId}_$safeName").also { it.writeBytes(data) }
    }
}
