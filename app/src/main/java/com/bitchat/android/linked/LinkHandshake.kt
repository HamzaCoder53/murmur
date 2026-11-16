package com.bitchat.android.linked

import org.json.JSONObject

/** A bidirectional frame pipe (a TCP socket in the app, an in-memory queue in tests). */
interface FrameIo {
    /** Returns false when the frame could not be written (the pipe is closed). */
    suspend fun send(frame: ByteArray): Boolean

    /** Next frame, or null once the pipe is closed. */
    suspend fun receive(): ByteArray?

    fun close()
}

/**
 * Mutual authentication for the linked-device channel, keyed by a pre-shared 256-bit secret.
 *
 * The key is either the one-time secret shown in a pairing QR code ([Mode.PAIR]) or the long-term
 * key two devices derived when they were paired ([Mode.LINK]). Neither side reveals the key or
 * anything derived from it to someone who does not hold it, and both prove possession over fresh
 * nonces, so a recorded handshake cannot be replayed:
 *
 * ```
 * client -> server  hello     {mode, client device ID, nc}
 * server -> client  challenge {server device ID, ns, HMAC(K', "server" | transcript)}
 * client -> server  auth      {HMAC(K', "client" | transcript)}
 * ```
 *
 * `K' = HKDF(K, nc|ns)`. Session keys for each direction, and in pair mode the new long-term link
 * key, are derived from K and both nonces. Anything that does not follow this exact sequence ends
 * the handshake with [Result.Failed]; nothing is decrypted or acted on before it succeeds.
 *
 * This is a Skein-only side channel between the user's own devices. It never touches the Bitchat
 * mesh protocol, so official Bitchat clients neither see nor need to understand it.
 */
object LinkHandshake {
    const val VERSION = 1
    private const val LABEL = "skein-link-v1"

    enum class Mode(val code: String) { PAIR("pair"), LINK("link") }

    enum class Failure { MALFORMED, UNKNOWN_DEVICE, NO_OFFER, BAD_PROOF, UNEXPECTED_DEVICE, CLOSED }

    sealed interface Result {
        class Ok(
            val mode: Mode,
            val peerDeviceId: String,
            val cipher: FrameCipher,
            /** Pair mode only: the long-term key both devices now share. */
            val linkKey: ByteArray?
        ) : Result

        data class Failed(val reason: Failure) : Result
    }

    /** What a listening device can authenticate against. */
    interface ServerKeys {
        /** The secret of the pairing QR code currently shown, if any. */
        fun pairingSecret(): ByteArray?

        /** Long-term key shared with [deviceId], if that device is linked (or awaits a wipe). */
        fun linkKey(deviceId: String): ByteArray?
    }

