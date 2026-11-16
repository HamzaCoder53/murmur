package com.bitchat.android.lan

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** One byte stream to a LAN peer. A TCP socket in production, an in-memory pipe in tests. */
interface LanLink {
    val input: InputStream
    val output: OutputStream

    /** Per-read timeout in milliseconds; 0 waits forever. */
    fun setReadTimeout(timeoutMs: Int) = Unit

    fun close()
}

class SocketLanLink(private val socket: Socket) : LanLink {
    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()

    init {
        try { socket.tcpNoDelay = true } catch (_: Exception) { }
        try { socket.keepAlive = true } catch (_: Exception) { }
    }

    override fun setReadTimeout(timeoutMs: Int) {
        socket.soTimeout = timeoutMs
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) { }
    }
}
