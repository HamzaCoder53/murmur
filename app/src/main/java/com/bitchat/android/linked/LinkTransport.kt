package com.bitchat.android.linked

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Length-prefixed TCP framing for [LinkSession] and [LinkHandshake].
 * Rejects frames larger than [MAX_FRAME_BYTES] before allocating.
 */
class SocketFrameIo(private val socket: Socket) : FrameIo {
    companion object {
        const val MAX_FRAME_BYTES = 512 * 1024
    }

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val writeLock = Mutex()

    @Volatile
    private var closed = false

    override suspend fun send(frame: ByteArray): Boolean {
        if (closed || frame.isEmpty() || frame.size > MAX_FRAME_BYTES) return false
        return writeLock.withLock {
            if (closed) return@withLock false
            withContext(Dispatchers.IO) {
                try {
                    output.writeInt(frame.size)
                    output.write(frame)
                    output.flush()
                    true
                } catch (_: Exception) {
                    close()
                    false
                }
            }
        }
    }

    override suspend fun receive(): ByteArray? {
        if (closed) return null
        return withContext(Dispatchers.IO) {
            try {
                val len = input.readInt()
                if (len !in 1..MAX_FRAME_BYTES) {
                    close()
                    return@withContext null
                }
                val buf = ByteArray(len)
                input.readFully(buf)
                buf
            } catch (_: Exception) {
                close()
                null
            }
        }
    }

