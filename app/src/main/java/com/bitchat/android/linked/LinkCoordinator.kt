package com.bitchat.android.linked

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Linked devices: two or more of the user's phones/tablets sharing one identity.
 *
 * - Together (an authenticated link session is up): [LeaderElection] picks one leader that runs
 *   the mesh; the others switch their radios off, show the leader's chats and peers, and send
 *   through it ([LinkCommand]s), like a tethered watch.
 * - Apart: each runs the mesh on its own with the shared identity. On reconnect both exchange the
 *   messages since their last sync ([LinkFrame.Sync] backlogs), merged by message ID.
 * - Pairing: a one-time QR secret authenticates the new device; the user confirms on the existing
 *   device, which then sends identity, history and media; both keep a per-pair long-term key.
 * - Unlink / panic: wipe requests that are kept until the other device confirms.
 */
class LinkCoordinator(
    private val scope: CoroutineScope,
    private val store: LinkStore,
    private val bridge: LinkedAppBridge,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        private const val TAG = "LinkedDevices"
        const val PUSH_DEBOUNCE_MS = 300L
        const val HELLO_TIMEOUT_MS = 15_000L
        const val APPROVAL_TIMEOUT_MS = 120_000L
        const val PAIRING_OFFER_MS = 5 * 60_000L
        const val MAX_BAD_PAIRING_ATTEMPTS = 5
        private const val MAX_PAIR_MEDIA_BYTES = 100L * 1024 * 1024
    }

    val myDeviceId: String get() = store.myDeviceId

    private val sessions = ConcurrentHashMap<String, LinkSession>()

    private val _role = MutableStateFlow<LinkRole>(LinkRole.Standalone)
    val role: StateFlow<LinkRole> = _role.asStateFlow()

    private val _connected = MutableStateFlow<Set<String>>(emptySet())
    val connected: StateFlow<Set<String>> = _connected.asStateFlow()

    private val _devices = MutableStateFlow(store.devices())
    val devices: StateFlow<List<LinkedDevice>> = _devices.asStateFlow()

    /** Per session: message ID -> signature last pushed, so mirror pushes send only changes. */
    private val pushed = ConcurrentHashMap<String, MutableMap<String, String>>()

    /** Media being received, by message ID. */
    private class Incoming(val fileName: String, val chunks: Array<ByteArray?>)
    private val incomingMedia = ConcurrentHashMap<String, Incoming>()

    /** File commands from a follower whose bytes are still arriving. */
    private val pendingFileCommands = ConcurrentHashMap<String, LinkCommand.SendFile>()

    private var pushJob: Job? = null

    // --- Pairing state, for the UI ---

    sealed interface Pairing {
        object Idle : Pairing
        /** Existing device: QR code shown, waiting for the new device. */
        data class Offering(val secret: ByteArray, val expiresAt: Long) : Pairing
        /** Existing device: the new device authenticated; the user must confirm. */
        data class AwaitingApproval(val deviceName: String) : Pairing
        /** New device: connected; waiting for the user to confirm on the existing device. */
        object WaitingForOtherDevice : Pairing
        object Transferring : Pairing
        data class Done(val deviceName: String) : Pairing
        object Restarting : Pairing
        data class Failed(val reason: String) : Pairing
    }

    private val _pairing = MutableStateFlow<Pairing>(Pairing.Idle)
    val pairing: StateFlow<Pairing> = _pairing.asStateFlow()
    private var approval: CompletableDeferred<Boolean>? = null
    private var badPairingAttempts = 0

    fun start() {
        if (pushJob != null) return
        pushJob = scope.launch { pushLoop() }
    }

    fun stop() {
        pushJob?.cancel()
        pushJob = null
        sessions.values.forEach { it.close() }
        sessions.clear()
        updateRole()
    }

    /** True when the link network has anything to do (links, pending wipes or a QR offer). */
    fun hasWork(): Boolean =
        store.devices().isNotEmpty() || store.pendingWipes().isNotEmpty() || _pairing.value is Pairing.Offering

    // --- Keys for the handshake ---

    val serverKeys = object : LinkHandshake.ServerKeys {
        override fun pairingSecret(): ByteArray? = (_pairing.value as? Pairing.Offering)
            ?.takeIf { clock() < it.expiresAt }?.secret

        override fun linkKey(deviceId: String): ByteArray? =
            store.device(deviceId)?.key ?: store.pendingWipes()[deviceId]
    }

    fun clientKey(deviceId: String): ByteArray? = serverKeys.linkKey(deviceId)

    /** A pairing handshake failed its proof: after a few, the QR code is withdrawn. */
    fun onPairingProofFailed() {
        if (++badPairingAttempts >= MAX_BAD_PAIRING_ATTEMPTS && _pairing.value is Pairing.Offering) {
            _pairing.value = Pairing.Failed("too many failed attempts")
        }
    }

    // --- Pairing: existing device ---

    /** Start showing a QR code. Returns the one-time secret it carries. */
    fun startPairingOffer(): ByteArray {
        val secret = LinkCrypto.randomBytes(LinkCrypto.KEY_BYTES)
        badPairingAttempts = 0
        _pairing.value = Pairing.Offering(secret, clock() + PAIRING_OFFER_MS)
        return secret
    }

    fun cancelPairing() {
        approval?.complete(false)
        _pairing.value = Pairing.Idle
    }

    fun failPairing(reason: String) {
        approval?.complete(false)
        _pairing.value = Pairing.Failed(reason)
    }

    fun respondToPairing(approve: Boolean) {
        approval?.complete(approve)
    }

    /** Server side of a session authenticated with the QR secret. */
    suspend fun runPairingAsExisting(session: LinkSession, linkKey: ByteArray) {
        // One QR code, one device: the secret is used up from here on.
        if (_pairing.value !is Pairing.Offering) {
            session.close()
            return
        }
        val request = withTimeoutOrNull(HELLO_TIMEOUT_MS) { session.receive() } as? LinkFrame.PairRequest
        if (request == null) {
            _pairing.value = Pairing.Failed("no request")
            session.close()
            return
        }
        val decision = CompletableDeferred<Boolean>()
        approval = decision
        _pairing.value = Pairing.AwaitingApproval(request.deviceName)
        val approved = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { decision.await() } == true
        approval = null
        if (!approved) {
            session.send(LinkFrame.PairDenied)
            session.close()
            _pairing.value = Pairing.Idle
            return
        }
        val identity = bridge.identityBundle()
        if (identity == null) {
            session.send(LinkFrame.PairDenied)
            session.close()
            _pairing.value = Pairing.Failed("no identity")
            return
        }
        _pairing.value = Pairing.Transferring
        val newId = session.peerDeviceId
        var ok = session.send(LinkFrame.PairIdentity(identity, bridge.deviceName()))
        // Introduce the new device to every other device of the group, each pair with its own key.
        store.devices().filter { it.deviceId != newId }.forEach { other ->
            val pairKey = LinkCrypto.randomBytes(LinkCrypto.KEY_BYTES)
            ok = ok && session.send(LinkFrame.Introduce(other.deviceId, other.name, pairKey))
            store.addPendingIntroduction(other.deviceId, LinkFrame.Introduce(newId, request.deviceName, pairKey))
        }
        val history = bridge.entriesSince(0L)
        ok = ok && sendHistory(session, history, bridge.favorites())
        var mediaBytes = 0L
        for (entry in history) {
            if (!ok) break
            if (entry.message.type == com.bitchat.android.model.BitchatMessageType.Message) continue
            val file = bridge.mediaFile(entry.message.id) ?: continue
            mediaBytes += file.second.size
            if (mediaBytes > MAX_PAIR_MEDIA_BYTES) break
            ok = sendMedia(session, entry.message.id, file.first, file.second)
        }
        ok = ok && session.send(LinkFrame.PairDone)
        if (!ok) {
            _pairing.value = Pairing.Failed("connection lost")
            session.close()
            return
        }
        val now = clock()
        store.put(LinkedDevice(newId, request.deviceName, linkKey, lastSeen = now, lastSyncAt = now))
        refreshDevices()
        _pairing.value = Pairing.Done(request.deviceName)
        session.close()
        // Introductions for devices reachable right now go out at once.
        sessions.values.forEach { deliverIntroductions(it) }
    }

    // --- Pairing: new device ---

    /** Client side, after the QR secret authenticated the existing device. */
    suspend fun runPairingAsNew(session: LinkSession, linkKey: ByteArray) {
        _pairing.value = Pairing.WaitingForOtherDevice
        if (!session.send(LinkFrame.PairRequest(bridge.deviceName()))) {
            _pairing.value = Pairing.Failed("connection lost")
            return
        }
        var identity: LinkFrame.PairIdentity? = null
        val intros = mutableListOf<LinkFrame.Introduce>()
        val entries = mutableListOf<LinkEntry>()
        val favorites = mutableListOf<LinkFavorite>()
        val media = HashMap<String, Pair<String, ByteArray>>()
        while (true) {
            val frame = session.receive()
            when (frame) {
                null -> {
                    _pairing.value = Pairing.Failed("connection lost")
                    return
                }
                LinkFrame.PairDenied -> {
                    _pairing.value = Pairing.Failed("denied")
                    session.close()
                    return
                }
                is LinkFrame.PairIdentity -> {
                    identity = frame
                    _pairing.value = Pairing.Transferring
                }
                is LinkFrame.Introduce -> intros += frame
                is LinkFrame.Sync -> {
                    entries += frame.entries
                    favorites += frame.favorites
                }
                is LinkFrame.MediaChunk -> collectChunk(frame)?.let { (name, data) -> media[frame.messageID] = name to data }
                LinkFrame.PairDone -> break
                else -> Unit
            }
        }
        val adopted = identity ?: run {
            _pairing.value = Pairing.Failed("no identity")
            session.close()
            return
        }
        session.close()
        if (!bridge.adoptPairing(adopted.identity, entries, favorites, media)) {
            _pairing.value = Pairing.Failed("could not store identity")
            return
        }
        // This device's previous links belonged to its previous identity.
        store.clearLinks()
        val now = clock()
        store.put(LinkedDevice(session.peerDeviceId, adopted.deviceName, linkKey, lastSeen = now, lastSyncAt = now))
        intros.forEach { store.put(LinkedDevice(it.deviceId, it.name, it.key)) }
        store.lastKnownNickname = bridge.nickname()
        refreshDevices()
        _pairing.value = Pairing.Restarting
        bridge.restartProcess()
    }

    // --- Linked sessions ---

    /** Run an authenticated link-mode session until it ends. */
    suspend fun runSession(session: LinkSession) {
        val peer = session.peerDeviceId
        val linked = store.device(peer)
        val wipeKey = store.pendingWipes()[peer]
        if (linked == null && wipeKey == null) {
            session.close()
            return
        }
        val wipeOnly = linked == null || store.devices().isEmpty()
        noteLocalNickname()
        if (!session.send(LinkFrame.Hello(bridge.deviceName(), bridge.nickname(), store.nicknameAt, wipeOnly))) return
        val hello = withTimeoutOrNull(HELLO_TIMEOUT_MS) { session.receive() } as? LinkFrame.Hello
        if (hello == null) {
            session.close()
            return
        }
        if (wipeKey != null) {
            // This device owes it a wipe: deliver that and nothing else.
            if (session.send(LinkFrame.Wipe(store.isPanicWipe(peer)))) {
                withTimeoutOrNull(HELLO_TIMEOUT_MS) {
                    while (true) {
                        val frame = session.receive() ?: break
                        if (frame == LinkFrame.WipeAck) {
                            store.removePendingWipe(peer)
                            store.remove(peer)
                            refreshDevices()
                            break
                        }
                    }
                }
            }
            session.close()
            return
        }
        if (hello.wipeOnly) {
            // The other device no longer holds the identity; it only came to deliver a wipe.
            serveFrames(session)
            return
        }
        if (!register(session)) return
        try {
            store.put(linked!!.copy(name = hello.deviceName.ifBlank { linked.name }, lastSeen = clock()))
            refreshDevices()
            applyRemoteNickname(hello.nickname, hello.nicknameAt)
            deliverIntroductions(session)
            val syncStartedAt = clock()
            val sent = sendHistory(session, bridge.entriesSince(linked.lastSyncAt), bridge.favorites())
            if (sent) store.device(peer)?.let { store.put(it.copy(lastSyncAt = syncStartedAt)) }
            serveFrames(session)
        } finally {
            unregister(session)
        }
    }

    private suspend fun serveFrames(session: LinkSession) {
        while (true) {
            val frame = session.receive() ?: break
            try {
                handle(session, frame)
            } catch (e: Exception) {
                Log.w(TAG, "Link frame failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun register(session: LinkSession): Boolean {
        val peer = session.peerDeviceId
        val keep = synchronized(sessions) {
            val existing = sessions[peer]
            // Both devices may dial each other. Keep the connection dialed by the smaller device
            // ID; both ends agree on that without talking.
            val preferNew = existing == null || existing.closed ||
                (session.dialed == (myDeviceId < peer))
            if (preferNew) {
                sessions[peer] = session
                existing?.close()
            }
            preferNew
        }
        if (!keep) {
            session.close()
            return false
        }
        pushed[peer] = HashMap()
        updateRole()
        return true
    }

    private fun unregister(session: LinkSession) {
        val peer = session.peerDeviceId
        val removed = synchronized(sessions) { sessions.remove(peer, session) }
        session.close()
        if (removed) {
            pushed.remove(peer)
            store.device(peer)?.let { store.put(it.copy(lastSeen = clock())) }
            refreshDevices()
            updateRole()
        }
    }

    private suspend fun handle(session: LinkSession, frame: LinkFrame) {
        val peer = session.peerDeviceId
        when (frame) {
            is LinkFrame.Sync -> {
                val missing = bridge.applyEntries(frame.entries)
                if (frame.favorites.isNotEmpty()) {
                    bridge.applyFavorites(BacklogMerge.mergeFavorites(bridge.favorites(), frame.favorites))
                }
                missing.forEach { session.send(LinkFrame.FetchMedia(it)) }
            }
            is LinkFrame.Peers -> if (leaderId() == peer) bridge.applyMirrorPeers(frame.peers)
            is LinkFrame.Nickname -> applyRemoteNickname(frame.nickname, frame.at)
            is LinkFrame.Command -> onCommand(frame.command, null)
            is LinkFrame.FetchMedia -> bridge.mediaFile(frame.messageID)?.let { (name, data) ->
                sendMedia(session, frame.messageID, name, data)
            }
            is LinkFrame.MediaChunk -> collectChunk(frame)?.let { (name, data) ->
                val command = pendingFileCommands.remove(frame.messageID)
                if (command != null) onCommand(command, data) else bridge.onMediaReceived(frame.messageID, name, data)
            }
            is LinkFrame.Wipe -> onWipe(session, frame.panic)
            LinkFrame.WipeAck -> {
                store.removePendingWipe(peer)
                store.remove(peer)
                refreshDevices()
            }
            LinkFrame.Unlinked -> {
                store.remove(peer)
                refreshDevices()
                session.close()
            }
            is LinkFrame.Introduce -> if (frame.deviceId != myDeviceId && store.device(frame.deviceId) == null) {
                store.put(LinkedDevice(frame.deviceId, frame.name, frame.key))
                refreshDevices()
            }
            else -> Unit
        }
    }

    private suspend fun deliverIntroductions(session: LinkSession) {
        val intros = store.pendingIntroductions(session.peerDeviceId)
        if (intros.isEmpty()) return
        if (intros.all { session.send(it) }) store.clearPendingIntroductions(session.peerDeviceId)
    }

    // --- Roles and mirroring ---

    private fun reachableLinked(): Set<String> =
        sessions.filterValues { !it.closed }.keys.filter { store.device(it) != null }.toSet()

    private fun leaderId(): String? = (_role.value as? LinkRole.Follower)?.leaderId

    @Synchronized
    private fun updateRole() {
        val reachable = reachableLinked()
        _connected.value = reachable
        val next = LeaderElection.roleFor(myDeviceId, reachable)
        val previous = _role.value
        if (next == previous) return
        _role.value = next
        when (next) {
            is LinkRole.Follower -> bridge.setRadiosSuppressed(true)
            else -> {
                bridge.applyMirrorPeers(null)
                bridge.setRadiosSuppressed(false)
                pushed.values.forEach { it.clear() }
            }
        }
        Log.i(TAG, "Linked-device role: ${next.javaClass.simpleName}")
        scope.launch { pushNow() }
    }

    @OptIn(FlowPreview::class)
    private suspend fun pushLoop() {
        bridge.changes.debounce(PUSH_DEBOUNCE_MS).collect { pushNow() }
    }

    /**
     * Mirror state to every connected device with a larger ID: those follow this device, or a
     * device that this one also reaches. Duplicates are harmless; entries merge by message ID.
     */
    suspend fun pushNow() {
        noteLocalNickname()?.let { (name, at) -> sessions.values.forEach { it.send(LinkFrame.Nickname(name, at)) } }
        val targets = sessions.values.filter { !it.closed && it.peerDeviceId > myDeviceId && store.device(it.peerDeviceId) != null }
        if (targets.isEmpty()) return
        val entries = bridge.currentEntries()
        val peers = if (_role.value == LinkRole.Leader) bridge.peers() else null
        targets.forEach { session ->
            val sent = pushed.getOrPut(session.peerDeviceId) { HashMap() }
            val changed = entries.filter { sent[it.message.id] != signature(it) }
            var ok = true
            changed.chunked(LinkProtocol.ENTRIES_PER_FRAME).forEach { chunk ->
                ok = ok && session.send(LinkFrame.Sync(chunk))
            }
            if (ok) changed.forEach { sent[it.message.id] = signature(it) }
            peers?.let { session.send(LinkFrame.Peers(it)) }
        }
    }

    private fun signature(e: LinkEntry) =
        "${BacklogMerge.rank(e.message.deliveryStatus)}|${e.read}|${e.message.content.hashCode()}|${e.message.type}"

    /**
     * Send something on the mesh on this device's behalf. A follower hands it to its leader;
     * returns false when this device should send it itself.
     */
    fun forward(command: LinkCommand, media: ByteArray? = null): Boolean {
        val leader = leaderId() ?: return false
        val session = sessions[leader] ?: return false
        scope.launch {
            var ok = session.send(LinkFrame.Command(command))
            if (ok && command is LinkCommand.SendFile && media != null) {
                ok = sendMedia(session, command.messageID, command.fileName, media)
            }
            if (!ok) Log.w(TAG, "Could not hand a message to the leading linked device")
        }
        return true
    }

    private suspend fun onCommand(command: LinkCommand, media: ByteArray?) {
        if (command is LinkCommand.SendFile && media == null) {
            // The bytes follow; run the command when they are complete.
            pendingFileCommands[command.messageID] = command
            return
        }
        // A relaying follower passes it on; the leader (or a lone device) carries it out.
        if (forward(command, media)) return
        bridge.execute(command, media)
        pushNow()
    }

    // --- Nickname ---

    /** Records a local rename (time-stamped) and returns it when it is new. */
    private fun noteLocalNickname(): Pair<String, Long>? {
        val current = bridge.nickname()
        val known = store.lastKnownNickname
        if (known == current) return null
        store.lastKnownNickname = current
        // First run: adopt without a timestamp, so it never beats a real rename elsewhere.
        if (known == null) return null
        val at = clock()
        store.nicknameAt = at
        return current to at
    }

    private fun applyRemoteNickname(name: String, at: Long) {
        if (name.isBlank() || at <= store.nicknameAt || name == bridge.nickname()) return
        bridge.setNickname(name)
        store.lastKnownNickname = name
        store.nicknameAt = at
    }

    // --- History and media ---

    private suspend fun sendHistory(session: LinkSession, entries: List<LinkEntry>, favorites: List<LinkFavorite>): Boolean {
        val chunks = entries.chunked(LinkProtocol.ENTRIES_PER_FRAME)
        if (chunks.isEmpty()) return session.send(LinkFrame.Sync(emptyList(), favorites, backlogDone = true))
        chunks.forEachIndexed { i, chunk ->
            val last = i == chunks.lastIndex
            if (!session.send(LinkFrame.Sync(chunk, if (last) favorites else emptyList(), backlogDone = last))) return false
        }
        // What was handed over counts as pushed, so mirroring does not resend it.
        pushed[session.peerDeviceId]?.let { sent -> entries.forEach { sent[it.message.id] = signature(it) } }
        return true
    }

    private suspend fun sendMedia(session: LinkSession, id: String, fileName: String, data: ByteArray): Boolean {
        if (data.size > LinkProtocol.MAX_MEDIA_BYTES) return true
        val total = maxOf(1, (data.size + LinkProtocol.MEDIA_CHUNK_BYTES - 1) / LinkProtocol.MEDIA_CHUNK_BYTES)
        for (i in 0 until total) {
            val from = i * LinkProtocol.MEDIA_CHUNK_BYTES
            val part = data.copyOfRange(from, minOf(data.size, from + LinkProtocol.MEDIA_CHUNK_BYTES))
            if (!session.send(LinkFrame.MediaChunk(id, fileName, i, total, part))) return false
        }
        return true
    }

    /** Returns (file name, bytes) once every chunk of a file arrived. */
    private fun collectChunk(chunk: LinkFrame.MediaChunk): Pair<String, ByteArray>? {
        val incoming = incomingMedia.compute(chunk.messageID) { _, current ->
            if (current == null || current.chunks.size != chunk.total) Incoming(chunk.fileName, arrayOfNulls(chunk.total)) else current
        }!!
        incoming.chunks[chunk.index] = chunk.data
        if (incoming.chunks.any { it == null }) return null
        incomingMedia.remove(chunk.messageID)
        val out = java.io.ByteArrayOutputStream()
        incoming.chunks.forEach { out.write(it!!) }
        return incoming.fileName to out.toByteArray()
    }

    // --- Unlink and wipe ---

    /** Remove [deviceId] from the group: it erases the shared identity now or when next reachable. */
    suspend fun unlinkDevice(deviceId: String) {
        val device = store.device(deviceId) ?: return
        store.addPendingWipe(deviceId, device.key, panic = false)
        store.remove(deviceId)
        refreshDevices()
        val session = sessions[deviceId]
        if (session != null && session.send(LinkFrame.Wipe(panic = false))) {
            // Acked in handle(); the pending wipe stays until then.
        }
        updateRole()
    }

    /** "Unlink this device": leave the group and erase the shared identity here. */
    suspend fun leaveGroup() {
        sessions.values.forEach { it.send(LinkFrame.Unlinked); it.close() }
        store.clearLinks()
        refreshDevices()
        bridge.eraseIdentity()
        bridge.restartProcess()
    }

    /**
     * Panic: every linked device must erase the identity. Reachable ones are told now; the rest
     * keep a pending wipe (with the key to authenticate it) until they confirm.
     */
    suspend fun panicWipeAll(except: String? = null) {
        val devices = store.devices()
        devices.filter { it.deviceId != except }.forEach { store.addPendingWipe(it.deviceId, it.key, panic = true) }
        store.clearLinks()
        refreshDevices()
        devices.filter { it.deviceId != except }.forEach { device ->
            sessions[device.deviceId]?.send(LinkFrame.Wipe(panic = true))
        }
        updateRole()
    }

    private suspend fun onWipe(session: LinkSession, panic: Boolean) {
        Log.w(TAG, "A linked device asked this device to erase the shared identity")
        session.send(LinkFrame.WipeAck)
        if (panic) panicWipeAll(except = session.peerDeviceId) else {
            sessions.values.filter { it !== session }.forEach { it.send(LinkFrame.Unlinked) }
            store.clearLinks()
        }
        store.remove(session.peerDeviceId)
        refreshDevices()
        bridge.eraseIdentity()
        bridge.restartProcess()
    }

    private fun refreshDevices() {
        _devices.value = store.devices()
    }
}
