package com.bitchat.android.linked

import android.net.Uri
import java.util.Base64

/**
 * QR code payload for linking a new device.
 *
 * Carries only the existing device's random install ID, a one-time 256-bit pairing secret, and
 * an optional local IP/port hint. Never carries identity keys.
 */
object LinkQr {
    private const val SCHEME = "skein-link"
    private const val HOST = "v1"

    data class Payload(
        val deviceId: String,
        val secret: ByteArray,
        val host: String?,
        val port: Int?
    ) {
        override fun equals(other: Any?) = other is Payload &&
            other.deviceId == deviceId &&
            other.host == host &&
            other.port == port &&
            other.secret.contentEquals(secret)

        override fun hashCode() = deviceId.hashCode()
    }

    fun encode(deviceId: String, secret: ByteArray, host: String?, port: Int?): String {
        val secretB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendQueryParameter("d", deviceId)
            .appendQueryParameter("s", secretB64)
            .apply {
                if (!host.isNullOrBlank()) appendQueryParameter("h", host)
                if (port != null && port in 1..65535) appendQueryParameter("p", port.toString())
            }
            .build()
            .toString()
    }

    fun parse(raw: String): Payload? = try {
        val uri = Uri.parse(raw.trim())
        if (uri.scheme != SCHEME || uri.authority != HOST) return null
        val deviceId = uri.getQueryParameter("d") ?: return null
        if (!LinkHandshake.isDeviceId(deviceId)) return null
        val secretStr = uri.getQueryParameter("s") ?: return null
        val secret = Base64.getUrlDecoder().decode(secretStr)
        if (secret.size != LinkCrypto.KEY_BYTES) return null
        val host = uri.getQueryParameter("h")?.takeIf { it.isNotBlank() }
        val port = uri.getQueryParameter("p")?.toIntOrNull()?.takeIf { it in 1..65535 }
        Payload(deviceId, secret, host, port)
    } catch (_: Exception) {
        null
    }
}
