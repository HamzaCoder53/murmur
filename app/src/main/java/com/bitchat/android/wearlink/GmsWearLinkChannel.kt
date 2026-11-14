package com.bitchat.android.wearlink

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.net.Uri
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [WearLinkChannel] over the Wearable Data Layer.
 *
 * Presence is expressed with dynamic capabilities: each side adds [localCapability] while its mesh
 * service runs and watches for [remoteCapability]. A phone that is in range but not running the
 * mesh therefore never looks like a usable relay.
 *
 * Only directly connected (nearby) nodes count. A counterpart reachable only through Google's cloud
 * relay is not near enough to carry local mesh traffic, and the system can keep reporting a phone
 * as reachable that way for a long time after it is switched off.
 *
 * Capability callbacks are not guaranteed when a node silently disappears (powered off, out of
 * range), so reachability is also re-checked every [POLL_INTERVAL_MS] while started.
 *
 * Every Google Play services call is guarded: on a device without Play services the channel simply
 * never reports a reachable node.
 */
class GmsWearLinkChannel(
    context: Context,
    private val localCapability: String,
    private val remoteCapability: String
) : WearLinkChannel {

    private companion object {
        const val TAG = "GmsWearLinkChannel"
        const val POLL_INTERVAL_MS = 15_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            if (!started) return
            refreshReachableNodes()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val appContext = context.applicationContext
    private val capabilityClient: CapabilityClient? =
        runCatching { Wearable.getCapabilityClient(appContext) }.getOrNull()
    private val messageClient: MessageClient? =
        runCatching { Wearable.getMessageClient(appContext) }.getOrNull()
    private val channelClient: ChannelClient? =
        runCatching { Wearable.getChannelClient(appContext) }.getOrNull()

    @Volatile
    private var fileDestination: ((String, String) -> java.io.File?)? = null

    @Volatile
    private var fileReceived: ((String, String, java.io.File) -> Unit)? = null

    /** Incoming channels being written, so completion can be reported with their file. */
    private val incoming = java.util.concurrent.ConcurrentHashMap<String, java.io.File>()

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            val path = channel.path
            if (!path.startsWith(WearLinkContract.FILE_PREFIX)) return
            val target = try {
                fileDestination?.invoke(channel.nodeId, path)
            } catch (e: Exception) {
                null
            }
            if (target == null) {
                channelClient?.close(channel)
                return
            }
            target.parentFile?.mkdirs()
            incoming[path + "@" + channel.nodeId] = target
            channelClient?.receiveFile(channel, Uri.fromFile(target), false)
        }

        override fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
            val target = incoming.remove(channel.path + "@" + channel.nodeId) ?: return
            channelClient?.close(channel)
            if (closeReason == ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL && target.length() > 0) {
                try {
                    fileReceived?.invoke(channel.nodeId, channel.path, target)
                } catch (e: Exception) {
                    Log.w(TAG, "File handler failed: ${e.message}")
                }
            } else {
                target.delete()
            }
        }

        override fun onOutputClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
            channelClient?.close(channel)
        }
    }

    override fun setFileListener(
        destination: ((nodeId: String, path: String) -> java.io.File?)?,
        onReceived: ((nodeId: String, path: String, file: java.io.File) -> Unit)?
    ) {
        fileDestination = destination
        fileReceived = onReceived
    }

    override fun sendFile(nodeId: String, path: String, file: java.io.File): Boolean {
        val client = channelClient ?: return false
        if (!started || !path.startsWith(WearLinkContract.FILE_PREFIX) || !file.isFile) return false
        return try {
            client.openChannel(nodeId, path)
                .addOnSuccessListener { channel -> client.sendFile(channel, Uri.fromFile(file)) }
                .addOnFailureListener { error -> Log.d(TAG, "File send failed: ${error.message}") }
            true
        } catch (e: Exception) {
            Log.w(TAG, "File send rejected: ${e.message}")
            false
        }
    }

    private val _reachableNodes = MutableStateFlow<Set<String>>(emptySet())
    override val reachableNodes: StateFlow<Set<String>> = _reachableNodes.asStateFlow()

    @Volatile
    override var onMessage: ((nodeId: String, data: ByteArray) -> Unit)? = null

    @Volatile
    private var controlListener: ((nodeId: String, path: String, data: ByteArray) -> Unit)? = null

    override fun setControlListener(listener: ((nodeId: String, path: String, data: ByteArray) -> Unit)?) {
        controlListener = listener
    }

    override fun sendControl(nodeId: String, path: String, data: ByteArray): Boolean {
        val client = messageClient ?: return false
        if (!started || !path.startsWith(WearLinkContract.CONTROL_PREFIX)) return false
        return try {
            client.sendMessage(nodeId, path, data)
                .addOnFailureListener { error -> Log.d(TAG, "Control send failed: ${error.message}") }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Control send rejected: ${e.message}")
            false
        }
    }

    @Volatile
    private var started = false

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { info ->
        // The callback's node set may include nodes that have since disconnected, so treat it as a
        // prompt to ask for the reachable set rather than as the answer.
        if (info.name == remoteCapability) refreshReachableNodes()
    }

    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        if (event.path.startsWith(WearLinkContract.CONTROL_PREFIX)) {
            try {
                controlListener?.invoke(event.sourceNodeId, event.path, event.data)
            } catch (e: Exception) {
                Log.w(TAG, "Control handler failed: ${e.message}")
            }
        } else if (event.path == WearLinkContract.PATH_PACKET) {
            try {
                onMessage?.invoke(event.sourceNodeId, event.data)
            } catch (e: Exception) {
                Log.w(TAG, "Tunnel message handler failed: ${e.message}")
            }
        }
    }

    override fun start() {
        if (started) return
        started = true
        guarded("start") {
            capabilityClient?.addLocalCapability(localCapability)
            capabilityClient?.addListener(capabilityListener, remoteCapability)
            messageClient?.addListener(messageListener)
            channelClient?.registerChannelCallback(channelCallback)
        }
        refreshReachableNodes()
        handler.postDelayed(poll, POLL_INTERVAL_MS)
    }

    override fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(poll)
        guarded("stop") {
            capabilityClient?.removeLocalCapability(localCapability)
            capabilityClient?.removeListener(capabilityListener, remoteCapability)
            messageClient?.removeListener(messageListener)
            channelClient?.unregisterChannelCallback(channelCallback)
        }
        _reachableNodes.value = emptySet()
    }

    override fun send(nodeId: String, data: ByteArray): Boolean {
        val client = messageClient ?: return false
        if (!started) return false
        return try {
            client.sendMessage(nodeId, WearLinkContract.PATH_PACKET, data)
                .addOnFailureListener { error ->
                    // Usually the node went out of range between the capability update and now.
                    Log.d(TAG, "Tunnel send failed: ${error.message}")
                    refreshReachableNodes()
                }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Tunnel send rejected: ${e.message}")
            false
        }
    }

    private fun refreshReachableNodes() {
        val client = capabilityClient ?: return
        guarded("refresh") {
            client.getCapability(remoteCapability, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener { info: CapabilityInfo ->
                    if (started) {
                        _reachableNodes.value = info.nodes.filter { it.isNearby }.mapTo(HashSet()) { it.id }
                    }
                }
                .addOnFailureListener { error ->
                    Log.d(TAG, "Capability lookup failed: ${error.message}")
                    if (started) _reachableNodes.value = emptySet()
                }
        }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Data Layer $what failed: ${e.message}")
        }
    }
}
