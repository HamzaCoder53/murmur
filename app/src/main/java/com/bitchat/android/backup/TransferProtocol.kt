package com.bitchat.android.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** The other phone failed authentication, or spoke something else. */
class TransferAuthException(message: String) : IOException(message)

/**
 * One-time phone-to-phone move over the local network.
 *
 * The old phone shows a QR code ([Invitation]) with a fresh random 256-bit key, a short transfer
 * code and its local addresses. The QR never contains identity keys. Wire protocol on one TCP
 * connection (the key itself is never sent):
 * ```
 * new -> old  "SKMV1" | code (6 ASCII) | clientNonce (32)
 * old -> new  serverNonce (32) | HMAC(authKey, "server" | code | clientNonce | serverNonce)
 * new -> old  HMAC(authKey, "client" | code | clientNonce | serverNonce)
 * old -> new  length (8, big-endian) | archive bytes
 * new -> old  1 byte: 1 = archive authenticated and accepted, 0 = rejected
 * ```
 * authKey = HMAC-SHA256(qrKey, "skein move auth v1"); the archive is a normal `.skein` file
 * encrypted with raw key HMAC-SHA256(qrKey, "skein move archive v1"), so it is confidential and
 * tamper-evident even though the TCP connection itself is plain. Both sides authenticate each
 * other before any archive byte is sent.
 */
