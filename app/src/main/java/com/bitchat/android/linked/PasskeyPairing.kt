package com.bitchat.android.linked

import com.bitchat.android.noise.southernstorm.crypto.Curve25519
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/**
 * Pairing by typing a 6-digit code shown on the other device, instead of scanning a QR code.
 *
 * Modelled on Bluetooth LE Secure Connections "passkey entry": the two devices exchange fresh
 * X25519 keys, then prove knowledge of the code one bit at a time. For each of the 20 bits each
 * side first commits (HMAC over both public keys and the bit, keyed by a fresh nonce) and only
 * then reveals its nonce, so neither side can learn a bit before committing to its own. Someone in
 * the middle can learn at most about one bit before being caught, and a failed attempt burns the
 * code ([LinkCoordinator] withdraws it), so guessing gets one try per code.
 *
 * The output is a 256-bit secret derived from the X25519 result and both public keys. It is used
 * exactly like the secret in a pairing QR code, so the regular [LinkHandshake] still runs on top
 * and confirms both sides hold it.
 */
object PasskeyPairing {
    const val CODE_DIGITS = 6
    const val BITS = 20 // 10^6 < 2^20
    private const val VERSION = 1
    private const val NONCE_BYTES = 16
    private const val LABEL = "skein-passkey-v1"

    private val random = SecureRandom()

    sealed interface Result {
        class Ok(val secret: ByteArray) : Result
        data class Failed(val reason: Failure) : Result
    }

    enum class Failure { MALFORMED, NO_OFFER, WRONG_CODE, CLOSED }

    fun newCode(): String = (random.nextInt(1_000_000)).toString().padStart(CODE_DIGITS, '0')

    fun isCode(value: String): Boolean = value.length == CODE_DIGITS && value.all(Char::isDigit)

    /** True if [frame] opens a passkey pairing (so a server knows which protocol it is talking). */
    fun isHello(frame: ByteArray): Boolean = try {
        JSONObject(String(frame, Charsets.UTF_8)).optString("t") == "pk-hello"
    } catch (_: Exception) {
        false
    }

    /** Device typing the code. */
    suspend fun runInitiator(io: FrameIo, code: String): Result {
        if (!isCode(code)) return Result.Failed(Failure.MALFORMED)
        val sk = privateKey()
        val pk = publicKey(sk)
        if (!io.send(json("pk-hello").put("pk", pk.b64()).bytes())) return Result.Failed(Failure.CLOSED)
        val reply = io.receive()?.json() ?: return Result.Failed(Failure.CLOSED)
        if (reply.optString("t") == "pk-none") return Result.Failed(Failure.NO_OFFER)
        if (reply.optString("t") != "pk-key") return Result.Failed(Failure.MALFORMED)
        val peerPk = reply.optString("pk").unb64()?.takeIf { it.size == 32 } ?: return Result.Failed(Failure.MALFORMED)
        val bits = bitsOf(code)
        for (i in 0 until BITS) {
            val bit = bits[i]
            val myNonce = nonce()
            val myCommit = commit(myNonce, pk, peerPk, bit)
            if (!io.send(json("pk-commit").put("c", myCommit.b64()).bytes())) return Result.Failed(Failure.CLOSED)
            val theirCommit = io.receive()?.json()?.takeIf { it.optString("t") == "pk-commit" }
                ?.optString("c")?.unb64() ?: return Result.Failed(Failure.MALFORMED)
            if (!io.send(json("pk-reveal").put("n", myNonce.b64()).bytes())) return Result.Failed(Failure.CLOSED)
            val theirReveal = io.receive()?.json() ?: return Result.Failed(Failure.CLOSED)
            if (theirReveal.optString("t") == "pk-fail") return Result.Failed(Failure.WRONG_CODE)
            val theirNonce = theirReveal.takeIf { it.optString("t") == "pk-reveal" }
                ?.optString("n")?.unb64() ?: return Result.Failed(Failure.MALFORMED)
            if (!LinkCrypto.constantTimeEquals(theirCommit, commit(theirNonce, peerPk, pk, bit))) {
                io.send(json("pk-fail").bytes())
                return Result.Failed(Failure.WRONG_CODE)
            }
        }
        return Result.Ok(secret(sk, peerPk, initiatorPk = pk, responderPk = peerPk))
    }

