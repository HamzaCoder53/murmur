package com.bitchat.android.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Sockets for the one-time phone move. Supported layouts: both phones on the same Wi-Fi network,
 * or the new phone joined to the old phone's hotspot (or the other way round). Only private IPv4
 * addresses of Wi-Fi/hotspot interfaces are advertised; no internet is used.
 */
object TransferNetwork {
    private const val TAG = "TransferNetwork"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 30_000
    private val WIFI_INTERFACE_PREFIXES = listOf("wlan", "swlan", "ap", "softap", "wifi", "eth")

    /** The Wi-Fi network, if this phone is on one (it may also be a hotspot instead). */
    @Suppress("DEPRECATION")
    fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            cm.allNetworks.firstOrNull { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (_: Exception) { null }
    }

    /** Private IPv4 addresses the new phone can reach: the Wi-Fi network first, then hotspot. */
    fun localAddresses(context: Context): List<InetAddress> {
        val result = LinkedHashSet<InetAddress>()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        wifiNetwork(context)?.let { network ->
            try {
                cm?.getLinkProperties(network)?.linkAddresses?.map { it.address }
                    ?.filter { it is Inet4Address && it.isSiteLocalAddress }
                    ?.let(result::addAll)
            } catch (_: Exception) { }
        }
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && WIFI_INTERFACE_PREFIXES.any { p -> it.name.startsWith(p) } }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && it.isSiteLocalAddress }
                .let(result::addAll)
        } catch (_: Exception) { }
        return result.take(3)
    }

    /**
     * Old phone: listens on [addresses] until one connection authenticates and receives
     * [archive], the window closes, or too many connections fail authentication. Returns true
     * once the new phone confirmed the archive. Cancelling the coroutine closes the listener.
     */
    suspend fun serve(
        invitation: TransferProtocol.Invitation,
        server: ServerSocket,
        allowed: Set<InetAddress>,
        archive: File,
        onConnected: () -> Unit,
        progress: ProgressListener
    ): Boolean = withContext(Dispatchers.IO) {
        val handle = currentCoroutineContext().job.invokeOnCompletion { try { server.close() } catch (_: IOException) { } }
        try {
            val deadline = System.currentTimeMillis() + TransferProtocol.LISTEN_WINDOW_MS
            var failures = 0
            while (failures < TransferProtocol.MAX_FAILED_ATTEMPTS) {
                currentCoroutineContext().ensureActive()
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return@withContext false
                server.soTimeout = remaining.toInt()
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    return@withContext false
                }
                socket.use { s ->
                    // The listener is bound to every interface; only the advertised ones are admitted.
                    if (allowed.isNotEmpty() && s.localAddress !in allowed) return@use
                    s.soTimeout = READ_TIMEOUT_MS
                    try {
                        onConnected()
                        val ok = TransferProtocol.serve(s.getInputStream(), s.getOutputStream(), invitation, archive, progress)
                        return@withContext ok
                    } catch (e: TransferAuthException) {
                        Log.w(TAG, "Rejected a transfer connection: ${e.message}")
                        failures++
                    }
                }
            }
            false
        } finally {
            handle.dispose()
            try { server.close() } catch (_: IOException) { }
        }
    }

    /** New phone: connects to the first reachable address in [invitation]. */
    suspend fun connect(context: Context, invitation: TransferProtocol.Invitation): Socket = withContext(Dispatchers.IO) {
        val network = wifiNetwork(context)
        var last: Exception? = null
        for (address in invitation.addresses) {
            currentCoroutineContext().ensureActive()
            val host = address.substringBefore(':')
            val port = address.substringAfter(':').toInt()
            val socket = try { network?.socketFactory?.createSocket() ?: Socket() } catch (_: Exception) { Socket() }
            try {
                socket.connect(InetSocketAddress(InetAddress.getByName(host), port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                return@withContext socket
            } catch (e: Exception) {
                last = e
                try { socket.close() } catch (_: IOException) { }
            }
        }
        throw IOException("Could not reach the old phone", last)
    }
}
