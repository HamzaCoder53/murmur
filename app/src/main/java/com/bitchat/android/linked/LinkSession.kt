package com.bitchat.android.linked

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An authenticated, encrypted session with one linked device.
 *
 * Fail-closed: the first frame that does not decrypt as the next authentic frame (tampered,
 * replayed, reordered, plaintext or keyed for someone else) ends the session. Nothing from such a
 * frame is ever decoded.
 */
class LinkSession(
    val peerDeviceId: String,
    private val io: FrameIo,
    private val cipher: FrameCipher,
    /** True when this device dialed (the TCP client). Used to drop duplicate connections. */
    val dialed: Boolean
) {
    private val sendLock = Mutex()

    @Volatile
    var closed = false
        private set

    suspend fun send(frame: LinkFrame): Boolean {
        if (closed) return false
        return sendLock.withLock {
            // Seal under the lock so counters reach the wire in order.
            !closed && io.send(cipher.seal(LinkProtocol.encode(frame)))
        }.also { if (!it) close() }
    }

    /** The next frame, or null once the session ended (closed, or an inauthentic frame arrived). */
    suspend fun receive(): LinkFrame? {
        while (!closed) {
            val raw = io.receive() ?: break
            val plain = cipher.open(raw)
            if (plain == null) {
                close()
                break
            }
            // Authentic but unknown to this version: skipped, so newer peers stay compatible.
            LinkProtocol.decode(plain)?.let { return it }
        }
        close()
        return null
    }

    fun close() {
        closed = true
        io.close()
    }
}