    /**
     * Device showing the code. [hello] is the first frame already read from [io]; [code] is null
     * when this device is not offering a code right now.
     */
    suspend fun runResponder(io: FrameIo, hello: ByteArray, code: String?): Result {
        val h = hello.json() ?: return Result.Failed(Failure.MALFORMED)
        if (h.optInt("v") != VERSION || h.optString("t") != "pk-hello") return Result.Failed(Failure.MALFORMED)
        val peerPk = h.optString("pk").unb64()?.takeIf { it.size == 32 } ?: return Result.Failed(Failure.MALFORMED)
        if (code == null || !isCode(code)) {
            io.send(json("pk-none").bytes())
            return Result.Failed(Failure.NO_OFFER)
        }
        val sk = privateKey()
        val pk = publicKey(sk)
        if (!io.send(json("pk-key").put("pk", pk.b64()).bytes())) return Result.Failed(Failure.CLOSED)
        val bits = bitsOf(code)
        for (i in 0 until BITS) {
            val bit = bits[i]
            val theirCommit = io.receive()?.json()?.takeIf { it.optString("t") == "pk-commit" }
                ?.optString("c")?.unb64() ?: return Result.Failed(Failure.MALFORMED)
            val myNonce = nonce()
            if (!io.send(json("pk-commit").put("c", commit(myNonce, pk, peerPk, bit).b64()).bytes())) {
                return Result.Failed(Failure.CLOSED)
            }
            val theirReveal = io.receive()?.json() ?: return Result.Failed(Failure.CLOSED)
            if (theirReveal.optString("t") == "pk-fail") return Result.Failed(Failure.WRONG_CODE)
            val theirNonce = theirReveal.takeIf { it.optString("t") == "pk-reveal" }
                ?.optString("n")?.unb64() ?: return Result.Failed(Failure.MALFORMED)
            // Check the initiator's bit before revealing ours: a wrong guess learns nothing more.
            if (!LinkCrypto.constantTimeEquals(theirCommit, commit(theirNonce, peerPk, pk, bit))) {
                io.send(json("pk-fail").bytes())
                return Result.Failed(Failure.WRONG_CODE)
            }
            if (!io.send(json("pk-reveal").put("n", myNonce.b64()).bytes())) return Result.Failed(Failure.CLOSED)
        }
        return Result.Ok(secret(sk, peerPk, initiatorPk = peerPk, responderPk = pk))
    }

    internal fun bitsOf(code: String): IntArray {
        val value = code.toInt()
        return IntArray(BITS) { (value ushr it) and 1 }
    }

    private fun commit(nonce: ByteArray, senderPk: ByteArray, receiverPk: ByteArray, bit: Int): ByteArray =
        LinkCrypto.hmac(nonce, LABEL.toByteArray(), senderPk, receiverPk, byteArrayOf(bit.toByte()))

    private fun secret(sk: ByteArray, peerPk: ByteArray, initiatorPk: ByteArray, responderPk: ByteArray): ByteArray {
        val shared = ByteArray(32)
        Curve25519.eval(shared, 0, sk, peerPk)
        try {
            return LinkCrypto.hkdf(shared, initiatorPk + responderPk, "$LABEL secret")
        } finally {
            shared.fill(0)
            sk.fill(0)
        }
    }

    private fun privateKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    private fun publicKey(sk: ByteArray): ByteArray = ByteArray(32).also { Curve25519.eval(it, 0, sk, null) }

    private fun nonce(): ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)

    private fun json(type: String) = JSONObject().put("v", VERSION).put("t", type)
    private fun JSONObject.bytes() = toString().toByteArray(Charsets.UTF_8)
    private fun ByteArray.json(): JSONObject? = try { JSONObject(String(this, Charsets.UTF_8)) } catch (_: Exception) { null }
    private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
    private fun String.unb64(): ByteArray? = try { Base64.getDecoder().decode(this) } catch (_: Exception) { null }
}
