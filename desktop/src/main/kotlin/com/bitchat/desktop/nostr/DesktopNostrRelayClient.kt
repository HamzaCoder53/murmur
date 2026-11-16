package com.bitchat.desktop.nostr

import android.util.Log
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrEventDeduplicator
import com.bitchat.android.nostr.NostrFilter
import com.bitchat.android.nostr.NostrRequest
import com.bitchat.android.nostr.RelayReconnectPolicy
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight OkHttp WebSocket Nostr relay client for desktop, reusing the mirrored
 * [NostrEvent], [NostrFilter], [NostrRequest], [NostrEventDeduplicator], and [RelayReconnectPolicy].
 */
class DesktopNostrRelayClient(
    private val relayUrls: List<String>,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build(),
) {
    private val gson = Gson()
    private val deduplicator = NostrEventDeduplicator.getInstance()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val subscriptions = ConcurrentHashMap<String, Pair<NostrFilter, (NostrEvent) -> Unit>>()
    private val pendingEvents = ConcurrentHashMap.newKeySet<NostrEvent>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        for (url in relayUrls) {
            connectRelay(url)
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        for ((_, ws) in sockets) {
            try { ws.close(1000, "shutdown") } catch (_: Exception) { }
        }
        sockets.clear()
        subscriptions.clear()
        pendingEvents.clear()
    }

    fun subscribe(subId: String, filter: NostrFilter, onEvent: (NostrEvent) -> Unit) {
        subscriptions[subId] = filter to onEvent
        if (!started.get()) start()
        val msg = gson.toJson(NostrRequest.Subscribe(subId, listOf(filter)))
        for ((_, ws) in sockets) {
            ws.send(msg)
        }
    }

    fun unsubscribe(subId: String) {
        subscriptions.remove(subId)
        val msg = gson.toJson(NostrRequest.Close(subId))
        for ((_, ws) in sockets) {
            ws.send(msg)
        }
    }

    fun publish(event: NostrEvent) {
        if (!started.get()) start()
        val msg = gson.toJson(NostrRequest.Event(event))
        var sentAny = false
        for ((_, ws) in sockets) {
            if (ws.send(msg)) sentAny = true
        }
        if (!sentAny) {
            pendingEvents += event
        }
    }

    private fun connectRelay(url: String) {
        if (!started.get()) return
        val req = Request.Builder().url(url).build()
        httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sockets[url] = webSocket
                reconnectAttempts.remove(url)
                for ((subId, pair) in subscriptions) {
                    webSocket.send(gson.toJson(NostrRequest.Subscribe(subId, listOf(pair.first))))
                }
                val toFlush = pendingEvents.toList()
                pendingEvents.removeAll(toFlush.toSet())
                for (ev in toFlush) {
                    webSocket.send(gson.toJson(NostrRequest.Event(ev)))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRelayMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sockets.remove(url, webSocket)
                scheduleReconnect(url)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets.remove(url, webSocket)
                if (started.get()) scheduleReconnect(url)
            }
        })
    }

    private fun scheduleReconnect(url: String) {
        if (!started.get()) return
        val attempt = reconnectAttempts.compute(url) { _, prev -> RelayReconnectPolicy.nextAttempt(prev ?: 0) } ?: 1
        val delayMs = RelayReconnectPolicy.backoffMs(attempt)
        scope.launch {
            delay(delayMs)
            connectRelay(url)
        }
    }

    internal fun handleRelayMessage(text: String) {
        val arr = try {
            JSONArray(text)
        } catch (_: Exception) {
            return
        }
        if (arr.length() < 3 || arr.optString(0) != "EVENT") return
        val subId = arr.optString(1)
        val eventJson = arr.optJSONObject(2)?.toString() ?: return
        val event = NostrEvent.fromJsonString(eventJson) ?: return
        if (!event.isValid()) {
            Log.w(TAG, "Dropped invalid Nostr event ${event.id.take(8)}")
            return
        }
        if (deduplicator.isDuplicate(event.id)) return
        subscriptions[subId]?.second?.invoke(event)
    }

    companion object {
        private const val TAG = "DesktopNostrRelayClient"
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
        )
    }
}
