package com.bitchat.android.backup

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/** One private conversation as stored in a backup. */
data class BackupConversation(
    val id: String,
    val displayName: String?,
    val messages: List<BitchatMessage>,
    val readMessageIDs: Set<String>
)

/**
 * A message in `chats.json`. For photos, voice notes and files [mediaRef] names the archive entry
 * holding the bytes (`media/<n>.<ext>`); the message content is rewritten to the new local path
 * on import.
 */
data class BackupMessage(val message: BitchatMessage, val mediaRef: String?)

/**
 * JSON encoding of `chats.json`. Unlike the watch mirror encoding ([com.bitchat.android.wearlink.MirrorProtocol])
 * this keeps every field and the message type, because a backup must restore messages exactly.
 */
object BackupChats {
    const val VERSION = 1

    fun encode(conversations: List<Pair<BackupConversation, Map<String, String>>>): ByteArray {
        val array = JSONArray()
        conversations.forEach { (conversation, mediaRefs) ->
            array.put(JSONObject().apply {
                put("id", conversation.id)
                conversation.displayName?.let { put("name", it) }
                put("messages", JSONArray().apply {
                    conversation.messages.forEach { put(encodeMessage(it, mediaRefs[it.id])) }
                })
                put("read", JSONArray(conversation.readMessageIDs.toList()))
            })
        }
        return JSONObject().put("v", VERSION).put("conversations", array).toString().toByteArray(Charsets.UTF_8)
    }

    /** Throws [SkeinFormatException] on anything malformed. */
    fun decode(bytes: ByteArray): List<Pair<BackupConversation, Map<String, String>>> = try {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        if (json.optInt("v", -1) != VERSION) throw SkeinFormatException("Unsupported chats version")
        val array = json.getJSONArray("conversations")
        (0 until array.length()).map { i ->
            val c = array.getJSONObject(i)
            val id = c.getString("id").trim()
            if (id.isEmpty() || id.length > 256) throw SkeinFormatException("Bad conversation id")
            val refs = HashMap<String, String>()
            val messagesJson = c.getJSONArray("messages")
            val messages = (0 until messagesJson.length()).map { j ->
                val decoded = decodeMessage(messagesJson.getJSONObject(j))
                decoded.mediaRef?.let { refs[decoded.message.id] = it }
                decoded.message
            }
            val readJson = c.optJSONArray("read") ?: JSONArray()
            val read = (0 until readJson.length()).map { readJson.getString(it) }.toSet()
            BackupConversation(id, c.optString("name", "").ifBlank { null }, messages, read) to refs
        }
    } catch (e: SkeinFormatException) {
        throw e
    } catch (e: Exception) {
        throw SkeinFormatException("Chats in the backup are damaged", e)
    }

    fun encodeMessage(m: BitchatMessage, mediaRef: String?): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("s", m.sender)
        put("c", m.content)
        put("ty", m.type.name)
        put("t", m.timestamp.time)
        if (m.isRelay) put("relay", true)
        m.originalSender?.let { put("os", it) }
        put("p", m.isPrivate)
        m.recipientNickname?.let { put("rn", it) }
        m.senderPeerID?.let { put("sp", it) }
        m.mentions?.let { put("men", JSONArray(it)) }
        m.channel?.let { put("ch", it) }
        m.powDifficulty?.let { put("pow", it) }
        m.senderNostrPubkey?.let { put("nk", it) }
        mediaRef?.let { put("media", it) }
        when (val st = m.deliveryStatus) {
            null -> Unit
            is DeliveryStatus.Sending -> put("st", "sending")
            is DeliveryStatus.Sent -> put("st", "sent")
            is DeliveryStatus.Delivered -> { put("st", "delivered"); put("sb", st.to); put("sa", st.at.time) }
            is DeliveryStatus.Read -> { put("st", "read"); put("sb", st.by); put("sa", st.at.time) }
            is DeliveryStatus.Failed -> { put("st", "failed"); put("sr", st.reason) }
            is DeliveryStatus.PartiallyDelivered -> {
                put("st", "partial"); put("pr", st.reached); put("pt", st.total)
            }
        }
    }

    fun decodeMessage(o: JSONObject): BackupMessage {
        val status: DeliveryStatus? = when (o.optString("st", "")) {
            "sending" -> DeliveryStatus.Sending
            "sent" -> DeliveryStatus.Sent
            "delivered" -> DeliveryStatus.Delivered(o.optString("sb", ""), Date(o.optLong("sa", 0)))
            "read" -> DeliveryStatus.Read(o.optString("sb", ""), Date(o.optLong("sa", 0)))
            "failed" -> DeliveryStatus.Failed(o.optString("sr", ""))
            "partial" -> DeliveryStatus.PartiallyDelivered(o.optInt("pr", 0), o.optInt("pt", 0))
            else -> null
        }
        val type = BitchatMessageType.entries.firstOrNull { it.name == o.optString("ty") }
            ?: BitchatMessageType.Message
        val id = o.getString("id")
        if (id.isBlank() || id.length > 128) throw SkeinFormatException("Bad message id")
        val mentions = o.optJSONArray("men")?.let { a -> (0 until a.length()).map { a.getString(it) } }
        val message = BitchatMessage(
            id = id,
            sender = o.getString("s"),
            content = o.getString("c"),
            type = type,
            timestamp = Date(o.getLong("t")),
            isRelay = o.optBoolean("relay", false),
            originalSender = o.optString("os", "").ifEmpty { null },
            isPrivate = o.optBoolean("p", true),
            recipientNickname = o.optString("rn", "").ifEmpty { null },
            senderPeerID = o.optString("sp", "").ifEmpty { null },
            mentions = mentions,
            channel = o.optString("ch", "").ifEmpty { null },
            deliveryStatus = status,
            powDifficulty = if (o.has("pow")) o.getInt("pow") else null,
            senderNostrPubkey = o.optString("nk", "").ifEmpty { null }
        )
        val ref = o.optString("media", "").ifEmpty { null }
        if (ref != null && !SkeinArchive.isValidMediaEntry(ref)) throw SkeinFormatException("Bad media reference")
        return BackupMessage(message, ref)
    }
}
