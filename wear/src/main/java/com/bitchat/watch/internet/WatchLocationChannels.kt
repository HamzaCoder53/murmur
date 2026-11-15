package com.bitchat.watch.internet

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitchat.android.geohash.Geohash
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrFilter
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrKind
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.android.nostr.NostrRequest
import com.bitchat.android.nostr.NostrResponse
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.spam.SpamFilter
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Location channels on the watch: public chat with people in the same area (block up to region),
 * over the internet, compatible with Bitchat and Skein phones.
 *
 * Matches the phone exactly: kind-20000 events tagged with the area's geohash and your nickname,
 * signed with a per-area identity derived from your device seed (so a linked watch posts as the
 * same person as its phone), sent to and read from the 5 relays closest to the area.
 *
 * Location is read once when channels are opened (the system uses the phone's GPS while the watch
 * is connected to it), never in the background. Only the area's geohash leaves the watch.
 */
class WatchLocationChannels(
    private val context: Context,
    private val scope: CoroutineScope,
    private val internet: WatchNostrClient,
    private val nickname: () -> String
) {
    private companion object {
        const val TAG = "WatchLocation"
        const val RELAYS_PER_AREA = 5
        const val LOOKBACK_MS = 60 * 60_000L
        const val PUBLISH_TIMEOUT_MS = 10_000L
        const val MAX_MESSAGE_CHARS = 1_000
    }

    sealed interface Places {
        data object Unknown : Places
        data object Locating : Places
        data object NoPermission : Places
        data class Failed(@androidx.annotation.StringRes val reason: Int) : Places
        data class Found(val channels: List<GeohashChannel>) : Places
    }

    private val _places = MutableStateFlow<Places>(Places.Unknown)
    val places: StateFlow<Places> = _places.asStateFlow()

    private val _liveRelays = MutableStateFlow(0)
    val liveRelays: StateFlow<Int> = _liveRelays.asStateFlow()

    private val seen = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private var liveJob: Job? = null
    private val liveSockets = ConcurrentHashMap<String, WebSocket>()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Messages of one area, as the chat screen shows them. */
    fun messagesKey(geohash: String) = "geo:$geohash"

    // --- Where am I ---

    /** Read the location once and list the channels around it, from block to region. */
    @SuppressLint("MissingPermission")
    fun refreshPlaces() {
        if (!hasPermission()) {
            _places.value = Places.NoPermission
            return
        }
        _places.value = Places.Locating
        scope.launch {
            val location = try {
                val client = LocationServices.getFusedLocationProviderClient(context)
                val priority = if (hasFinePermission()) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
                withTimeoutOrNull(30_000L) {
                    suspendCancellableCoroutine<android.location.Location?> { cont ->
                        val cancel = CancellationTokenSource()
                        cont.invokeOnCancellation { cancel.cancel() }
                        client.getCurrentLocation(priority, cancel.token)
                            .addOnSuccessListener { cont.resume(it) }
                            .addOnFailureListener { cont.resume(null) }
                    }
                } ?: client.lastLocation.let { task ->
                    suspendCancellableCoroutine<android.location.Location?> { cont ->
                        task.addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Location unavailable: ${e.message}")
                null
            }
            _places.value = if (location == null) {
                Places.Failed(com.bitchat.watch.R.string.loc_unavailable)
            } else {
                Places.Found(channelsAt(location.latitude, location.longitude, hasFinePermission()))
            }
        }
    }

    /** Channels for a point; block and neighborhood need a precise location. */
    internal fun channelsAt(latitude: Double, longitude: Double, precise: Boolean): List<GeohashChannel> =
        listOf(
            GeohashChannelLevel.BLOCK,
            GeohashChannelLevel.NEIGHBORHOOD,
            GeohashChannelLevel.CITY,
            GeohashChannelLevel.PROVINCE,
            GeohashChannelLevel.REGION
        )
            .filter { precise || it.precision <= GeohashChannelLevel.CITY.precision }
            .map { GeohashChannel(it, Geohash.encode(latitude, longitude, it.precision)) }

    // --- Relays near the area ---

    private val relayDirectory: List<Triple<String, Double, Double>> by lazy {
        try {
            context.assets.open("nostr_relays.csv").bufferedReader().useLines { lines ->
                lines.drop(1).mapNotNull { line ->
                    val parts = line.split(',')
                    if (parts.size < 3) return@mapNotNull null
                    val url = parts[0].trim().let { if ("://" in it) it else "wss://$it" }
                    val lat = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
                    val lon = parts[2].trim().toDoubleOrNull() ?: return@mapNotNull null
                    Triple(url, lat, lon)
                }.toList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Relay list unavailable: ${e.message}")
            emptyList()
        }
    }

    /** The relays a phone would use for this area: the 5 closest to its centre. */
    internal fun relaysFor(geohash: String): List<String> {
        val (lat, lon) = Geohash.decodeToCenter(geohash)
        val nearest = relayDirectory.sortedBy { distanceMeters(lat, lon, it.second, it.third) }
            .take(RELAYS_PER_AREA).map { it.first }
        return nearest.ifEmpty { WatchNostrClient.RELAYS }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // --- Live chat while a channel is open ---

    fun open(geohash: String) {
        close()
        liveJob = scope.launch {
            var backoff = 5_000L
            while (true) {
                val lease = internet.lease()
                if (lease == null) {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                    continue
                }
                val request = NostrRequest.toJson(
                    NostrRequest.Subscribe(
                        "geo-$geohash",
                        listOf(NostrFilter.geohashEphemeral(geohash, System.currentTimeMillis() - LOOKBACK_MS, 200))
                    )
                )
                val closed = CompletableDeferred<Unit>()
                relaysFor(geohash).forEach { url ->
                    lease.client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            liveSockets[url] = webSocket
                            _liveRelays.value = liveSockets.size
                            webSocket.send(request)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val r = internet.parse(text)
                            if (r is NostrResponse.Event) onEvent(r.event, geohash)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = gone(url, webSocket)

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone(url, webSocket)

                        private fun gone(url: String, socket: WebSocket) {
                            liveSockets.remove(url, socket)
                            _liveRelays.value = liveSockets.size
                            if (liveSockets.isEmpty()) closed.complete(Unit)
                        }
                    })
                }
                backoff = 5_000L
                try {
                    closed.await()
                } finally {
                    lease.release()
                }
                delay(backoff)
            }
        }
    }

    fun close() {
        liveJob?.cancel()
        liveJob = null
        liveSockets.values.forEach { runCatching { it.close(1000, null) } }
        liveSockets.clear()
        _liveRelays.value = 0
    }

    private fun onEvent(event: NostrEvent, geohash: String) {
        if (event.kind != NostrKind.EPHEMERAL_EVENT) return
        val tagGeo = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.getOrNull(1)
        if (!tagGeo.equals(geohash, ignoreCase = true)) return
        if (!seen.add(event.id)) return
        scope.launch {
            try {
                if (!event.isValidSignature()) return@launch
                val pubkey = event.pubkey.lowercase()
                val mine = NostrIdentityBridge.deriveIdentity(geohash, context).publicKeyHex
                if (mine.equals(pubkey, ignoreCase = true)) return@launch
                // Presence-only "teleport" announcements carry no text.
                if (event.content.isBlank()) return@launch
                val name = event.tags.firstOrNull { it.size >= 2 && it[0] == "n" }?.getOrNull(1)?.take(32) ?: "anon"
                val message = BitchatMessage(
                    id = event.id,
                    sender = "$name#${pubkey.takeLast(4)}",
                    content = event.content.take(MAX_MESSAGE_CHARS),
                    timestamp = Date(event.createdAt * 1000L),
                    senderPeerID = "nostr:${pubkey.take(8)}",
                    senderNostrPubkey = pubkey,
                    channel = "#$geohash"
                )
                if (SpamFilter.shared.shouldHide(message, SpamFilter.nostrSenderKey(pubkey))) return@launch
                AppStateStore.addChannelMessage(messagesKey(geohash), message)
            } catch (e: Exception) {
                Log.w(TAG, "Could not read a location message: ${e.message}")
            }
        }
    }

    // --- Posting ---

    /** Post to an area's public chat. Shows at once here; true when a relay accepted it. */
    suspend fun send(geohash: String, content: String): Boolean {
        val text = content.trim().take(MAX_MESSAGE_CHARS)
        if (text.isEmpty()) return false
        val identity = NostrIdentityBridge.deriveIdentity(geohash, context)
        val event = NostrProtocol.createEphemeralGeohashEvent(text, geohash, identity, nickname())
        seen.add(event.id)
        AppStateStore.addChannelMessage(
            messagesKey(geohash),
            BitchatMessage(
                id = event.id,
                sender = nickname(),
                content = text,
                timestamp = Date(),
                senderPeerID = "geohash:$geohash",
                senderNostrPubkey = identity.publicKeyHex,
                channel = "#$geohash",
                deliveryStatus = com.bitchat.android.model.DeliveryStatus.Sending
            )
        )
        val accepted = publish(event, relaysFor(geohash))
        val status = if (accepted) {
            com.bitchat.android.model.DeliveryStatus.Sent
        } else {
            com.bitchat.android.model.DeliveryStatus.Failed("No relay reachable")
        }
        AppStateStore.updateChannelMessageStatus(messagesKey(geohash), event.id, status)
        return accepted
    }

    private suspend fun publish(event: NostrEvent, relays: List<String>): Boolean {
        val lease = internet.lease() ?: return false
        try {
            val payload = NostrRequest.toJson(NostrRequest.Event(event))
            val accepted = CompletableDeferred<Boolean>()
            val sockets = relays.map { url ->
                lease.client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(payload)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val r = internet.parse(text)
                        if (r is NostrResponse.Ok && r.eventId == event.id && r.accepted) accepted.complete(true)
                    }
                })
            }
            val ok = withTimeoutOrNull(PUBLISH_TIMEOUT_MS) { accepted.await() } ?: false
            delay(1_000)
            sockets.forEach { it.close(1000, null) }
            return ok
        } finally {
            lease.release()
        }
    }
}
