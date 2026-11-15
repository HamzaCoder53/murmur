package com.bitchat.watch.internet

import kotlinx.coroutines.joinAll
import com.bitchat.watch.R
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import android.util.Log
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.NostrEmbeddedBitChat
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrFilter
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.android.nostr.NostrRequest
import com.bitchat.android.nostr.NostrResponse
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Private messages over the internet for the watch, speaking the same Nostr format as Bitchat on
 * phones: a Bitchat packet inside a NIP-17 gift wrap, via public relays. Works with anyone who is
 * a mutual favorite (you starred each other while in Bluetooth range, which swaps the keys used
 * here).
 *
 * The watch only uses it when it is not mirroring a linked phone: next to a linked phone, the
 * phone already does this for both.
 */
class WatchNostrClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val myPeerID: () -> String,
    private val myNickname: () -> String,
    private val onIncomingPrivate: (BitchatMessage) -> Unit,
    /** This device's internet identity (overridable for tests). */
    private val identityProvider: () -> NostrIdentity? = { NostrIdentityBridge.getCurrentNostrIdentity(context) },
    /** A connection to use for relays (overridable for tests); null means use the system's. */
    private val leaseProvider: (suspend () -> Lease?)? = null
) {
    /** An internet connection borrowed for one job; [release] hands it back. */
    class Lease(val client: OkHttpClient, val release: () -> Unit)
    enum class Mode(@androidx.annotation.StringRes val labelRes: Int, val intervalMs: Long?) {
        OFF(R.string.internet_mode_off, null),
        EVERY_5_MIN(R.string.internet_mode_5, 5 * 60_000L),
        EVERY_1_MIN(R.string.internet_mode_1, 60_000L),
        ALWAYS(R.string.internet_mode_always, null)
    }

    data class Status(
        val mode: Mode,
        val active: Boolean = false,
        val checking: Boolean = false,
        val connectedRelays: Int = 0,
        val lastCheckMs: Long? = null,
        /** What went wrong last time, as a text resource; null when the last check worked. */
        @androidx.annotation.StringRes val lastErrorRes: Int? = null
    )

    companion object {
        private const val TAG = "WatchNostr"
        private const val PREFS = "murmur_internet"
        private const val KEY_MODE = "mode"
        private const val KEY_SEEN = "seen_event_ids"
        private const val SEEN_PERSISTED = 500
        val DEFAULT_MODE = Mode.EVERY_5_MIN

        /** Same relays Bitchat uses by default. */
        val RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://offchain.pub",
            "wss://nostr21.com"
        )

        private const val FETCH_TIMEOUT_MS = 12_000L
        private const val HANDLE_TIMEOUT_MS = 8_000L
        private const val PUBLISH_TIMEOUT_MS = 10_000L
        private const val NETWORK_TIMEOUT_MS = 10_000L
        /**
         * Always ask for the last 48 hours, like the phone. Gift wraps are backdated by up to 22 h
         * on purpose (NIP-17), so "since my last check" would silently skip new messages.
         */
        private const val LOOKBACK_MS = 48 * 60 * 60_000L
        private const val MAX_AGE_SEC = 173_700L // 48 h + 15 min, as on the phone
        private const val REQUEST_CODE = 5502

        /** Set while a client runs, for the alarm receiver. */
        @Volatile
        var current: WatchNostrClient? = null
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    private val _status = MutableStateFlow(Status(mode = loadMode()))
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    private var suspended = false

    @Volatile
    private var running = false

    private val fetchLock = Mutex()
    /** Events already handled, so a 48-hour re-fetch does not deliver or acknowledge twice. */
    private val seenEventIds = java.util.Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 2_000
    }).apply { addAll(prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()) }

    private fun persistSeen() {
        val recent = synchronized(seenEventIds) { seenEventIds.toList().takeLast(SEEN_PERSISTED).toSet() }
        prefs.edit().putStringSet(KEY_SEEN, recent).apply()
    }

    /** Persistent sockets for ALWAYS mode, keyed by relay URL. */
    private val liveSockets = ConcurrentHashMap<String, WebSocket>()
    private var liveJob: Job? = null
    private var liveNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private val alarmIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, InternetCheckReceiver::class.java).setAction(InternetCheckReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun loadMode(): Mode =
        runCatching { Mode.valueOf(prefs.getString(KEY_MODE, null) ?: DEFAULT_MODE.name) }.getOrDefault(DEFAULT_MODE)

    // --- Lifecycle ---

    fun start() {
        current = this
        running = true
        applyMode()
    }

    fun stop() {
        running = false
        if (current === this) current = null
        stopLive()
        cancelAlarm()
        publishStatus()
    }

    /** Pause while mirroring a linked phone (the phone fetches for both). */
    fun setSuspended(value: Boolean) {
        if (suspended == value) return
        suspended = value
        applyMode()
    }

    fun setMode(mode: Mode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _status.value = _status.value.copy(mode = mode)
        applyMode()
    }

    private val active: Boolean get() = running && !suspended && _status.value.mode != Mode.OFF

    private fun applyMode() {
        stopLive()
        cancelAlarm()
        if (active) {
            when (_status.value.mode) {
                Mode.ALWAYS -> startLive()
                Mode.EVERY_1_MIN, Mode.EVERY_5_MIN -> {
                    scope.launch { fetchOnce() }
                    scheduleAlarm()
                }
                Mode.OFF -> Unit
            }
        }
        publishStatus()
    }

    private fun publishStatus() {
        _status.value = _status.value.copy(active = active, connectedRelays = liveSockets.size)
    }

    // --- Periodic checks ---

    private fun scheduleAlarm() {
        val interval = _status.value.mode.intervalMs ?: return
        val at = System.currentTimeMillis() + interval
        try {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule internet check: ${e.message}")
        }
    }

    private fun cancelAlarm() {
        try { alarmManager.cancel(alarmIntent) } catch (_: Exception) { }
    }

    /** The periodic alarm fired. */
    fun onAlarm() {
        if (!active) return
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Skein:InternetCheck")
        wakeLock.acquire(FETCH_TIMEOUT_MS + NETWORK_TIMEOUT_MS + 5_000L)
        scope.launch {
            try {
                fetchOnce()
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                scheduleAlarm()
            }
        }
    }

    /** "Check now": fetch regardless of mode (unless mirroring a linked phone). */
    fun checkNow() {
        if (!running || suspended) return
        scope.launch { fetchOnce() }
    }

    // --- Network ---

    /**
     * Ask the system for an internet connection. Away from the phone a watch keeps Wi-Fi or LTE off
     * until an app asks; this brings it up. Returns null when none appears in time.
     */
    private suspend fun acquireNetwork(): Pair<Network, ConnectivityManager.NetworkCallback>? {
        val found = CompletableDeferred<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found.complete(network)
            }
        }
        return try {
            connectivity.requestNetwork(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback
            )
            val network = withTimeoutOrNull(NETWORK_TIMEOUT_MS) { found.await() }
            if (network == null) {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
                null
            } else {
                network to callback
            }
        } catch (e: Exception) {
            Log.w(TAG, "No internet connection: ${e.message}")
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            null
        }
    }

    /** Borrow an internet connection and a client bound to it. */
    internal suspend fun lease(): Lease? {
        leaseProvider?.let { return it() }
        val (network, callback) = acquireNetwork() ?: return null
        val client = clientFor(network)
        return Lease(client) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun clientFor(network: Network): OkHttpClient = OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    // --- One-shot fetch ---

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun fetchOnce() {
        if (!running || suspended) return
        if (!fetchLock.tryLock()) return
        try {
            _status.value = _status.value.copy(checking = true, lastErrorRes = null)
            val identity = identityProvider() ?: run {
                _status.value = _status.value.copy(checking = false, lastErrorRes = R.string.internet_error_identity)
                return
            }
            val lease = lease() ?: run {
                _status.value = _status.value.copy(checking = false, lastErrorRes = R.string.internet_error_connection)
                return
            }
            try {
                val since = System.currentTimeMillis() - LOOKBACK_MS
                val client = lease.client
                val subscription = "m-" + UUID.randomUUID().toString().take(8)
                val request = NostrRequest.toJson(
                    NostrRequest.Subscribe(subscription, listOf(NostrFilter.giftWrapsFor(identity.publicKeyHex, since)))
                )
                // Messages still being decrypted (and acknowledged) when the relays finish.
                val handling = java.util.concurrent.ConcurrentLinkedQueue<kotlinx.coroutines.Job>()
                val finished = RELAYS.map { url ->
                    val done = CompletableDeferred<Boolean>()
                    val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(request)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            when (val r = parse(text)) {
                                is NostrResponse.Event -> handleGiftWrap(r.event, identity)?.let(handling::add)
                                is NostrResponse.EndOfStoredEvents -> done.complete(true)
                                else -> Unit
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            done.complete(false)
                        }

                        // A relay that hangs up cleanly before end-of-stored-events is done too.
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            done.complete(false)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            done.complete(false)
                        }
                    })
                    socket to done
                }
                val reached = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    finished.count { it.second.await() }
                } ?: finished.count { it.second.isCompleted && it.second.getCompleted() }
                finished.forEach { (socket, _) -> socket.close(1000, null) }
                // Finish handling what arrived before the wake lock and network go away.
                withTimeoutOrNull(HANDLE_TIMEOUT_MS) { handling.toList().joinAll() }
                persistSeen()
                _status.value = _status.value.copy(
                    checking = false,
                    lastCheckMs = System.currentTimeMillis(),
                    lastErrorRes = if (reached == 0) R.string.internet_error_relays else null
                )
            } finally {
                lease.release()
            }
        } finally {
            if (_status.value.checking) _status.value = _status.value.copy(checking = false)
            fetchLock.unlock()
        }
    }

    // --- Always-on ---

    private fun startLive() {
        if (liveJob != null) return
        liveJob = scope.launch {
            var backoffMs = 5_000L
            while (active && _status.value.mode == Mode.ALWAYS) {
                val identity = identityProvider()
                val acquired = if (identity != null) acquireNetwork() else null
                if (identity == null || acquired == null) {
                    _status.value = _status.value.copy(lastErrorRes = R.string.internet_error_connection)
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(120_000L)
                    continue
                }
                val (network, callback) = acquired
                liveNetworkCallback = callback
                val client = clientFor(network)
                val closed = CompletableDeferred<Unit>()
                // The session ends when every relay socket has ended, not when the first one fails
                // while the others are still connecting.
                val ended = java.util.concurrent.atomic.AtomicInteger(0)
                val opened = java.util.concurrent.atomic.AtomicBoolean(false)
                fun socketEnded() {
                    if (ended.incrementAndGet() >= RELAYS.size) closed.complete(Unit)
                }
                val since = System.currentTimeMillis() - LOOKBACK_MS
                val request = NostrRequest.toJson(
                    NostrRequest.Subscribe("m-live", listOf(NostrFilter.giftWrapsFor(identity.publicKeyHex, since)))
                )
                RELAYS.forEach { url ->
                    client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            opened.set(true)
                            liveSockets[url] = webSocket
                            webSocket.send(request)
                            publishStatus()
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            when (val r = parse(text)) {
                                is NostrResponse.Event -> handleGiftWrap(r.event, identity)
                                is NostrResponse.EndOfStoredEvents -> persistSeen()
                                else -> Unit
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            liveSockets.remove(url, webSocket)
                            publishStatus()
                            socketEnded()
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            liveSockets.remove(url, webSocket)
                            publishStatus()
                            socketEnded()
                        }
                    })
                }
                _status.value = _status.value.copy(lastErrorRes = null, lastCheckMs = System.currentTimeMillis())
                try {
                    closed.await()
                } finally {
                    // Also when the session is cancelled (mode change, tethered, stopped).
                    runCatching { connectivity.unregisterNetworkCallback(callback) }
                    liveNetworkCallback = null
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                }
                // Back off only while no relay could be reached at all.
                backoffMs = if (opened.get()) 5_000L else (backoffMs * 2).coerceAtMost(120_000L)
                delay(backoffMs)
            }
        }
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        liveSockets.values.forEach { runCatching { it.close(1000, null) } }
        liveSockets.clear()
        liveNetworkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        liveNetworkCallback = null
    }

    internal fun parse(text: String): NostrResponse? = try {
        NostrResponse.fromJsonArray(JsonParser.parseString(text).asJsonArray)
    } catch (_: Exception) {
        null
    }

    // --- Receiving ---

    private fun handleGiftWrap(event: NostrEvent, identity: NostrIdentity): kotlinx.coroutines.Job? {
        synchronized(seenEventIds) { if (!seenEventIds.add(event.id)) return null }
        if (System.currentTimeMillis() / 1000 - event.createdAt > MAX_AGE_SEC) return null
        return scope.launch {
            try {
                val (content, rawSender, rumorTimestamp) = NostrProtocol.decryptPrivateMessage(event, identity) ?: return@launch
                val senderPubkey = rawSender.lowercase()
                if (!content.startsWith("bitchat1:")) return@launch
                val packetData = base64UrlDecode(content.removePrefix("bitchat1:")) ?: return@launch
                val packet = BitchatPacket.fromBinaryData(packetData) ?: return@launch
                if (packet.type != MessageType.NOISE_ENCRYPTED.value) return@launch
                val payload = NoisePayload.decode(packet.payload) ?: return@launch

                // Same conversation key the phone uses, so a linked phone merges cleanly.
                val conversationKey = "nostr_${senderPubkey.take(16)}"
                GeohashAliasRegistry.put(conversationKey, senderPubkey)
                val conversationID = ContactDirectory.canonicalConversationId(conversationKey)

                when (payload.type) {
                    NoisePayloadType.PRIVATE_MESSAGE -> {
                        val pm = PrivateMessagePacket.decode(payload.data) ?: return@launch
                        if (FavoriteControlMessage.parse(pm.content) == null) {
                            val sender = ContactDirectory.resolve(conversationID).displayName
                                ?: "${senderPubkey.take(8)}…"
                            onIncomingPrivate(
                                BitchatMessage(
                                    id = pm.messageID,
                                    sender = sender,
                                    content = pm.content,
                                    timestamp = Date(rumorTimestamp * 1000L),
                                    isPrivate = true,
                                    recipientNickname = myNickname(),
                                    senderPeerID = conversationID,
                                    senderNostrPubkey = senderPubkey,
                                    deliveryStatus = DeliveryStatus.Delivered(myNickname(), Date())
                                )
                            )
                        }
                        sendAck(pm.messageID, senderPubkey, identity)
                    }
                    NoisePayloadType.DELIVERED -> com.bitchat.android.services.AppStateStore.updatePrivateMessageStatus(
                        String(payload.data, Charsets.UTF_8),
                        DeliveryStatus.Delivered(conversationID, Date())
                    )
                    NoisePayloadType.READ_RECEIPT -> com.bitchat.android.services.AppStateStore.updatePrivateMessageStatus(
                        String(payload.data, Charsets.UTF_8),
                        DeliveryStatus.Read(conversationID, Date())
                    )
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read an internet message: ${e.message}")
            }
        }
    }

    /** Tell the sender, over the internet, that their message was read. */
    suspend fun sendReadReceipt(messageID: String, peerID: String, senderNostrPubkey: String?): Boolean {
        if (!running || suspended) return false
        val recipientHex = senderNostrPubkey?.let { ContactIdentityResolver.nostrPubkeyHex(it) ?: it }
            ?: recipientKey(peerID)?.let { ContactIdentityResolver.nostrPubkeyHex(it) }
            ?: return false
        val identity = identityProvider() ?: return false
        val receipt = NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(NoisePayloadType.READ_RECEIPT, messageID)
            ?: return false
        return publish(NostrProtocol.createPrivateMessage(receipt, recipientHex, identity))
    }

    private suspend fun sendAck(messageID: String, recipientHex: String, identity: NostrIdentity) {
        val ack = NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(NoisePayloadType.DELIVERED, messageID) ?: return
        publish(NostrProtocol.createPrivateMessage(ack, recipientHex, identity))
    }

    // --- Sending ---

    /** The recipient's internet key, if you are mutual favorites; null means internet can't reach them. */
    fun recipientKey(peerID: String): String? = try {
        val resolution = ContactDirectory.resolve(peerID)
        when {
            // Same rule as the phone: favorites only once both sides favorited each other.
            resolution.isMutualFavorite && resolution.nostrPubkey != null -> resolution.nostrPubkey
            ContactIdentityResolver.isMeshPeerId(peerID) ->
                FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
                    ?.takeIf { it.isMutual }?.peerNostrPublicKey
            // A conversation that started over the internet: reply the way it came.
            ContactIdentityResolver.isNostrAlias(peerID) -> GeohashAliasRegistry.get(peerID)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** Whether a private message to [peerID] can go over the internet right now. */
    fun canSend(peerID: String): Boolean = running && !suspended && recipientKey(peerID) != null

    /**
     * Send a private message over the internet. Returns true once at least one relay accepted it
     * (not when it is delivered: the recipient's app acknowledges that separately).
     */
    suspend fun sendPrivate(content: String, peerID: String, messageID: String): Boolean {
        if (!running || suspended) return false
        val recipientKey = recipientKey(peerID) ?: return false
        val recipientHex = ContactIdentityResolver.nostrPubkeyHex(recipientKey) ?: return false
        val identity = identityProvider() ?: return false
        val recipientPeerID = runCatching {
            FavoritesPersistenceService.shared.findPeerIDForNostrPubkey(recipientKey)
        }.getOrNull() ?: peerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) } ?: return false
        val embedded = NostrEmbeddedBitChat.encodePMForNostr(content, messageID, recipientPeerID, myPeerID())
            ?: return false
        return publish(NostrProtocol.createPrivateMessage(embedded, recipientHex, identity))
    }

    internal suspend fun publish(events: List<NostrEvent>): Boolean {
        if (events.isEmpty()) return false
        val lease = lease() ?: return false
        try {
            val client = lease.client
            val ids = events.map { it.id }.toSet()
            val payloads = events.map { NostrRequest.toJson(NostrRequest.Event(it)) }
            val accepted = CompletableDeferred<Boolean>()
            val sockets = RELAYS.map { url ->
                client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        payloads.forEach { webSocket.send(it) }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val r = parse(text)
                        if (r is NostrResponse.Ok && r.eventId in ids) {
                            if (r.accepted) accepted.complete(true) else Log.w(TAG, "$url refused: ${r.message}")
                        } else if (r is NostrResponse.Notice) {
                            Log.w(TAG, "$url notice: ${r.message}")
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "$url failed: ${t.javaClass.simpleName} ${t.message}")
                    }
                })
            }
            val ok = withTimeoutOrNull(PUBLISH_TIMEOUT_MS) { accepted.await() } ?: false
            // Give the other relays a moment to take it too before closing.
            delay(1_000)
            sockets.forEach { it.close(1000, null) }
            return ok
        } finally {
            lease.release()
        }
    }

    private fun base64UrlDecode(input: String): ByteArray? = try {
        var s = input.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) s += "="
        android.util.Base64.decode(s, android.util.Base64.DEFAULT)
    } catch (_: Exception) {
        null
    }
}

/** Fires the periodic internet check. */
class InternetCheckReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.bitchat.watch.internet.CHECK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) WatchNostrClient.current?.onAlarm()
    }
}
