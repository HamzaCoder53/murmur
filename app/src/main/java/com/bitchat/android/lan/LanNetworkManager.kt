package com.bitchat.android.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Finds Skein peers on the current Wi-Fi network with DNS-SD and connects to them over TCP,
 * handing every connection to [LanTransport].
 *
 * The Wi-Fi network is obtained with [ConnectivityManager.requestNetwork] without the INTERNET
 * capability, so a router with no uplink still works, and every outbound socket is created from
 * that [Network] rather than by binding the whole process to it. When the network goes away the
 * listener, advertisement and all connections are torn down; when it returns they are rebuilt.
 *
 * The advertised instance name is random per session and carries nothing about the user or
 * device. Peers identify themselves only through the mesh protocol, after connecting.
 */
class LanNetworkManager(
    context: Context,
    private val scope: CoroutineScope,
    private val transport: LanTransport
) {
    companion object {
        private const val TAG = "LanNetworkManager"
        const val SERVICE_TYPE = "_skeinmesh._tcp"
        private const val CONNECT_TIMEOUT_MS = 5_000

        /** The device with the larger instance name waits this long for the other to dial first. */
        private const val RESPONDER_DIAL_DELAY_MS = 4_000L

        /** How often discovered peers without a connection are dialed again. */
        private const val RECONNECT_INTERVAL_MS = 30_000L

        /**
         * NSD callbacks for every session. Process-wide and never shut down: NsdManager may still
         * deliver a stop or unregister callback after a session ends, and a rejected callback
         * would throw on the system's handler thread.
         */
        private val nsdCallbackExecutor: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "skein-lan-nsd").apply { isDaemon = true }
            }
        }

        fun randomInstanceName(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(8).also(random::nextBytes)
            return "skein-" + bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val callbackExecutor: ExecutorService get() = nsdCallbackExecutor

    private val instanceName = randomInstanceName()

    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var session: Session? = null
    private val lock = Any()

    fun start() {
        if (connectivity == null || nsd == null) {
            Log.w(TAG, "Connectivity or NSD service unavailable; local Wi-Fi mesh disabled")
            return
        }
        if (networkCallback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch(Dispatchers.IO) { openSession(network) }
            }

            override fun onLost(network: Network) {
                scope.launch(Dispatchers.IO) { closeSession(network) }
            }
        }
        try {
            connectivity.requestNetwork(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            // SecurityException without CHANGE_NETWORK_STATE, or too many outstanding requests.
            Log.w(TAG, "Wi-Fi network request failed: ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        networkCallback?.let { callback ->
            try { connectivity?.unregisterNetworkCallback(callback) } catch (_: Exception) { }
        }
        networkCallback = null
        synchronized(lock) {
            session?.close()
            session = null
        }
        transport.closeAll()
    }

    private fun openSession(network: Network) {
        synchronized(lock) {
            if (networkCallback == null) return
            if (session?.network == network) return
            session?.close()
            transport.closeAll()
            val next = Session(network)
            session = try {
                next.also { it.open() }
            } catch (e: Exception) {
                Log.w(TAG, "Could not open local Wi-Fi session: ${e.javaClass.simpleName}")
                next.close()
                null
            }
        }
    }

    private fun closeSession(network: Network) {
        synchronized(lock) {
            if (session?.network != network) return
            session?.close()
            session = null
            transport.closeAll()
        }
        Log.i(TAG, "Wi-Fi network lost; local mesh paused")
    }

    /** Everything tied to one Wi-Fi network: listener, advertisement, discovery and dialing. */
    private inner class Session(val network: Network) {
        private val sessionJob = SupervisorJob(scope.coroutineContext[Job])
        private val sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)
        private var serverSocket: ServerSocket? = null
        private var multicastLock: WifiManager.MulticastLock? = null
        private var registrationListener: NsdManager.RegistrationListener? = null
        private var discoveryListener: NsdManager.DiscoveryListener? = null

        @Volatile private var closed = false
        @Volatile private var registeredName: String = instanceName

        /** Discovered instance name -> resolved endpoint. */
        private val endpoints = ConcurrentHashMap<String, InetSocketAddress>()
        private val dialing = ConcurrentHashMap.newKeySet<String>()

        /** Pre-API-34 resolveService handles one request at a time, so queue them. */
        private val resolveQueue = ArrayDeque<NsdServiceInfo>()
        private var resolving = false

        fun open() {
            val server = ServerSocket(0)
            serverSocket = server
            sessionScope.launch(Dispatchers.IO) { acceptLoop(server) }
            acquireMulticastLockIfNeeded()
            register(server.localPort)
            discover()
            sessionScope.launch {
                while (isActive) {
                    delay(RECONNECT_INTERVAL_MS)
                    endpoints.forEach { (name, address) -> maybeDial(name, address, immediate = true) }
                }
            }
            Log.i(TAG, "Local Wi-Fi mesh listening and discovering")
        }

        fun close() {
            if (closed) return
            closed = true
            sessionJob.cancel()
            registrationListener?.let { try { nsd?.unregisterService(it) } catch (_: Exception) { } }
            registrationListener = null
            discoveryListener?.let { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) { } }
            discoveryListener = null
            try { serverSocket?.close() } catch (_: Exception) { }
            serverSocket = null
            multicastLock?.let { if (it.isHeld) try { it.release() } catch (_: Exception) { } }
            multicastLock = null
            endpoints.clear()
        }

        /**
         * The system mDNS stack moved into the connectivity module with a socket of its own in
         * Android 14; older releases can drop multicast replies while the Wi-Fi filter is on.
         */
        private fun acquireMulticastLockIfNeeded() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            try {
                multicastLock = wifi?.createMulticastLock("skein-lan-mesh")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Multicast lock unavailable: ${e.javaClass.simpleName}")
            }
        }

        private fun acceptLoop(server: ServerSocket) {
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                // The listener is bound to every interface; only Wi-Fi connections are admitted.
                if (!isOnThisNetwork(socket.localAddress) || transport.isFull()) {
                    try { socket.close() } catch (_: Exception) { }
                    continue
                }
                val link = try {
                    SocketLanLink(socket)
                } catch (_: Exception) {
                    try { socket.close() } catch (_: Exception) { }
                    continue
                }
                transport.attach(link, socket.inetAddress?.hostAddress)
            }
        }

        private fun isOnThisNetwork(local: InetAddress?): Boolean {
            if (local == null) return false
            val properties = try { connectivity?.getLinkProperties(network) } catch (_: Exception) { null }
                ?: return false
            return properties.linkAddresses.any { it.address == local }
        }

        private fun register(port: Int) {
            val info = NsdServiceInfo().apply {
                serviceName = instanceName
                serviceType = SERVICE_TYPE
                setPort(port)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setNetwork(this@Session.network)
                }
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    // The system renames on a collision; remember the name to skip ourselves.
                    serviceInfo.serviceName?.let { registeredName = it }
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Service registration failed ($errorCode)")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            }
            try {
                nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                registrationListener = listener
            } catch (e: Exception) {
                Log.w(TAG, "Service registration threw: ${e.javaClass.simpleName}")
            }
        }

        private fun discover() {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "Discovery failed to start ($errorCode)")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val name = serviceInfo.serviceName ?: return
                    if (closed || isSelf(name)) return
                    if (serviceInfo.serviceType?.contains("_skeinmesh") != true) return
                    resolve(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    serviceInfo.serviceName?.let { endpoints.remove(it) }
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    nsd?.discoverServices(
                        SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, network, callbackExecutor, listener
                    )
                } else {
                    nsd?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                }
                discoveryListener = listener
            } catch (e: Exception) {
                Log.w(TAG, "Discovery threw: ${e.javaClass.simpleName}")
            }
        }

        private fun isSelf(name: String) = name == instanceName || name == registeredName

        private fun resolve(info: NsdServiceInfo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                resolveWithCallback(info)
            } else {
                synchronized(resolveQueue) {
                    resolveQueue.addLast(info)
                    if (resolving) return
                    resolving = true
                }
                resolveNextLegacy()
            }
        }

        private fun resolveWithCallback(info: NsdServiceInfo) {
            val manager = nsd ?: return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            val callback = object : NsdManager.ServiceInfoCallback {
                private var done = false

                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
                override fun onServiceLost() = Unit

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    if (done) return
                    val address = pickAddress(serviceInfo.hostAddresses) ?: return
                    done = true
                    try { manager.unregisterServiceInfoCallback(this) } catch (_: Exception) { }
                    onResolved(serviceInfo.serviceName ?: info.serviceName, address, serviceInfo.port)
                }
            }
            try {
                manager.registerServiceInfoCallback(info, callbackExecutor, callback)
            } catch (e: Exception) {
                Log.d(TAG, "Resolve threw: ${e.javaClass.simpleName}")
            }
        }

        @Suppress("DEPRECATION")
        private fun resolveNextLegacy() {
            val next = synchronized(resolveQueue) {
                val item = resolveQueue.removeFirstOrNull()
                if (item == null || closed) {
                    resolving = false
                    resolveQueue.clear()
                    return
                }
                item
            }
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolveNextLegacy()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    serviceInfo.host?.let { host ->
                        onResolved(serviceInfo.serviceName ?: next.serviceName, host, serviceInfo.port)
                    }
                    resolveNextLegacy()
                }
            }
            try {
                nsd?.resolveService(next, listener)
            } catch (_: Exception) {
                resolveNextLegacy()
            }
        }

        private fun pickAddress(addresses: List<InetAddress>): InetAddress? =
            addresses.firstOrNull { it is Inet4Address }
                ?: addresses.firstOrNull { !it.isLinkLocalAddress }
                ?: addresses.firstOrNull()

        private fun onResolved(name: String?, host: InetAddress, port: Int) {
            if (closed || name == null || isSelf(name) || port !in 1..65535) return
            val address = InetSocketAddress(host, port)
            endpoints[name] = address
            maybeDial(name, address, immediate = false)
        }

        /**
         * Both devices discover each other, so only one should dial: the smaller instance name
         * dials at once and the larger waits, dialing only if no connection has appeared.
         */
        private fun maybeDial(name: String, address: InetSocketAddress, immediate: Boolean) {
            if (closed) return
            val hostText = address.address?.hostAddress ?: return
            if (transport.hasConnectionTo(hostText) || transport.isFull()) return
            if (!dialing.add(name)) return
            sessionScope.launch(Dispatchers.IO) {
                try {
                    if (!immediate && registeredName > name) delay(RESPONDER_DIAL_DELAY_MS)
                    if (closed || transport.hasConnectionTo(hostText) || transport.isFull()) return@launch
                    val socket = network.socketFactory.createSocket()
                    try {
                        socket.connect(address, CONNECT_TIMEOUT_MS)
                    } catch (e: Exception) {
                        try { socket.close() } catch (_: Exception) { }
                        Log.d(TAG, "Dial failed: ${e.javaClass.simpleName}")
                        return@launch
                    }
                    if (closed) {
                        try { socket.close() } catch (_: Exception) { }
                        return@launch
                    }
                    transport.attach(SocketLanLink(socket), hostText)
                } catch (e: Exception) {
                    Log.d(TAG, "Dial error: ${e.javaClass.simpleName}")
                } finally {
                    dialing.remove(name)
                }
            }
        }
    }
}
