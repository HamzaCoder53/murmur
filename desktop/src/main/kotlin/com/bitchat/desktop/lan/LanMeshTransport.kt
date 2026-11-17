package com.bitchat.desktop.lan

import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.desktop.platform.LocalDiscovery
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Wi-Fi / LAN [MeshTransport] using length-prefixed TCP sockets and [LocalDiscovery] (mDNS)
 * to exchange [BitchatPacket]s with phones and other desktops on the local network.
 */
class LanMeshTransport(
    private val myPeerID: String,
    private val discovery: LocalDiscovery,
    private val onPacketReceived: (packet: BitchatPacket, senderPeerID: String, linkAddress: String) -> Unit,
) : MeshTransport {

    override val id: String = "lan"

    private val running = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap<String, Socket>()
    private val peerToAddr = ConcurrentHashMap<String, String>()
    private val addrToPeer = ConcurrentHashMap<String, String>()
    private val writeLock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var advHandle: AutoCloseable? = null
    @Volatile
    private var browseHandle: AutoCloseable? = null

    val listeningPort: Int
        get() = serverSocket?.localPort ?: 0

    fun start(bindPort: Int = 0) {
        if (!running.compareAndSet(false, true)) return
        val srv = ServerSocket(bindPort)
        serverSocket = srv
        thread(isDaemon = true, name = "skein-lan-accept") {
            while (running.get() && !srv.isClosed) {
                try {
                    val sock = srv.accept()
                    val addr = "${sock.inetAddress.hostAddress}:${sock.port}"
                    attachSocket(addr, sock)
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
        advHandle = discovery.advertise(SERVICE_TYPE, "skein-$myPeerID", srv.localPort)
        browseHandle = discovery.browse(
            SERVICE_TYPE,
            onFound = { svc ->
                if (svc.name == "skein-$myPeerID") return@browse
                val addr = "${svc.host}:${svc.port}"
                if (!sockets.containsKey(addr)) {
                    connectTo(svc.host, svc.port)
                }
            },
            onLost = { _ -> },
        )
    }

    fun connectTo(host: String, port: Int): Boolean {
        val addr = "$host:$port"
        if (sockets.containsKey(addr)) return true
        return try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), 3_000)
            attachSocket(addr, sock)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { advHandle?.close() } catch (_: Exception) { }
        try { browseHandle?.close() } catch (_: Exception) { }
        try { serverSocket?.close() } catch (_: Exception) { }
        for ((_, sock) in sockets) {
            try { sock.close() } catch (_: Exception) { }
        }
        sockets.clear()
        peerToAddr.clear()
        addrToPeer.clear()
    }

    override fun broadcastPacket(routed: RoutedPacket): Boolean {
        val bytes = routed.packet.toBinaryData(padding = false) ?: return false
        var sent = 0
        for ((addr, sock) in sockets) {
            if (addr == routed.relayAddress) continue
            if (writeFrame(sock, bytes)) sent++
        }
        return sent > 0
    }

    override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        val addr = peerToAddr[peerID] ?: return false
        val sock = sockets[addr] ?: return false
        val bytes = packet.toBinaryData(padding = false) ?: return false
        return writeFrame(sock, bytes)
    }

    override fun sendPacketToLink(relayAddress: String, ingressLinkID: String, packet: BitchatPacket): Boolean {
        val addr = ingressLinkID.ifEmpty { relayAddress }
        val sock = sockets[addr] ?: return false
        val bytes = packet.toBinaryData(padding = false) ?: return false
        return writeFrame(sock, bytes)
    }

    override fun getDeviceAddressForPeer(peerID: String): String? = peerToAddr[peerID]
    override fun getDeviceAddressToPeerMapping(): Map<String, String> = addrToPeer.toMap()

    private fun attachSocket(addr: String, sock: Socket) {
        sockets[addr] = sock
        thread(isDaemon = true, name = "skein-lan-read-$addr") {
            try {
                val input = DataInputStream(sock.getInputStream())
                while (running.get() && !sock.isClosed) {
                    val len = input.readInt()
                    if (len <= 0 || len > MAX_FRAME_BYTES) break
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    val pkt = BitchatPacket.fromBinaryData(buf) ?: continue
                    val senderPeerID = pkt.senderID.joinToString("") { "%02x".format(it) }
                    addrToPeer[addr] = senderPeerID
                    peerToAddr.putIfAbsent(senderPeerID, addr)
                    onPacketReceived(pkt, senderPeerID, addr)
                }
            } catch (_: Exception) {
            } finally {
                sockets.remove(addr, sock)
                val peer = addrToPeer.remove(addr)
                if (peer != null) peerToAddr.remove(peer, addr)
                try { sock.close() } catch (_: Exception) { }
            }
        }
    }

    private fun writeFrame(sock: Socket, bytes: ByteArray): Boolean = synchronized(writeLock) {
        if (bytes.size > MAX_FRAME_BYTES || sock.isClosed) return false
        return try {
            val out = DataOutputStream(sock.getOutputStream())
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val SERVICE_TYPE = "_skein-lan._tcp.local."
        const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    }
}