    override fun close() {
        closed = true
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Local Wi-Fi / LAN discovery (NSD `_skeinlink._tcp.`) and TCP transport for linked devices.
 */
class LinkTransport(
    context: Context,
    private val scope: CoroutineScope,
    private val store: LinkStore,
    private val coordinator: LinkCoordinator
) {
    companion object {
        private const val TAG = "LinkTransport"
        private const val SERVICE_TYPE = "_skeinlink._tcp."
        private const val SERVICE_PREFIX = "sl-"
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val REDIAL_BACKOFF_MS = 3_000L
        /** How long a device typing a code looks for the device showing it. */
        private const val CODE_SEARCH_MS = 20_000L
    }

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    var listeningPort: Int? = null
        private set

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val discovered = ConcurrentHashMap<String, Pair<String, Int>>()
    private val dialing = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun start() {
        if (acceptJob != null) return
        try {
            val server = ServerSocket(0).apply { reuseAddress = true }
            serverSocket = server
            listeningPort = server.localPort
            acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(server) }
            registerNsd(server.localPort)
            startDiscovery()
            reconnectJob = scope.launch { reconnectLoop() }
            Log.i(TAG, "Link transport listening on port ${server.localPort}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start link transport: ${e.message}")
            stop()
        }
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        listeningPort = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        stopDiscovery()
        unregisterNsd()
        discovered.clear()
        dialing.clear()
    }

    fun isRunning(): Boolean = acceptJob != null

    fun localHostHint(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    /** Connect to an existing device using a scanned QR payload and run pairing. */
    fun connectForPairing(qr: LinkQr.Payload) {
        scope.launch(Dispatchers.IO) {
            val io = openPairingSocket(qr)
            if (io == null) {
                coordinator.failPairing("unreachable")
                return@launch
            }
            pairOver(io, qr.deviceId, qr.secret)
        }
    }

    /**
     * Pair by typing the 6-digit code shown on the existing device. Devices offering Skein links
     * on this Wi-Fi are tried in turn; one that is not offering a code says so at once and is
     * skipped without learning anything.
     */
    fun connectWithCode(code: String) {
        scope.launch(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + CODE_SEARCH_MS
            val tried = HashSet<String>()
            val linked = store.devices().map { it.deviceId }.toSet()
            while (System.currentTimeMillis() < deadline) {
                val candidates = discovered.entries
                    .filter { it.key != store.myDeviceId && it.key !in linked && it.key !in tried }
                for ((deviceId, address) in candidates) {
                    tried += deviceId
                    val io = dialTcp(address.first, address.second) ?: continue
                    val result = withTimeoutOrNull(LinkCoordinator.HELLO_TIMEOUT_MS) {
                        PasskeyPairing.runInitiator(io, code)
                    }
                    when (result) {
                        is PasskeyPairing.Result.Ok -> {
                            pairOver(io, deviceId, result.secret)
                            return@launch
                        }
                        is PasskeyPairing.Result.Failed -> {
                            io.close()
                            if (result.reason == PasskeyPairing.Failure.WRONG_CODE) {
                                coordinator.failPairing("wrong_code")
                                return@launch
                            }
                        }
                        null -> io.close()
                    }
                }
                delay(500)
            }
            coordinator.failPairing("not_found")
        }
    }

    private suspend fun pairOver(io: SocketFrameIo, serverDeviceId: String, secret: ByteArray) {
        run {
            val result = withTimeoutOrNull(LinkCoordinator.HELLO_TIMEOUT_MS) {
                LinkHandshake.runClient(
                    io = io,
                    myDeviceId = store.myDeviceId,
                    mode = LinkHandshake.Mode.PAIR,
                    key = { secret },
                    expectedServerId = serverDeviceId
                )
            }
            when (result) {
                is LinkHandshake.Result.Ok -> {
                    val key = result.linkKey
                    if (key == null) {
                        io.close()
                        coordinator.failPairing("missing_key")
                        return@run
                    }
                    val session = LinkSession(result.peerDeviceId, io, result.cipher, dialed = true)
                    coordinator.runPairingAsNew(session, key)
                }
                is LinkHandshake.Result.Failed -> {
                    io.close()
                    coordinator.failPairing(result.reason.name.lowercase())
                }
                null -> {
                    io.close()
                    coordinator.failPairing("timeout")
                }
            }
        }
    }

    private suspend fun openPairingSocket(qr: LinkQr.Payload): SocketFrameIo? {
        if (!qr.host.isNullOrBlank() && qr.port != null) {
            dialTcp(qr.host, qr.port)?.let { return it }
        }
        // Fallback: wait briefly for NSD resolution of the device ID.
        repeat(10) {
            discovered[qr.deviceId]?.let { (host, port) ->
                dialTcp(host, port)?.let { return it }
            }
            delay(500)
        }
        return null
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (scope.isActive && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            scope.launch(Dispatchers.IO) { handleIncoming(socket) }
        }
    }

    private suspend fun handleIncoming(socket: Socket) {
        val io = try {
            SocketFrameIo(socket)
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) { }
            return
        }
        // A device typing our 6-digit code speaks the passkey protocol first; it then runs the
        // ordinary pairing handshake with the secret that produced.
        val first = withTimeoutOrNull(LinkCoordinator.HELLO_TIMEOUT_MS) { io.receive() }
        if (first == null) {
            io.close()
            return
        }
        var keys: LinkHandshake.ServerKeys = coordinator.serverKeys
        val handshakeIo: FrameIo
        if (PasskeyPairing.isHello(first)) {
            val code = coordinator.offeredCode()
            val passkey = withTimeoutOrNull(LinkCoordinator.HELLO_TIMEOUT_MS) {
                PasskeyPairing.runResponder(io, first, code)
            }
            if (passkey !is PasskeyPairing.Result.Ok) {
                if (passkey == null ||
                    (passkey as PasskeyPairing.Result.Failed).reason != PasskeyPairing.Failure.NO_OFFER
                ) {
                    coordinator.onCodeFailed()
                }
                io.close()
                return
            }
            val base = coordinator.serverKeys
            keys = object : LinkHandshake.ServerKeys {
                override fun pairingSecret(): ByteArray = passkey.secret
                override fun linkKey(deviceId: String): ByteArray? = base.linkKey(deviceId)
            }
            handshakeIo = io
        } else {
            handshakeIo = PushbackFrameIo(io, first)
        }
        val result = withTimeoutOrNull(LinkCoordinator.HELLO_TIMEOUT_MS) {
            LinkHandshake.runServer(handshakeIo, store.myDeviceId, keys)
        }
        when (result) {
            is LinkHandshake.Result.Ok -> {
                val session = LinkSession(result.peerDeviceId, io, result.cipher, dialed = false)
                if (result.mode == LinkHandshake.Mode.PAIR) {
                    val key = result.linkKey ?: run { io.close(); return }
                    coordinator.runPairingAsExisting(session, key)
                } else {
                    coordinator.runSession(session)
                }
            }
            is LinkHandshake.Result.Failed -> {
                if (result.reason == LinkHandshake.Failure.BAD_PROOF ||
                    result.reason == LinkHandshake.Failure.NO_OFFER
                ) {
                    coordinator.onPairingProofFailed()
                }
                io.close()
            }
            null -> io.close()
        }
    }

    private suspend fun reconnectLoop() {
        while (scope.isActive) {
            val connected = coordinator.connected.value
            val targets = (store.devices().map { it.deviceId } + store.pendingWipes().keys).distinct()
            targets.forEach { peerId ->
                if (peerId !in connected && peerId !in dialing) {
                    discovered[peerId]?.let { (host, port) ->
                        dialLinkedPeer(peerId, host, port)
                    }
                }
            }
            delay(REDIAL_BACKOFF_MS)
        }
    }

    private fun dialLinkedPeer(peerId: String, host: String, port: Int) {
        if (!dialing.add(peerId)) return
        scope.launch(Dispatchers.IO) {
            try {
                val io = dialTcp(host, port) ?: return@launch
                val result = withTimeoutOrNull(LinkCoordinator.HELLO_TIMEOUT_MS) {
                    LinkHandshake.runClient(
                        io = io,
                        myDeviceId = store.myDeviceId,
                        mode = LinkHandshake.Mode.LINK,
                        key = coordinator::clientKey,
                        expectedServerId = peerId
                    )
                }
                if (result is LinkHandshake.Result.Ok) {
                    val session = LinkSession(result.peerDeviceId, io, result.cipher, dialed = true)
                    coordinator.runSession(session)
                } else {
                    io.close()
                }
            } finally {
                dialing.remove(peerId)
            }
        }
    }

    private fun dialTcp(host: String, port: Int): SocketFrameIo? = try {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        SocketFrameIo(socket)
    } catch (_: Exception) {
        null
    }

    // --- NSD ---

    private fun registerNsd(port: Int) {
        val mgr = nsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "$SERVICE_PREFIX${store.myDeviceId}"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        try {
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD registerService failed: ${e.message}")
            registrationListener = null
        }
    }

    private fun unregisterNsd() {
        val mgr = nsdManager ?: return
        val listener = registrationListener ?: return
        registrationListener = null
        try {
            mgr.unregisterService(listener)
        } catch (_: Exception) {
        }
    }

    private fun startDiscovery() {
        val mgr = nsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val peerId = extractDeviceId(serviceInfo.serviceName) ?: return
                if (peerId == store.myDeviceId) return
                resolveService(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val peerId = extractDeviceId(serviceInfo.serviceName) ?: return
                discovered.remove(peerId)
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD startDiscovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        try {
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD discoverServices failed: ${e.message}")
            discoveryListener = null
        }
    }

    private fun stopDiscovery() {
        val mgr = nsdManager ?: return
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            mgr.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val mgr = nsdManager ?: return
        try {
            mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val peerId = extractDeviceId(info.serviceName) ?: return
                    val host = info.host?.hostAddress ?: return
                    val port = info.port
                    if (port in 1..65535) {
                        discovered[peerId] = host to port
                        if (store.device(peerId) != null || store.pendingWipes().containsKey(peerId)) {
                            dialLinkedPeer(peerId, host, port)
                        }
                    }
                }
            })
        } catch (_: Exception) {
        }
    }

    private fun extractDeviceId(serviceName: String): String? {
        if (!serviceName.startsWith(SERVICE_PREFIX)) return null
        val id = serviceName.removePrefix(SERVICE_PREFIX).take(32)
        return id.takeIf(LinkHandshake::isDeviceId)
    }
}

/** Replays one frame already read, then reads from [io] (lets a server peek at the first frame). */
internal class PushbackFrameIo(private val io: FrameIo, first: ByteArray) : FrameIo {
    @Volatile
    private var pending: ByteArray? = first

    override suspend fun send(frame: ByteArray): Boolean = io.send(frame)

    override suspend fun receive(): ByteArray? {
        pending?.let {
            pending = null
            return it
        }
        return io.receive()
    }

    override fun close() = io.close()
}
