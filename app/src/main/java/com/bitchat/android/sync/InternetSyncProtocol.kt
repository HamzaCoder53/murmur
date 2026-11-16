package com.bitchat.android.sync

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * Compact JSON wire format for self-addressed NIP-17 internet sync events.
 *
 * Payloads start with [PREFIX] so official Bitchat clients and the Wear OS client's
 * [com.bitchat.watch.internet.WatchNostrClient] (which only accept "bitchat1:") ignore them.
 * Decoding is fail-closed: any missing, unknown, or malformed required field returns null.
 */
object InternetSyncProtocol {
    const val PREFIX = "skein-sync1:"
    const val VERSION = 1
    const val MAX_OPS_PER_EVENT = 20
    const val MAX_PAYLOAD_BYTES = 32_000

    const val PLACEHOLDER_PHOTO = "[photo]"
    const val PLACEHOLDER_VOICE = "[voice note]"
    const val PLACEHOLDER_FILE = "[file]"

    enum class Direction(val code: String) {
        OUT("out"),
        IN("in");

        companion object {
            fun fromCode(code: String): Direction? = entries.firstOrNull { it.code == code }
        }
    }

    sealed interface SyncOp {
        data class MsgOp(
            val id: String,
            val conv: String,
            val peer: String? = null,
            val noiseKeyHex: String? = null,
            val nostrPubkeyHex: String? = null,
            val sender: String,
            val content: String,
            val timestampMs: Long,
            val direction: Direction,
            val deliveryState: String? = null,
            val statusActor: String? = null,
            val statusAtMs: Long? = null,
            val failureReason: String? = null,
            val isRead: Boolean = false
        ) : SyncOp

        data class ReadOp(
            val conv: String,
            val peer: String? = null,
            val noiseKeyHex: String? = null,
            val nostrPubkeyHex: String? = null,
            val messageIds: List<String> = emptyList(),
            val markAll: Boolean = false,
            val timestampMs: Long
        ) : SyncOp
    }

    data class SyncEnvelope(
        val version: Int = VERSION,
        val deviceId: String,
        val ops: List<SyncOp>
    )

    data class DeliveryFields(
        val state: String?,
        val actor: String? = null,
        val atMs: Long? = null,
        val reason: String? = null
    )

    fun contentForSync(message: BitchatMessage): String = when (message.type) {
        BitchatMessageType.Image -> PLACEHOLDER_PHOTO
        BitchatMessageType.Audio -> PLACEHOLDER_VOICE
        BitchatMessageType.File -> PLACEHOLDER_FILE
        else -> message.content
    }

    fun encodeDeliveryStatus(status: DeliveryStatus?): DeliveryFields = when (status) {
        null -> DeliveryFields(null)
        is DeliveryStatus.Sending -> DeliveryFields("sending")
        is DeliveryStatus.Sent -> DeliveryFields("sent")
        is DeliveryStatus.PartiallyDelivered -> DeliveryFields("sent")
        is DeliveryStatus.Delivered -> DeliveryFields("delivered", actor = status.to, atMs = status.at.time)
        is DeliveryStatus.Read -> DeliveryFields("read", actor = status.by, atMs = status.at.time)
        is DeliveryStatus.Failed -> DeliveryFields("failed", reason = status.reason)
    }

    fun decodeDeliveryStatus(
        state: String?,
        actor: String?,
        atMs: Long?,
        reason: String?
    ): DeliveryStatus? = when (state) {
        null, "" -> null
        "sending" -> DeliveryStatus.Sending
        "sent" -> DeliveryStatus.Sent
        "delivered" -> DeliveryStatus.Delivered(actor.orEmpty(), Date(atMs ?: 0L))
        "read" -> DeliveryStatus.Read(actor.orEmpty(), Date(atMs ?: 0L))
        "failed" -> DeliveryStatus.Failed(reason.orEmpty())
        else -> null
    }

    fun encode(envelope: SyncEnvelope): String {
        val root = JSONObject().apply {
            put("v", envelope.version)
            put("dev", envelope.deviceId)
            put("ops", JSONArray().apply {
                envelope.ops.forEach { put(encodeOp(it)) }
            })
        }
        return PREFIX + root.toString()
    }

    fun batchOps(
        deviceId: String,
        ops: List<SyncOp>,
        maxOps: Int = MAX_OPS_PER_EVENT,
        maxBytes: Int = MAX_PAYLOAD_BYTES
    ): List<String> {
        if (deviceId.isBlank() || ops.isEmpty()) return emptyList()
        val limitOps = maxOps.coerceAtLeast(1)
        val limitBytes = maxBytes.coerceAtLeast(512)
        val batches = mutableListOf<String>()
        val current = mutableListOf<SyncOp>()

        for (rawOp in ops) {
            val op = fitOpWithinBytes(deviceId, rawOp, limitBytes)
            val candidate = current + op
            val encodedCandidate = encode(SyncEnvelope(VERSION, deviceId, candidate))
            val exceedsCount = candidate.size > limitOps
            val exceedsBytes = encodedCandidate.toByteArray(Charsets.UTF_8).size > limitBytes
            if (current.isNotEmpty() && (exceedsCount || exceedsBytes)) {
                batches += encode(SyncEnvelope(VERSION, deviceId, current.toList()))
                current.clear()
            }
            current += op
        }

        if (current.isNotEmpty()) {
            batches += encode(SyncEnvelope(VERSION, deviceId, current.toList()))
        }
        return batches
    }

