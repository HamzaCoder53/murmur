package com.bitchat.desktop.link

import com.bitchat.android.linked.FrameIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Length-prefixed TCP frame pipe implementing [FrameIo] for LAN linking between desktop and
 * phone/watch. Uses 4-byte big-endian length prefixes.
 *
 * Until the link handshake has authenticated the other side ([markAuthenticated]), incoming
 * frames are capped at [PRE_AUTH_MAX_FRAME_BYTES] so an unauthenticated peer cannot make us
 * allocate large buffers; after that the cap is [MAX_FRAME_BYTES]. Reads time out after
 * [readTimeoutMs] so a silent peer cannot hold the link open forever.
 */
class TcpLinkTransport(
    private val socket: Socket,
    readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : FrameIo, Closeable {
    init {
        socket.soTimeout = readTimeoutMs
    }

    @Volatile
    private var maxIncoming = PRE_AUTH_MAX_FRAME_BYTES

    /** Call once the link handshake has verified the peer, to allow full-size frames. */
    fun markAuthenticated() {
        maxIncoming = MAX_FRAME_BYTES
    }

    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    private val writeLock = Any()

    override suspend fun send(frame: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (frame.size > MAX_FRAME_BYTES) return@withContext false
        synchronized(writeLock) {
            try {
                output.writeInt(frame.size)
                output.write(frame)
                output.flush()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val len = input.readInt()
            if (len <= 0 || len > maxIncoming) return@withContext null
            val buf = ByteArray(len)
            input.readFully(buf)
            buf
        } catch (_: EOFException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) { }
    }

    companion object {
        const val MAX_FRAME_BYTES = 16 * 1024 * 1024
        const val PRE_AUTH_MAX_FRAME_BYTES = 8 * 1024
        const val DEFAULT_READ_TIMEOUT_MS = 60_000

        /**
         * Listens on [bindAddress] only (the chosen LAN interface address). There is no default:
         * callers must pick an interface rather than exposing the port on every network.
         */
        fun listen(bindAddress: InetAddress, port: Int = 0): ServerSocket =
            ServerSocket(port, 4, bindAddress)

        suspend fun connect(host: String, port: Int, timeoutMs: Int = 5_000): TcpLinkTransport =
            withContext(Dispatchers.IO) {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                TcpLinkTransport(sock)
            }
    }
}
