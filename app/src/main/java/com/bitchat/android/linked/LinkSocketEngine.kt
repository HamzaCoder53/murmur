package com.bitchat.android.linked

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

// Platform-neutral TCP side of linked devices, shared by the phone and watch (LinkTransport,
// which adds Android NSD) and by desktop (which adds mDNS through JmDNS). Keep this file free of
// android.* imports: desktop compiles it from these sources.

/**
 * Length-prefixed TCP framing for [LinkSession] and [LinkHandshake].
 * Rejects empty frames and frames larger than [MAX_FRAME_BYTES] before allocating, and closes the
 * socket on the first bad length or I/O error.
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

    /**
     * Bounds every blocking read (0 = none). Coroutine timeouts cannot interrupt a blocked socket
     * read, so unauthenticated phases set this to keep a silent peer from holding a thread.
     */
    fun setReadTimeout(ms: Int) {
        try {
            socket.soTimeout = ms
        } catch (_: Exception) {
        }
    }

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

/**
 * Listening socket, pairing (QR secret or 6-digit code), handshakes and redialing of linked
 * devices. Service discovery is the platform's job: it reports what it resolves through
 * [onServiceResolved] / [onServiceLost] and advertises [serviceName] on [listeningPort].
 */
class LinkSocketEngine(
    private val scope: CoroutineScope,
    private val store: LinkStore,
    private val coordinator: LinkCoordinator,
    /** New outgoing sockets; a watch passes its Wi-Fi network's factory (its default may be Bluetooth). */
    private val newSocket: () -> Socket = { Socket() }
) {
    companion object {
        const val SERVICE_TYPE = "_skeinlink._tcp."
        const val SERVICE_PREFIX = "sl-"
        const val CONNECT_TIMEOUT_MS = 6_000
        private const val REDIAL_BACKOFF_MS = 3_000L
        /** How long a device typing a code looks for the device showing it. */
        private const val CODE_SEARCH_MS = 20_000L
        /** Socket read bound before a peer has authenticated. */
        val HANDSHAKE_READ_TIMEOUT_MS = LinkCoordinator.HELLO_TIMEOUT_MS.toInt()

        /** The device ID inside an advertised service name, or null for anything else. */
        fun extractDeviceId(serviceName: String): String? {
            if (!serviceName.startsWith(SERVICE_PREFIX)) return null
            val id = serviceName.removePrefix(SERVICE_PREFIX).take(32)
            return id.takeIf(LinkHandshake::isDeviceId)
        }
    }

    val serviceName: String get() = "$SERVICE_PREFIX${store.myDeviceId}"

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    var listeningPort: Int? = null
        private set

    private val discovered = ConcurrentHashMap<String, Pair<String, Int>>()
    private val dialing = ConcurrentHashMap.newKeySet<String>()

    /** Opens the listening socket and starts redialing. Returns the port, or null on failure. */
    @Synchronized
    fun start(): Int? {
        if (acceptJob != null) return listeningPort
        return try {
            val server = ServerSocket(0).apply { reuseAddress = true }
            serverSocket = server
            listeningPort = server.localPort
            acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(server) }
            reconnectJob = scope.launch { reconnectLoop() }
            server.localPort
        } catch (_: Exception) {
            stop()
            null
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

    /** Discovery resolved [serviceName] at [host]:[port]. Dials it at once when it is linked. */
    fun onServiceResolved(serviceName: String, host: String, port: Int) {
        val peerId = extractDeviceId(serviceName) ?: return
        if (peerId == store.myDeviceId || port !in 1..65535) return
        discovered[peerId] = host to port
        if (store.device(peerId) != null || store.pendingWipes().containsKey(peerId)) {
            dialLinkedPeer(peerId, host, port)
        }
    }

    fun onServiceLost(serviceName: String) {
        val peerId = extractDeviceId(serviceName) ?: return
        discovered.remove(peerId)
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
     * on this network are tried in turn; one that is not offering a code says so at once and is
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
                    return
                }
                // Authenticated: the transfer waits for a person to approve on the other device.
                io.setReadTimeout(0)
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

    private suspend fun openPairingSocket(qr: LinkQr.Payload): SocketFrameIo? {
        if (!qr.host.isNullOrBlank() && qr.port != null) {
            dialTcp(qr.host, qr.port)?.let { return it }
        }
        // Fallback: wait briefly for discovery to resolve the device ID.
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
        io.setReadTimeout(HANDSHAKE_READ_TIMEOUT_MS)
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
                io.setReadTimeout(0)
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
                    io.setReadTimeout(0)
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

    private fun dialTcp(host: String, port: Int): SocketFrameIo? {
        var socket: Socket? = null
        return try {
            socket = newSocket()
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            SocketFrameIo(socket).also { it.setReadTimeout(HANDSHAKE_READ_TIMEOUT_MS) }
        } catch (_: Exception) {
            try { socket?.close() } catch (_: Exception) { }
            null
        }
    }
}