    fun isDeviceId(value: String?): Boolean =
        value != null && value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }

    suspend fun runClient(
        io: FrameIo,
        myDeviceId: String,
        mode: Mode,
        /** Pair mode: the secret from the QR code. Link mode: looked up per server device. */
        key: (serverDeviceId: String) -> ByteArray?,
        /** Pair mode: the device ID in the QR code; a different server is refused. */
        expectedServerId: String? = null
    ): Result = runClientInner(io, myDeviceId, mode, key, expectedServerId).also {
        if (it is Result.Failed) io.close()
    }

    private suspend fun runClientInner(
        io: FrameIo,
        myDeviceId: String,
        mode: Mode,
        key: (serverDeviceId: String) -> ByteArray?,
        expectedServerId: String?
    ): Result {
        val nc = LinkCrypto.randomBytes(LinkCrypto.NONCE_BYTES)
        val hello = JSONObject()
            .put("v", VERSION).put("t", "hello").put("mode", mode.code)
            .put("dev", myDeviceId).put("nc", nc.b64())
        if (!io.send(hello.bytes())) return Result.Failed(Failure.CLOSED)

        val challenge = io.receive()?.json() ?: return Result.Failed(Failure.CLOSED)
        if (challenge.optInt("v") != VERSION || challenge.optString("t") != "challenge") {
            return Result.Failed(Failure.MALFORMED)
        }
        val serverId = challenge.optString("dev")
        val ns = challenge.optString("ns").unb64()
        val serverProof = challenge.optString("mac").unb64()
        if (!isDeviceId(serverId) || ns?.size != LinkCrypto.NONCE_BYTES || serverProof == null) {
            return Result.Failed(Failure.MALFORMED)
        }
        if (expectedServerId != null && serverId != expectedServerId) return Result.Failed(Failure.UNEXPECTED_DEVICE)
        if (serverId == myDeviceId) return Result.Failed(Failure.UNEXPECTED_DEVICE)
        val k = key(serverId) ?: return Result.Failed(Failure.UNKNOWN_DEVICE)

        val transcript = transcript(mode, myDeviceId, serverId)
        val macKey = LinkCrypto.hkdf(k, nc + ns, "$LABEL mac")
        val expected = LinkCrypto.hmac(macKey, "server".toByteArray(), transcript, nc, ns)
        if (!LinkCrypto.constantTimeEquals(expected, serverProof)) return Result.Failed(Failure.BAD_PROOF)

        val proof = LinkCrypto.hmac(macKey, "client".toByteArray(), transcript, nc, ns)
        val auth = JSONObject().put("v", VERSION).put("t", "auth").put("mac", proof.b64())
        if (!io.send(auth.bytes())) return Result.Failed(Failure.CLOSED)

        val keys = sessionKeys(k, nc, ns, transcript)
        return Result.Ok(
            mode = mode,
            peerDeviceId = serverId,
            cipher = FrameCipher(sendKey = keys.c2s, receiveKey = keys.s2c),
            linkKey = if (mode == Mode.PAIR) keys.pairKey else null
        )
    }

    suspend fun runServer(io: FrameIo, myDeviceId: String, keys: ServerKeys): Result =
        runServerInner(io, myDeviceId, keys).also {
            if (it is Result.Failed) io.close()
        }

    private suspend fun runServerInner(io: FrameIo, myDeviceId: String, keys: ServerKeys): Result {
        val hello = io.receive()?.json() ?: return Result.Failed(Failure.CLOSED)
        if (hello.optInt("v") != VERSION || hello.optString("t") != "hello") return Result.Failed(Failure.MALFORMED)
        val mode = Mode.entries.firstOrNull { it.code == hello.optString("mode") }
            ?: return Result.Failed(Failure.MALFORMED)
        val clientId = hello.optString("dev")
        val nc = hello.optString("nc").unb64()
        if (!isDeviceId(clientId) || nc?.size != LinkCrypto.NONCE_BYTES) return Result.Failed(Failure.MALFORMED)
        if (clientId == myDeviceId) return Result.Failed(Failure.UNEXPECTED_DEVICE)

        val k = when (mode) {
            Mode.PAIR -> keys.pairingSecret() ?: return Result.Failed(Failure.NO_OFFER)
            Mode.LINK -> keys.linkKey(clientId) ?: return Result.Failed(Failure.UNKNOWN_DEVICE)
        }
        val ns = LinkCrypto.randomBytes(LinkCrypto.NONCE_BYTES)
        val transcript = transcript(mode, clientId, myDeviceId)
        val macKey = LinkCrypto.hkdf(k, nc + ns, "$LABEL mac")
        val proof = LinkCrypto.hmac(macKey, "server".toByteArray(), transcript, nc, ns)
        val challenge = JSONObject()
            .put("v", VERSION).put("t", "challenge")
            .put("dev", myDeviceId).put("ns", ns.b64()).put("mac", proof.b64())
        if (!io.send(challenge.bytes())) return Result.Failed(Failure.CLOSED)

        val auth = io.receive()?.json() ?: return Result.Failed(Failure.CLOSED)
        if (auth.optInt("v") != VERSION || auth.optString("t") != "auth") return Result.Failed(Failure.MALFORMED)
        val clientProof = auth.optString("mac").unb64() ?: return Result.Failed(Failure.MALFORMED)
        val expected = LinkCrypto.hmac(macKey, "client".toByteArray(), transcript, nc, ns)
        if (!LinkCrypto.constantTimeEquals(expected, clientProof)) return Result.Failed(Failure.BAD_PROOF)

        val session = sessionKeys(k, nc, ns, transcript)
        return Result.Ok(
            mode = mode,
            peerDeviceId = clientId,
            cipher = FrameCipher(sendKey = session.s2c, receiveKey = session.c2s),
            linkKey = if (mode == Mode.PAIR) session.pairKey else null
        )
    }

    private class SessionKeys(val c2s: ByteArray, val s2c: ByteArray, val pairKey: ByteArray)

    private fun sessionKeys(k: ByteArray, nc: ByteArray, ns: ByteArray, transcript: ByteArray): SessionKeys {
        val salt = LinkCrypto.hmac(nc + ns, transcript)
        return SessionKeys(
            c2s = LinkCrypto.hkdf(k, salt, "$LABEL c2s"),
            s2c = LinkCrypto.hkdf(k, salt, "$LABEL s2c"),
            pairKey = LinkCrypto.hkdf(k, salt, "$LABEL link-key")
        )
    }

    private fun transcript(mode: Mode, clientId: String, serverId: String): ByteArray =
        "$LABEL|${mode.code}|$clientId|$serverId".toByteArray(Charsets.UTF_8)

    private fun JSONObject.bytes() = toString().toByteArray(Charsets.UTF_8)

    private fun ByteArray.json(): JSONObject? = try {
        JSONObject(String(this, Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }
}

internal fun ByteArray.b64(): String = java.util.Base64.getEncoder().encodeToString(this)

internal fun String.unb64(): ByteArray? = try {
    if (isEmpty()) null else java.util.Base64.getDecoder().decode(this)
} catch (_: Exception) {
    null
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
