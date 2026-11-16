package com.bitchat.android.lan

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class LanFrameCodecTest {

    private fun stream(vararg chunks: ByteArray): DataInputStream {
        val all = ByteArrayOutputStream()
        chunks.forEach { all.write(it) }
        return DataInputStream(ByteArrayInputStream(all.toByteArray()))
    }

    private fun header(length: Int) = byteArrayOf(
        (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()
    )

    private fun assertRejected(input: DataInputStream) {
        try {
            LanFrameCodec.readFrame(input)
            fail("expected LanFrameException")
        } catch (_: LanFrameException) {
        }
    }

    @Test
    fun `a packet survives a frame round trip byte for byte`() {
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 7u,
            senderID = "1111111111111111",
            payload = "synthetic test message".toByteArray()
        )
        val bytes = packet.toBinaryData()!!

        val frame = LanFrameCodec.encode(bytes)!!
        assertEquals(LanFrameCodec.HEADER_BYTES + bytes.size, frame.size)

        val decoded = LanFrameCodec.readFrame(stream(frame))!!
        assertArrayEquals(bytes, decoded)
        val roundTripped = BitchatPacket.fromBinaryData(decoded)
        assertNotNull(roundTripped)
        assertArrayEquals(packet.payload, roundTripped!!.payload)
        assertEquals(packet.type, roundTripped.type)
    }

    @Test
    fun `consecutive frames and keepalives are read in order`() {
        val first = byteArrayOf(1, 2, 3)
        val second = ByteArray(LanFrameCodec.MAX_FRAME_BYTES) { it.toByte() }
        val input = stream(
            LanFrameCodec.encode(first)!!,
            LanFrameCodec.KEEPALIVE,
            LanFrameCodec.encode(second)!!
        )

        assertArrayEquals(first, LanFrameCodec.readFrame(input))
        assertEquals(0, LanFrameCodec.readFrame(input)!!.size)
        assertArrayEquals(second, LanFrameCodec.readFrame(input))
        assertNull("clean end of stream between frames", LanFrameCodec.readFrame(input))
    }

    @Test
    fun `encode refuses empty and oversize payloads`() {
        assertNull(LanFrameCodec.encode(ByteArray(0)))
        assertNull(LanFrameCodec.encode(ByteArray(LanFrameCodec.MAX_FRAME_BYTES + 1)))
    }

    @Test
    fun `an oversize length is rejected before any payload is read`() {
        assertRejected(stream(header(LanFrameCodec.MAX_FRAME_BYTES + 1)))
    }

    @Test
    fun `a negative length is rejected`() {
        assertRejected(stream(byteArrayOf(0x80.toByte(), 0, 0, 1)))
    }

    @Test
    fun `text from a foreign protocol is rejected`() {
        assertRejected(stream("GET / HTTP/1.1\r\n\r\n".toByteArray()))
    }

    @Test
    fun `a stream ending inside a header or payload is rejected`() {
        assertRejected(stream(byteArrayOf(0, 0)))
        assertRejected(stream(header(10), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `keepalive is a zero length header`() {
        assertTrue(LanFrameCodec.KEEPALIVE.all { it == 0.toByte() })
        assertEquals(LanFrameCodec.HEADER_BYTES, LanFrameCodec.KEEPALIVE.size)
    }
}
