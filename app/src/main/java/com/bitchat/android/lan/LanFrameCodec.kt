package com.bitchat.android.lan

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

/** A LAN peer sent something that is not a well-formed frame; the connection must be dropped. */
class LanFrameException(message: String) : IOException(message)

/**
 * Wire framing for the local Wi-Fi mesh transport.
 *
 * Each frame is a 4-byte big-endian length followed by exactly that many bytes. A non-empty frame
 * carries the BinaryProtocol encoding of one BitchatPacket, byte-for-byte what the BLE transport
 * writes. A zero-length frame is a keepalive and carries nothing.
 *
 * Packets are fragmented above 512 bytes before they reach a transport, so [MAX_FRAME_BYTES] is
 * generous; anything larger, or a negative length, means the peer is not speaking this protocol.
 */
object LanFrameCodec {
    const val HEADER_BYTES = 4
    const val MAX_FRAME_BYTES = 64 * 1024

    /** The zero-length keepalive frame. */
    val KEEPALIVE: ByteArray get() = ByteArray(HEADER_BYTES)

    /** Frames [payload], or returns null when it is empty or larger than [MAX_FRAME_BYTES]. */
    fun encode(payload: ByteArray): ByteArray? {
        if (payload.isEmpty() || payload.size > MAX_FRAME_BYTES) return null
        val frame = ByteArray(HEADER_BYTES + payload.size)
        val length = payload.size
        frame[0] = (length ushr 24).toByte()
        frame[1] = (length ushr 16).toByte()
        frame[2] = (length ushr 8).toByte()
        frame[3] = length.toByte()
        System.arraycopy(payload, 0, frame, HEADER_BYTES, length)
        return frame
    }

    /**
     * Reads one frame. Returns null on a clean end of stream (between frames), an empty array for
     * a keepalive, and the payload otherwise.
     *
     * @throws LanFrameException on an out-of-range length or a stream that ends inside a frame.
     * @throws IOException for underlying socket failures, including read timeouts.
     */
    fun readFrame(input: DataInputStream): ByteArray? {
        val first = input.read()
        if (first < 0) return null
        val length = try {
            (first shl 24) or
                (input.readUnsignedByte() shl 16) or
                (input.readUnsignedByte() shl 8) or
                input.readUnsignedByte()
        } catch (_: EOFException) {
            throw LanFrameException("Stream ended inside a frame header")
        }
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw LanFrameException("Frame length $length outside 0..$MAX_FRAME_BYTES")
        }
        if (length == 0) return ByteArray(0)
        val payload = ByteArray(length)
        try {
            input.readFully(payload)
        } catch (_: EOFException) {
            throw LanFrameException("Stream ended inside a $length-byte frame")
        }
        return payload
    }
}
