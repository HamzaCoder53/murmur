package com.bitchat.android.backup

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.bitchat.android.linked.FrameIo
import com.bitchat.android.linked.PasskeyPairing
import kotlinx.coroutines.delay
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Move to a new phone" by typing a 6-digit code instead of scanning the QR code.
 *
 * The old phone advertises the move on the local network (DNS-SD `_skeinmove._tcp`). The new phone
 * finds it, both run [PasskeyPairing] with the code, and the old phone then sends its QR invitation
 * encrypted with the secret that produced. From there the transfer is exactly the QR one.
 */
object TransferCode {
    private const val TAG = "TransferCode"
    const val SERVICE_TYPE = "_skeinmove._tcp."
    private const val MAX_FRAME = 4096
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val SEARCH_MS = 20_000L

    /** Length-prefixed frames over a socket's streams, for [PasskeyPairing]. */
    class StreamFrameIo(private val input: DataInputStream, private val output: DataOutputStream) : FrameIo {
        override suspend fun send(frame: ByteArray): Boolean = try {
            output.writeInt(frame.size)
            output.write(frame)
            output.flush()
            true
        } catch (_: IOException) {
            false
        }

        override suspend fun receive(): ByteArray? = try {
            val size = input.readInt()
            if (size !in 1..MAX_FRAME) null else ByteArray(size).also(input::readFully)
        } catch (_: IOException) {
            null
        }

        override fun close() = Unit
    }

    // --- Wrapping the invitation with the code-derived secret ---

    fun seal(secret: ByteArray, invitation: TransferProtocol.Invitation): ByteArray {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, nonce))
        return nonce + cipher.doFinal(invitation.encode().toByteArray(Charsets.UTF_8))
    }

    fun open(secret: ByteArray, sealed: ByteArray): TransferProtocol.Invitation? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        TransferProtocol.parseInvitation(String(cipher.doFinal(sealed.copyOfRange(12, sealed.size)), Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    // --- Old phone ---

    /** Advertise the move on the local network; returns a function that stops advertising. */
    fun advertise(context: Context, port: Int): () -> Unit {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return {}
        val info = NsdServiceInfo().apply {
            serviceName = "sm-" + ByteArray(6).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            serviceType = SERVICE_TYPE
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Move advertisement failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Move advertisement failed: ${e.javaClass.simpleName}")
            return {}
        }
        return { runCatching { nsd.unregisterService(listener) } }
    }

    // --- New phone ---

    /**
     * Find the old phone showing [code] and return its invitation plus the open socket, ready for
     * [TransferProtocol.receive]. Null when nothing on this network accepted the code.
     */
    suspend fun find(context: Context, code: String): Pair<TransferProtocol.Invitation, Socket>? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val found = ConcurrentHashMap<String, Pair<String, Int>>()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            found[info.serviceName] = host to info.port
                        }
                    })
                }
            }
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) {
            Log.w(TAG, "Move discovery failed: ${e.javaClass.simpleName}")
            return null
        }
        try {
            val network = TransferNetwork.wifiNetwork(context)
            val tried = HashSet<String>()
            val deadline = System.currentTimeMillis() + SEARCH_MS
            while (System.currentTimeMillis() < deadline) {
                for ((name, address) in found.entries.toList()) {
                    if (!tried.add(name)) continue
                    val socket = try { network?.socketFactory?.createSocket() ?: Socket() } catch (_: Exception) { Socket() }
                    try {
                        socket.connect(InetSocketAddress(address.first, address.second), CONNECT_TIMEOUT_MS)
                        socket.soTimeout = 30_000
                        val io = StreamFrameIo(DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()))
                        when (val result = PasskeyPairing.runInitiator(io, code)) {
                            is PasskeyPairing.Result.Ok -> {
                                val invitation = io.receive()?.let { open(result.secret, it) }
                                if (invitation != null) return invitation to socket
                                socket.close()
                            }
                            is PasskeyPairing.Result.Failed -> {
                                socket.close()
                                // The right phone, wrong code: stop, the code is now spent.
                                if (result.reason == PasskeyPairing.Failure.WRONG_CODE) return null
                            }
                        }
                    } catch (_: Exception) {
                        runCatching { socket.close() }
                    }
                }
                delay(500)
            }
            return null
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
    }
}
