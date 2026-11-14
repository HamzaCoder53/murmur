package com.bitchat.android.wearlink

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * Wire format between a phone and a linked watch (same identity).
 *
 * While they are together only the phone is on the mesh. It sends the watch a [Snapshot] of the
 * chat state whenever it changes; the watch sends back [Command]s ("send this") that the phone
 * carries out as if typed on the phone, so both screens show the same conversation. When the
 * watch rejoins after running alone, its hello carries a backlog of what it sent and received in
 * the meantime.
 *
 * Text only: photos and voice notes appear as a short placeholder on the other device.
 */
object MirrorProtocol {
    private const val VERSION = 1

    const val MAX_NICKNAME = 32

    /** Data Layer messages are limited to about 100 KB; stay well under it. */
    const val MAX_PAYLOAD_BYTES = 80_000

    data class Peer(
        val peerID: String,
        val nickname: String,
        val connected: Boolean,
        val hasSession: Boolean
    )

    data class Conversation(
        /** Conversation key on the phone. */
        val key: String,
        /** A mesh peer ID to address the conversation by on the watch. */
        val peerID: String,
        val messages: List<BitchatMessage>
    )

    data class Snapshot(
        val myPeerID: String,
        val myNickname: String,
        val peers: List<Peer>,
        val publicMessages: List<BitchatMessage>,
        val conversations: List<Conversation>
    )

    sealed interface Command {
        val messageID: String
        val content: String

        data class SendPublic(override val messageID: String, override val content: String) : Command

        data class SendPrivate(
            override val messageID: String,
            override val content: String,
            val peerID: String,
            val recipientNickname: String
        ) : Command

        /** The shared nickname was changed on the watch; [content] is the new name. */
        data class SetNickname(override val content: String) : Command {
            override val messageID: String = ""
        }
    }

    // --- Messages ---

    private const val PLACEHOLDER_PHOTO = "[photo]"
    private const val PLACEHOLDER_VOICE = "[voice note]"
    private const val PLACEHOLDER_FILE = "[file]"

