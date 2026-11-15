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
        val hasSession: Boolean,
        /** Noise static key (hex), needed to favorite or verify the peer. */
        val noiseKeyHex: String? = null,
        val isFavorite: Boolean = false,
        val theyFavoritedUs: Boolean = false
    )

    data class Conversation(
        /** Conversation key on the phone. */
        val key: String,
        /** A mesh peer ID to address the conversation by on the watch. */
        val peerID: String,
        val messages: List<BitchatMessage>,
        /** Unread count on the phone; the watch shows the same. */
        val unread: Int = 0
    )

    data class Snapshot(
        val myPeerID: String,
        val myNickname: String,
        val peers: List<Peer>,
        val publicMessages: List<BitchatMessage>,
        val conversations: List<Conversation>,
        /** Photos and voice notes among the messages, by message ID; fetched separately. */
        val media: Map<String, Media> = emptyMap()
    )

    enum class MediaKind(val code: String) { IMAGE("image"), AUDIO("audio"), FILE("file") }

    /** A message whose content is a file on the sending device. [extension] keeps its type. */
    data class Media(val kind: MediaKind, val extension: String)

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

        /** Watch asks for the media file of message [content]. */
        data class FetchMedia(override val content: String) : Command {
            override val messageID: String = content
        }

        /**
         * Watch recorded a voice note (uploaded as a file with the same message ID); the phone sends
         * it to [content] (a peer ID), or publicly when [content] is blank.
         */
        data class SendVoice(
            override val messageID: String,
            override val content: String,
            val recipientNickname: String,
            val extension: String
        ) : Command

        /** The conversation with [content] (a peer ID) was read on the watch. */
        data class MarkRead(override val content: String) : Command {
            override val messageID: String = ""
        }

        /** Favorite or unfavorite a peer; [content] is the peer ID. */
        data class SetFavorite(override val content: String, val isFavorite: Boolean) : Command {
            override val messageID: String = ""
        }

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
        mediaKindOf(m)?.let { kind ->
            put("ty", kind.code)
            put("x", m.content.substringAfterLast('.', "").lowercase().take(8))
        }
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

    private fun mediaKindOf(m: BitchatMessage): MediaKind? = when (m.type) {
        BitchatMessageType.Image -> MediaKind.IMAGE
        BitchatMessageType.Audio -> MediaKind.AUDIO
        BitchatMessageType.File -> MediaKind.FILE
        else -> null
    }

    /** Media references for the messages in [array], by message ID. */
    private fun mediaIn(array: JSONArray?, into: MutableMap<String, Media>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val kind = MediaKind.entries.firstOrNull { it.code == o.optString("ty") } ?: continue
            into[o.getString("id")] = Media(kind, o.optString("x", ""))
        }
    }

    private fun encodeMessages(list: List<BitchatMessage>) =
        JSONArray().apply { list.forEach { put(encodeMessage(it)) } }

    private fun decodeMessages(a: JSONArray?): List<BitchatMessage> =
        if (a == null) emptyList() else (0 until a.length()).map { decodeMessage(a.getJSONObject(it)) }

    // --- Snapshot ---

    /**
     * Encode, dropping the oldest messages until it fits in one Data Layer message. Peers are
     * always kept; in the extreme, messages go entirely rather than the snapshot never sending.
     */
    fun encodeSnapshot(snapshot: Snapshot): ByteArray {
        var publicLimit = snapshot.publicMessages.size
        var perConversationLimit = snapshot.conversations.maxOfOrNull { it.messages.size } ?: 0
        while (true) {
            val bytes = encodeSnapshotOnce(snapshot, publicLimit, perConversationLimit)
            if (bytes.size <= MAX_PAYLOAD_BYTES || (publicLimit == 0 && perConversationLimit == 0)) {
                return bytes
            }
            publicLimit /= 2
            perConversationLimit /= 2
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
                    p.noiseKeyHex?.let { put("k", it) }
                    put("f", p.isFavorite); put("fb", p.theyFavoritedUs)
                })
            }
        })
        json.put("pub", encodeMessages(s.publicMessages.takeLast(publicLimit)))
        json.put("dm", JSONArray().apply {
            s.conversations.forEach { c ->
                put(JSONObject().apply {
                    put("k", c.key); put("p", c.peerID); put("u", c.unread)
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
                    Peer(
                        p.getString("id"), p.getString("n"), p.optBoolean("c"), p.optBoolean("s"),
                        p.optString("k", "").ifEmpty { null }, p.optBoolean("f"), p.optBoolean("fb")
                    )
                }
            }
            val conversations = json.getJSONArray("dm").let { a ->
                (0 until a.length()).map { i ->
                    val c = a.getJSONObject(i)
                    Conversation(c.getString("k"), c.getString("p"), decodeMessages(c.getJSONArray("m")), c.optInt("u", 0))
                }
            }
            val media = HashMap<String, Media>()
            mediaIn(json.getJSONArray("pub"), media)
            json.getJSONArray("dm").let { a ->
                for (i in 0 until a.length()) mediaIn(a.getJSONObject(i).getJSONArray("m"), media)
            }
            Snapshot(
                myPeerID = json.getString("me"),
                myNickname = json.optString("nick", ""),
                peers = peers,
                publicMessages = decodeMessages(json.getJSONArray("pub")),
                conversations = conversations,
                media = media
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
            is Command.SetFavorite -> { put("op", "fav"); put("f", command.isFavorite) }
            is Command.MarkRead -> put("op", "read")
            is Command.FetchMedia -> put("op", "fetch")
            is Command.SendVoice -> { put("op", "voice"); put("n", command.recipientNickname); put("x", command.extension) }
        }
    }.toString().toByteArray(Charsets.UTF_8)

    fun decodeCommand(data: ByteArray): Command? = try {
        val json = JSONObject(String(data, Charsets.UTF_8))
        val id = json.getString("id")
        val content = json.getString("c")
        val op = json.optString("op")
        when {
            json.optInt("v", -1) != VERSION -> null
            // A voice note to the public chat has no recipient, so blank content is allowed.
            op == "voice" -> if (id.isBlank()) null else
                Command.SendVoice(id, content, json.optString("n", ""), json.optString("x", "m4a").take(8))
            content.isBlank() -> null
            op == "fetch" -> Command.FetchMedia(content)
            json.optString("op") == "pub" -> Command.SendPublic(id, content)
            json.optString("op") == "dm" -> Command.SendPrivate(id, content, json.getString("to"), json.optString("n", ""))
            json.optString("op") == "nick" -> Command.SetNickname(content.trim().take(MAX_NICKNAME))
            json.optString("op") == "fav" -> Command.SetFavorite(content, json.optBoolean("f"))
            json.optString("op") == "read" -> Command.MarkRead(content)
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
