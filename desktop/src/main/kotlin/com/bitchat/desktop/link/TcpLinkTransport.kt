package com.bitchat.desktop.link

import com.bitchat.android.linked.FrameIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Length-prefixed TCP frame pipe implementing [FrameIo] for LAN linking between desktop and
 * phone/watch. Uses 4-byte big-endian length prefixes capped at 16 MiB.
 */
class TcpLinkTransport(private val socket: Socket) : FrameIo, Closeable {
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
            if (len <= 0 || len > MAX_FRAME_BYTES) return@withContext null
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

        fun listen(port: Int = 0): ServerSocket = ServerSocket(port)

        suspend fun connect(host: String, port: Int, timeoutMs: Int = 5_000): TcpLinkTransport =
            withContext(Dispatchers.IO) {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                TcpLinkTransport(sock)
            }
    }
}