    internal fun encodeMessage(m: BitchatMessage): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("s", m.sender)
        put("c", when (m.type) {
            BitchatMessageType.Image -> PLACEHOLDER_PHOTO
            BitchatMessageType.Audio -> PLACEHOLDER_VOICE
            BitchatMessageType.File -> PLACEHOLDER_FILE
            else -> m.content
        })
        put("t", m.timestamp.time)
        put("p", m.isPrivate)
        m.senderPeerID?.let { put("sp", it) }
        m.recipientNickname?.let { put("rn", it) }
        when (val st = m.deliveryStatus) {
            null -> Unit
            is DeliveryStatus.Sending -> put("st", "sending")
            is DeliveryStatus.Sent -> put("st", "sent")
            is DeliveryStatus.Delivered -> { put("st", "delivered"); put("sb", st.to); put("sa", st.at.time) }
            is DeliveryStatus.Read -> { put("st", "read"); put("sb", st.by); put("sa", st.at.time) }
            is DeliveryStatus.Failed -> { put("st", "failed"); put("sr", st.reason) }
            is DeliveryStatus.PartiallyDelivered -> put("st", "sent")
        }
    }

    internal fun decodeMessage(o: JSONObject): BitchatMessage {
        val status: DeliveryStatus? = when (o.optString("st", "")) {
            "sending" -> DeliveryStatus.Sending
            "sent" -> DeliveryStatus.Sent
            "delivered" -> DeliveryStatus.Delivered(o.optString("sb", ""), Date(o.optLong("sa", 0)))
            "read" -> DeliveryStatus.Read(o.optString("sb", ""), Date(o.optLong("sa", 0)))
            "failed" -> DeliveryStatus.Failed(o.optString("sr", ""))
            else -> null
        }
        return BitchatMessage(
            id = o.getString("id"),
            sender = o.getString("s"),
            content = o.getString("c"),
            timestamp = Date(o.getLong("t")),
            isPrivate = o.optBoolean("p", false),
            senderPeerID = o.optString("sp", "").ifEmpty { null },
            recipientNickname = o.optString("rn", "").ifEmpty { null },
            deliveryStatus = status
        )
    }

    private fun encodeMessages(list: List<BitchatMessage>) =
        JSONArray().apply { list.forEach { put(encodeMessage(it)) } }

    private fun decodeMessages(a: JSONArray?): List<BitchatMessage> =
        if (a == null) emptyList() else (0 until a.length()).map { decodeMessage(a.getJSONObject(it)) }

    // --- Snapshot ---

    /**
     * Encode, dropping the oldest messages until it fits in one Data Layer message. Peers and the
     * newest messages are always kept.
     */
    fun encodeSnapshot(snapshot: Snapshot): ByteArray {
        var publicLimit = snapshot.publicMessages.size
        var perConversationLimit = snapshot.conversations.maxOfOrNull { it.messages.size } ?: 0
        while (true) {
            val bytes = encodeSnapshotOnce(snapshot, publicLimit, perConversationLimit)
            if (bytes.size <= MAX_PAYLOAD_BYTES || (publicLimit <= 1 && perConversationLimit <= 1)) {
                return bytes
            }
            publicLimit = (publicLimit / 2).coerceAtLeast(1)
            perConversationLimit = (perConversationLimit / 2).coerceAtLeast(1)
        }
    }

    private fun encodeSnapshotOnce(s: Snapshot, publicLimit: Int, perConversationLimit: Int): ByteArray {
        val json = JSONObject()
        json.put("v", VERSION)
        json.put("me", s.myPeerID)
        json.put("nick", s.myNickname)
        json.put("peers", JSONArray().apply {
            s.peers.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.peerID); put("n", p.nickname)
                    put("c", p.connected); put("s", p.hasSession)
                })
            }
        })
        json.put("pub", encodeMessages(s.publicMessages.takeLast(publicLimit)))
        json.put("dm", JSONArray().apply {
            s.conversations.forEach { c ->
                put(JSONObject().apply {
                    put("k", c.key); put("p", c.peerID)
                    put("m", encodeMessages(c.messages.takeLast(perConversationLimit)))
                })
            }
        })
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeSnapshot(data: ByteArray): Snapshot? = try {
        val json = JSONObject(String(data, Charsets.UTF_8))
        if (json.optInt("v", -1) != VERSION) null else {
            val peers = json.getJSONArray("peers").let { a ->
                (0 until a.length()).map { i ->
                    val p = a.getJSONObject(i)
                    Peer(p.getString("id"), p.getString("n"), p.optBoolean("c"), p.optBoolean("s"))
                }
            }
            val conversations = json.getJSONArray("dm").let { a ->
                (0 until a.length()).map { i ->
                    val c = a.getJSONObject(i)
                    Conversation(c.getString("k"), c.getString("p"), decodeMessages(c.getJSONArray("m")))
                }
            }
            Snapshot(
                myPeerID = json.getString("me"),
                myNickname = json.optString("nick", ""),
                peers = peers,
                publicMessages = decodeMessages(json.getJSONArray("pub")),
                conversations = conversations
            )
        }
    } catch (_: Exception) {
        null
    }

    // --- Commands ---

    fun encodeCommand(command: Command): ByteArray = JSONObject().apply {
        put("v", VERSION)
        put("id", command.messageID)
        put("c", command.content)
        when (command) {
            is Command.SendPublic -> put("op", "pub")
            is Command.SendPrivate -> {
                put("op", "dm"); put("to", command.peerID); put("n", command.recipientNickname)
            }
            is Command.SetNickname -> put("op", "nick")
        }
    }.toString().toByteArray(Charsets.UTF_8)

    fun decodeCommand(data: ByteArray): Command? = try {
        val json = JSONObject(String(data, Charsets.UTF_8))
        val id = json.getString("id")
        val content = json.getString("c")
        when {
            json.optInt("v", -1) != VERSION || content.isBlank() -> null
            json.optString("op") == "pub" -> Command.SendPublic(id, content)
            json.optString("op") == "dm" -> Command.SendPrivate(id, content, json.getString("to"), json.optString("n", ""))
            json.optString("op") == "nick" -> Command.SetNickname(content.trim().take(MAX_NICKNAME))
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    // --- Backlog: what a linked watch sent and received while away from the phone ---

    fun encodeBacklog(publicMessages: List<BitchatMessage>, conversations: List<Conversation>): ByteArray =
        encodeSnapshot(Snapshot("", "", emptyList(), publicMessages, conversations))

    fun decodeBacklog(data: ByteArray): Snapshot? = decodeSnapshot(data)
}