object TransferProtocol {
    const val SCHEME = "skein-move"
    const val PROTOCOL_VERSION = 1
    const val CODE_LENGTH = 6
    const val NONCE_BYTES = 32
    const val ACK_OK: Int = 1
    const val ACK_REJECTED: Int = 0
    /** Old phone keeps its listener open at most this long. */
    const val LISTEN_WINDOW_MS = 5 * 60 * 1000L
    const val MAX_FAILED_ATTEMPTS = 3
    private val MAGIC = "SKMV1".toByteArray(Charsets.US_ASCII)
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val ADDRESS = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}:[0-9]{1,5}$")

    data class Invitation(val key: ByteArray, val code: String, val addresses: List<String>) {
        fun encode(): String {
            val k = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key)
            return "$SCHEME:$PROTOCOL_VERSION?a=${addresses.joinToString(",")}&c=$code&k=$k"
        }

        override fun equals(other: Any?): Boolean = other is Invitation &&
            key.contentEquals(other.key) && code == other.code && addresses == other.addresses

        override fun hashCode(): Int = key.contentHashCode() * 31 + code.hashCode()
    }

    fun newInvitation(addresses: List<String>): Invitation {
        val bytes = SkeinCrypto.randomBytes(CODE_LENGTH)
        val code = bytes.map { CODE_ALPHABET[(it.toInt() and 0xFF) % CODE_ALPHABET.length] }.joinToString("")
        return Invitation(SkeinCrypto.randomBytes(SkeinCrypto.KEY_BYTES), code, addresses)
    }

    /** Parses a scanned QR string; null if it is not a valid invitation. */
    fun parseInvitation(text: String): Invitation? {
        val prefix = "$SCHEME:$PROTOCOL_VERSION?"
        if (!text.startsWith(prefix) || text.length > 512) return null
        val params = text.removePrefix(prefix).split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
        }.toMap()
        val key = try {
            java.util.Base64.getUrlDecoder().decode(params["k"] ?: return null)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val code = params["c"] ?: return null
        val addresses = params["a"]?.split(',')?.filter { it.isNotBlank() } ?: return null
        if (key.size != SkeinCrypto.KEY_BYTES) return null
        if (code.length != CODE_LENGTH || code.any { it !in CODE_ALPHABET }) return null
        if (addresses.isEmpty() || addresses.size > 4 || addresses.any { !ADDRESS.matches(it) }) return null
        if (addresses.any { a -> a.substringAfter(':').toInt() !in 1..65535 }) return null
        return Invitation(key, code, addresses)
    }

    fun authKey(qrKey: ByteArray) = SkeinCrypto.hmacSha256(qrKey, "skein move auth v1".toByteArray())
    fun archiveKey(qrKey: ByteArray) = SkeinCrypto.hmacSha256(qrKey, "skein move archive v1".toByteArray())

    private fun proof(authKey: ByteArray, role: String, code: String, cn: ByteArray, sn: ByteArray) =
        SkeinCrypto.hmacSha256(authKey, role.toByteArray(), code.toByteArray(Charsets.US_ASCII), cn, sn)

    /**
     * Old phone, one accepted connection. Returns true once the new phone acknowledged the
     * archive. Throws [TransferAuthException] if the peer does not know the key.
     */
    fun serve(
        input: InputStream,
        output: OutputStream,
        invitation: Invitation,
        archive: File,
        progress: ProgressListener? = null
    ): Boolean {
        val din = DataInputStream(input)
        val dout = DataOutputStream(output)
        val authKey = authKey(invitation.key)
        val magic = ByteArray(MAGIC.size)
        val code = ByteArray(CODE_LENGTH)
        val clientNonce = ByteArray(NONCE_BYTES)
        try {
            din.readFully(magic); din.readFully(code); din.readFully(clientNonce)
        } catch (e: EOFException) {
            throw TransferAuthException("Connection closed during handshake")
        }
        if (!magic.contentEquals(MAGIC) ||
            !MessageDigest.isEqual(code, invitation.code.toByteArray(Charsets.US_ASCII))
        ) {
            throw TransferAuthException("Wrong transfer code")
        }
        val serverNonce = SkeinCrypto.randomBytes(NONCE_BYTES)
        dout.write(serverNonce)
        dout.write(proof(authKey, "server", invitation.code, clientNonce, serverNonce))
        dout.flush()
        val clientProof = ByteArray(32)
        try {
            din.readFully(clientProof)
        } catch (e: EOFException) {
            throw TransferAuthException("Connection closed during handshake")
        }
        if (!MessageDigest.isEqual(clientProof, proof(authKey, "client", invitation.code, clientNonce, serverNonce))) {
            throw TransferAuthException("The other phone used the wrong key")
        }
        val total = archive.length()
        dout.writeLong(total)
        var sent = 0L
        archive.inputStream().use { file ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = file.read(buffer)
                if (n < 0) break
                dout.write(buffer, 0, n)
                sent += n
                progress?.onProgress(sent, total)
            }
        }
        dout.flush()
        return try { din.read() == ACK_OK } catch (_: IOException) { false }
    }

    /**
     * New phone. Authenticates, downloads the archive to [destination], calls [verify] (which must
     * fully read and authenticate it) and reports the result to the old phone. Returns [verify]'s
     * result; throws [TransferAuthException] if the old phone does not know the key.
     */
    fun <T : Any> receive(
        input: InputStream,
        output: OutputStream,
        invitation: Invitation,
        destination: File,
        progress: ProgressListener? = null,
        verify: (File) -> T
    ): T {
        val din = DataInputStream(input)
        val dout = DataOutputStream(output)
        val authKey = authKey(invitation.key)
        val clientNonce = SkeinCrypto.randomBytes(NONCE_BYTES)
        dout.write(MAGIC)
        dout.write(invitation.code.toByteArray(Charsets.US_ASCII))
        dout.write(clientNonce)
        dout.flush()
        val serverNonce = ByteArray(NONCE_BYTES)
        val serverProof = ByteArray(32)
        try {
            din.readFully(serverNonce); din.readFully(serverProof)
        } catch (e: EOFException) {
            throw TransferAuthException("The old phone refused the connection")
        }
        if (!MessageDigest.isEqual(serverProof, proof(authKey, "server", invitation.code, clientNonce, serverNonce))) {
            throw TransferAuthException("This is not the phone that showed the code")
        }
        dout.write(proof(authKey, "client", invitation.code, clientNonce, serverNonce))
        dout.flush()
        val total = try { din.readLong() } catch (e: EOFException) {
            throw TransferAuthException("The old phone refused the connection")
        }
        if (total < SkeinCrypto.HEADER_BYTES || total > SkeinArchive.MAX_ARCHIVE_BYTES) {
            throw SkeinFormatException("Transfer is too large")
        }
        destination.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            var received = 0L
            while (received < total) {
                val n = din.read(buffer, 0, minOf(buffer.size.toLong(), total - received).toInt())
                if (n < 0) throw SkeinFormatException("Transfer was interrupted")
                out.write(buffer, 0, n)
                received += n
                progress?.onProgress(received, total)
            }
        }
        val result = try {
            verify(destination)
        } catch (e: Exception) {
            try { dout.write(ACK_REJECTED); dout.flush() } catch (_: IOException) { }
            throw e
        }
        try { dout.write(ACK_OK); dout.flush() } catch (_: IOException) { }
        return result
    }
}
