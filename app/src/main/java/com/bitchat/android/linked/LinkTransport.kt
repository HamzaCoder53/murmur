package com.bitchat.android.linked

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import java.net.Socket

/**
 * Local Wi-Fi / LAN discovery (NSD `_skeinlink._tcp.`) for linked devices. Sockets, pairing and
 * redialing live in the platform-neutral [LinkSocketEngine], which desktop shares.
 */
class LinkTransport(
    context: Context,
    scope: CoroutineScope,
    store: LinkStore,
    coordinator: LinkCoordinator,
    /** New outgoing sockets; a watch passes its Wi-Fi network's factory (its default may be Bluetooth). */
    newSocket: () -> Socket = { Socket() }
) {
    companion object {
        private const val TAG = "LinkTransport"
        private const val SERVICE_TYPE = LinkSocketEngine.SERVICE_TYPE
    }

    private val engine = LinkSocketEngine(scope, store, coordinator, newSocket)

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    val listeningPort: Int? get() = engine.listeningPort

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Synchronized
    fun start() {
        if (engine.isRunning()) return
        val port = engine.start()
        if (port == null) {
            Log.w(TAG, "Failed to start link transport")
            stop()
            return
        }
        registerNsd(port)
        startDiscovery()
        Log.i(TAG, "Link transport listening on port $port")
    }

    @Synchronized
    fun stop() {
        engine.stop()
        stopDiscovery()
        unregisterNsd()
    }

    fun isRunning(): Boolean = engine.isRunning()

    fun localHostHint(): String? = engine.localHostHint()

    /** Connect to an existing device using a scanned QR payload and run pairing. */
    fun connectForPairing(qr: LinkQr.Payload) = engine.connectForPairing(qr)

    /** Pair by typing the 6-digit code shown on the existing device (same Wi-Fi). */
    fun connectWithCode(code: String) = engine.connectWithCode(code)

    // --- NSD ---

    private fun registerNsd(port: Int) {
        val mgr = nsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = engine.serviceName
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
                if (LinkSocketEngine.extractDeviceId(serviceInfo.serviceName) == null) return
                if (serviceInfo.serviceName == engine.serviceName) return
                resolveService(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                engine.onServiceLost(serviceInfo.serviceName)
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
                    val host = info.host?.hostAddress ?: return
                    engine.onServiceResolved(info.serviceName, host, info.port)
                }
            })
        } catch (_: Exception) {
        }
    }
}