    private fun fitOpWithinBytes(deviceId: String, op: SyncOp, maxBytes: Int): SyncOp {
        if (op !is SyncOp.MsgOp) return op
        val single = encode(SyncEnvelope(VERSION, deviceId, listOf(op)))
        val size = single.toByteArray(Charsets.UTF_8).size
        if (size <= maxBytes) return op

        val overhead = size - op.content.toByteArray(Charsets.UTF_8).size
        val budget = (maxBytes - overhead - 16).coerceAtLeast(0)
        val truncated = truncateUtf8(op.content, budget)
        return op.copy(content = truncated)
    }

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun encodeOp(op: SyncOp): JSONObject = when (op) {
        is SyncOp.MsgOp -> JSONObject().apply {
            put("op", "msg")
            put("id", op.id)
            put("conv", op.conv)
            op.peer?.takeIf { it.isNotBlank() }?.let { put("peer", it) }
            op.noiseKeyHex?.takeIf { it.isNotBlank() }?.let { put("nk", it) }
            op.nostrPubkeyHex?.takeIf { it.isNotBlank() }?.let { put("np", it) }
            put("s", op.sender)
            put("c", op.content)
            put("t", op.timestampMs)
            put("dir", op.direction.code)
            op.deliveryState?.takeIf { it.isNotBlank() }?.let { put("st", it) }
            op.statusActor?.takeIf { it.isNotBlank() }?.let { put("sb", it) }
            op.statusAtMs?.let { put("sa", it) }
            op.failureReason?.takeIf { it.isNotBlank() }?.let { put("sr", it) }
            if (op.isRead) put("rd", true)
        }
        is SyncOp.ReadOp -> JSONObject().apply {
            put("op", "read")
            put("conv", op.conv)
            op.peer?.takeIf { it.isNotBlank() }?.let { put("peer", it) }
            op.noiseKeyHex?.takeIf { it.isNotBlank() }?.let { put("nk", it) }
            op.nostrPubkeyHex?.takeIf { it.isNotBlank() }?.let { put("np", it) }
            if (op.messageIds.isNotEmpty()) {
                put("ids", JSONArray().apply { op.messageIds.forEach { put(it) } })
            }
            if (op.markAll) put("all", true)
            put("t", op.timestampMs)
        }
    }

    fun decode(payload: String): SyncEnvelope? {
        if (!payload.startsWith(PREFIX)) return null
        val jsonBody = payload.removePrefix(PREFIX)
        if (jsonBody.isBlank()) return null

        return try {
            val root = JSONObject(jsonBody)
            val version = root.optInt("v", -1)
            if (version != VERSION) return null

            val deviceId = root.optString("dev", "")
            if (deviceId.isBlank()) return null

            val opsArray = root.optJSONArray("ops") ?: return null
            if (opsArray.length() == 0) return null

            val ops = ArrayList<SyncOp>(opsArray.length())
            for (i in 0 until opsArray.length()) {
                val obj = opsArray.optJSONObject(i) ?: return null
                val op = decodeOp(obj) ?: return null
                ops.add(op)
            }
            SyncEnvelope(version = version, deviceId = deviceId, ops = ops)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeOp(obj: JSONObject): SyncOp? {
        return when (obj.optString("op", "")) {
            "msg" -> {
                val id = obj.optString("id", "").takeIf { it.isNotBlank() } ?: return null
                val conv = obj.optString("conv", "").takeIf { it.isNotBlank() } ?: return null
                if (!obj.has("s") || obj.isNull("s")) return null
                if (!obj.has("c") || obj.isNull("c")) return null
                val sender = obj.optString("s", "")
                val content = obj.optString("c", "")
                val timestampMs = obj.optLong("t", -1L)
                if (timestampMs <= 0L) return null
                val dir = Direction.fromCode(obj.optString("dir", "")) ?: return null

                val st = obj.optString("st", "").takeIf { it.isNotBlank() }
                if (st != null && st !in VALID_STATES) return null

                SyncOp.MsgOp(
                    id = id,
                    conv = conv,
                    peer = obj.optString("peer", "").takeIf { it.isNotBlank() },
                    noiseKeyHex = obj.optString("nk", "").takeIf { it.isNotBlank() },
                    nostrPubkeyHex = obj.optString("np", "").takeIf { it.isNotBlank() },
                    sender = sender,
                    content = content,
                    timestampMs = timestampMs,
                    direction = dir,
                    deliveryState = st,
                    statusActor = obj.optString("sb", "").takeIf { it.isNotBlank() },
                    statusAtMs = if (obj.has("sa") && !obj.isNull("sa")) obj.optLong("sa") else null,
                    failureReason = obj.optString("sr", "").takeIf { it.isNotBlank() },
                    isRead = obj.optBoolean("rd", false)
                )
            }
            "read" -> {
                val conv = obj.optString("conv", "").takeIf { it.isNotBlank() } ?: return null
                val markAll = obj.optBoolean("all", false)
                val idsArray = obj.optJSONArray("ids")
                val ids = mutableListOf<String>()
                if (idsArray != null) {
                    for (i in 0 until idsArray.length()) {
                        val id = idsArray.optString(i, "").takeIf { it.isNotBlank() } ?: return null
                        ids.add(id)
                    }
                }
                if (!markAll && ids.isEmpty()) return null
                val timestampMs = obj.optLong("t", -1L)
                if (timestampMs <= 0L) return null

                SyncOp.ReadOp(
                    conv = conv,
                    peer = obj.optString("peer", "").takeIf { it.isNotBlank() },
                    noiseKeyHex = obj.optString("nk", "").takeIf { it.isNotBlank() },
                    nostrPubkeyHex = obj.optString("np", "").takeIf { it.isNotBlank() },
                    messageIds = ids,
                    markAll = markAll,
                    timestampMs = timestampMs
                )
            }
            else -> null
        }
    }

    private val VALID_STATES = setOf("sending", "sent", "delivered", "read", "failed")
}
