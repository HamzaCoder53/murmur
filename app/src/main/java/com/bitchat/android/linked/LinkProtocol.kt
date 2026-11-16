package com.bitchat.android.linked

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/** One chat message as linked devices exchange it. */
data class LinkEntry(
    val message: BitchatMessage,
    /** Private conversation key on the sending device; null for the public mesh timeline. */
    val conversation: String?,
    /** The user has read it (private messages only; read state only ever moves to true). */
    val read: Boolean = false
)

/** A nearby mesh peer as the leader sees it, for followers to display. */
data class LinkPeer(
    val peerID: String,
    val nickname: String,
    val connected: Boolean,
    val hasSession: Boolean,
    val noiseKeyHex: String? = null,
    val isFavorite: Boolean = false,
    val theyFavoritedUs: Boolean = false
)

/** A favorite set on one device. [updatedAt] decides between devices that disagree. */
data class LinkFavorite(val noiseKeyHex: String, val nickname: String, val isFavorite: Boolean, val updatedAt: Long)

/** Something a follower asks its leader to do on the mesh on its behalf. */
sealed interface LinkCommand {
    data class SendPublic(val messageID: String?, val content: String, val mentions: List<String>, val channel: String?) : LinkCommand
    data class SendPrivate(val messageID: String, val content: String, val peerID: String, val recipientNickname: String) : LinkCommand
    data class ReadReceipt(val messageID: String, val peerID: String) : LinkCommand
    data class SetFavorite(val peerID: String, val isFavorite: Boolean) : LinkCommand
    data class Announce(val nickname: String) : LinkCommand

    /** A file the follower attached; its bytes follow as [LinkFrame.MediaChunk]s with the same ID. */
    data class SendFile(val messageID: String, val peerID: String?, val fileName: String, val mimeType: String, val kind: String) : LinkCommand
}

/** Every frame of an authenticated link session (sent encrypted, see [FrameCipher]). */
sealed interface LinkFrame {
    /** First frame each way: who the device is and the shared nickname as it knows it. */
    data class Hello(
        val deviceName: String,
        val nickname: String,
        val nicknameAt: Long,
        /** True when the sender holds no linked identity any more and only delivers wipes. */
        val wipeOnly: Boolean = false
    ) : LinkFrame

    /** Messages and favorites from the other device (a backlog on reconnect, or a mirror delta). */
    data class Sync(
        val entries: List<LinkEntry>,
        val favorites: List<LinkFavorite> = emptyList(),
        val backlogDone: Boolean = false
    ) : LinkFrame

    /** Leader to follower: who is on the mesh. */
    data class Peers(val peers: List<LinkPeer>) : LinkFrame

    data class Nickname(val nickname: String, val at: Long) : LinkFrame

    data class Command(val command: LinkCommand) : LinkFrame

    data class FetchMedia(val messageID: String) : LinkFrame

    data class MediaChunk(val messageID: String, val fileName: String, val index: Int, val total: Int, val data: ByteArray) : LinkFrame {
        override fun equals(other: Any?) = other is MediaChunk && other.messageID == messageID && other.index == index
        override fun hashCode() = messageID.hashCode() * 31 + index
    }

    /** Erase the shared identity. [panic] also passes the wipe on to the receiver's own links. */
    data class Wipe(val panic: Boolean) : LinkFrame
    object WipeAck : LinkFrame

    /** The sender left the group (erased its copy of the identity); forget it. */
    object Unlinked : LinkFrame

    /** Link the receiver with another device of the group, under a key made for that pair. */
    data class Introduce(val deviceId: String, val name: String, val key: ByteArray) : LinkFrame {
        override fun equals(other: Any?) = other is Introduce && other.deviceId == deviceId
        override fun hashCode() = deviceId.hashCode()
    }

    // --- Pairing (only on a session authenticated with a QR secret) ---

    data class PairRequest(val deviceName: String) : LinkFrame
    object PairDenied : LinkFrame

    /** The identity (an [com.bitchat.android.wearlink.IdentityBundle] encoding) and device name. */
    data class PairIdentity(val identity: ByteArray, val deviceName: String) : LinkFrame {
        override fun equals(other: Any?) = other is PairIdentity && other.deviceName == deviceName
        override fun hashCode() = deviceName.hashCode()
    }

    object PairDone : LinkFrame
    object Ping : LinkFrame
}

/** JSON encoding of [LinkFrame]s. Unknown or malformed input decodes to null and is dropped. */
object LinkProtocol {
    const val VERSION = 1

    /** Raw bytes per media chunk; with base64 and JSON it stays well under the 64 KiB frame limit. */
    const val MEDIA_CHUNK_BYTES = 32 * 1024
    const val MAX_MEDIA_BYTES = 16L * 1024 * 1024
    const val MAX_NICKNAME = 32
    const val MAX_DEVICE_NAME = 40

    /** Entries per [LinkFrame.Sync], so a big history becomes many frames below the frame limit. */
    const val ENTRIES_PER_FRAME = 40

    fun encode(frame: LinkFrame): ByteArray {
        val o = JSONObject().put("v", VERSION)
        when (frame) {
            is LinkFrame.Hello -> o.put("t", "hello").put("dn", frame.deviceName).put("nick", frame.nickname)
                .put("na", frame.nicknameAt).put("wo", frame.wipeOnly)
            is LinkFrame.Sync -> o.put("t", "sync")
                .put("e", JSONArray().apply { frame.entries.forEach { put(encodeEntry(it)) } })
                .put("f", JSONArray().apply { frame.favorites.forEach { put(encodeFavorite(it)) } })
                .put("bd", frame.backlogDone)
            is LinkFrame.Peers -> o.put("t", "peers").put("p", JSONArray().apply {
                frame.peers.forEach { p ->
                    put(JSONObject().put("id", p.peerID).put("n", p.nickname).put("c", p.connected)
                        .put("s", p.hasSession).put("f", p.isFavorite).put("fb", p.theyFavoritedUs)
                        .apply { p.noiseKeyHex?.let { put("k", it) } })
                }
            })
            is LinkFrame.Nickname -> o.put("t", "nick").put("n", frame.nickname).put("a", frame.at)
            is LinkFrame.Command -> o.put("t", "cmd").put("c", encodeCommand(frame.command))
            is LinkFrame.FetchMedia -> o.put("t", "fetch").put("id", frame.messageID)
            is LinkFrame.MediaChunk -> o.put("t", "media").put("id", frame.messageID).put("fn", frame.fileName)
                .put("i", frame.index).put("n", frame.total).put("d", frame.data.b64())
            is LinkFrame.Wipe -> o.put("t", "wipe").put("panic", frame.panic)
            LinkFrame.WipeAck -> o.put("t", "wipe_ack")
            LinkFrame.Unlinked -> o.put("t", "unlinked")
            is LinkFrame.Introduce -> o.put("t", "intro").put("dev", frame.deviceId).put("n", frame.name).put("k", frame.key.b64())
            is LinkFrame.PairRequest -> o.put("t", "pair_req").put("dn", frame.deviceName)
            LinkFrame.PairDenied -> o.put("t", "pair_denied")
            is LinkFrame.PairIdentity -> o.put("t", "pair_id").put("id", frame.identity.b64()).put("dn", frame.deviceName)
            LinkFrame.PairDone -> o.put("t", "pair_done")
            LinkFrame.Ping -> o.put("t", "ping")
        }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): LinkFrame? = try {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        if (o.optInt("v", -1) != VERSION) null else when (o.getString("t")) {
            "hello" -> LinkFrame.Hello(
                o.getString("dn").take(MAX_DEVICE_NAME), o.optString("nick").take(MAX_NICKNAME),
                o.optLong("na"), o.optBoolean("wo")
            )
            "sync" -> LinkFrame.Sync(
                o.getJSONArray("e").objects().map(::decodeEntry),
                o.optJSONArray("f")?.objects()?.mapNotNull(::decodeFavorite).orEmpty(),
                o.optBoolean("bd")
            )
            "peers" -> LinkFrame.Peers(o.getJSONArray("p").objects().map { p ->
                LinkPeer(
                    p.getString("id"), p.optString("n"), p.optBoolean("c"), p.optBoolean("s"),
                    p.optString("k").ifEmpty { null }, p.optBoolean("f"), p.optBoolean("fb")
                )
            })
            "nick" -> LinkFrame.Nickname(o.getString("n").trim().take(MAX_NICKNAME), o.getLong("a"))
            "cmd" -> decodeCommand(o.getJSONObject("c"))?.let { LinkFrame.Command(it) }
            "fetch" -> LinkFrame.FetchMedia(o.getString("id"))
            "media" -> {
                val index = o.getInt("i")
                val total = o.getInt("n")
                val data = o.getString("d").unb64() ?: ByteArray(0)
                if (index !in 0 until total || total.toLong() * MEDIA_CHUNK_BYTES > MAX_MEDIA_BYTES + MEDIA_CHUNK_BYTES ||
                    data.size > MEDIA_CHUNK_BYTES
                ) null else LinkFrame.MediaChunk(o.getString("id"), o.getString("fn"), index, total, data)
            }
            "wipe" -> LinkFrame.Wipe(o.optBoolean("panic"))
            "wipe_ack" -> LinkFrame.WipeAck
            "unlinked" -> LinkFrame.Unlinked
            "intro" -> {
                val dev = o.getString("dev")
                val key = o.getString("k").unb64()
                if (!LinkHandshake.isDeviceId(dev) || key?.size != LinkCrypto.KEY_BYTES) null
                else LinkFrame.Introduce(dev, o.optString("n").take(MAX_DEVICE_NAME), key)
            }
            "pair_req" -> LinkFrame.PairRequest(o.getString("dn").take(MAX_DEVICE_NAME))
            "pair_denied" -> LinkFrame.PairDenied
            "pair_id" -> o.getString("id").unb64()?.let { LinkFrame.PairIdentity(it, o.optString("dn").take(MAX_DEVICE_NAME)) }
            "pair_done" -> LinkFrame.PairDone
            "ping" -> LinkFrame.Ping
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    // --- Entries ---

    internal fun encodeEntry(e: LinkEntry): JSONObject {
        val m = e.message
        return JSONObject().apply {
            put("id", m.id); put("s", m.sender); put("c", m.content); put("t", m.timestamp.time)
            put("p", m.isPrivate); put("ty", m.type.name)
            m.senderPeerID?.let { put("sp", it) }
            m.recipientNickname?.let { put("rn", it) }
            m.channel?.let { put("ch", it) }
            m.mentions?.takeIf { it.isNotEmpty() }?.let { put("mn", JSONArray(it)) }
            e.conversation?.let { put("cv", it) }
            put("r", e.read)
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
    }

    internal fun decodeEntry(o: JSONObject): LinkEntry {
        val status: DeliveryStatus? = when (o.optString("st")) {
            "sending" -> DeliveryStatus.Sending
            "sent" -> DeliveryStatus.Sent
            "delivered" -> DeliveryStatus.Delivered(o.optString("sb"), Date(o.optLong("sa")))
            "read" -> DeliveryStatus.Read(o.optString("sb"), Date(o.optLong("sa")))
            "failed" -> DeliveryStatus.Failed(o.optString("sr"))
            else -> null
        }
        val type = BitchatMessageType.entries.firstOrNull { it.name == o.optString("ty") } ?: BitchatMessageType.Message
        val message = BitchatMessage(
            id = o.getString("id"),
            sender = o.getString("s"),
            content = o.getString("c"),
            type = type,
            timestamp = Date(o.getLong("t")),
            isPrivate = o.optBoolean("p"),
            recipientNickname = o.optString("rn").ifEmpty { null },
            senderPeerID = o.optString("sp").ifEmpty { null },
            mentions = o.optJSONArray("mn")?.let { a -> (0 until a.length()).map { a.getString(it) } },
            channel = o.optString("ch").ifEmpty { null },
            deliveryStatus = status
        )
        return LinkEntry(message, o.optString("cv").ifEmpty { null }, o.optBoolean("r"))
    }

    private fun encodeFavorite(f: LinkFavorite) = JSONObject()
        .put("k", f.noiseKeyHex).put("n", f.nickname).put("f", f.isFavorite).put("a", f.updatedAt)

    private fun decodeFavorite(o: JSONObject): LinkFavorite? {
        val key = o.optString("k")
        if (key.length != 64 || !key.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return LinkFavorite(key, o.optString("n"), o.optBoolean("f"), o.optLong("a"))
    }

    // --- Commands ---

    private fun encodeCommand(c: LinkCommand): JSONObject = JSONObject().apply {
        when (c) {
            is LinkCommand.SendPublic -> {
                put("op", "pub"); c.messageID?.let { put("id", it) }; put("c", c.content)
                put("mn", JSONArray(c.mentions)); c.channel?.let { put("ch", it) }
            }
            is LinkCommand.SendPrivate -> {
                put("op", "dm"); put("id", c.messageID); put("c", c.content); put("to", c.peerID); put("n", c.recipientNickname)
            }
            is LinkCommand.ReadReceipt -> { put("op", "read"); put("id", c.messageID); put("to", c.peerID) }
            is LinkCommand.SetFavorite -> { put("op", "fav"); put("to", c.peerID); put("f", c.isFavorite) }
            is LinkCommand.Announce -> { put("op", "nick"); put("n", c.nickname) }
            is LinkCommand.SendFile -> {
                put("op", "file"); put("id", c.messageID); c.peerID?.let { put("to", it) }
                put("fn", c.fileName); put("mt", c.mimeType); put("k", c.kind)
            }
        }
    }

    private fun decodeCommand(o: JSONObject): LinkCommand? = when (o.optString("op")) {
        "pub" -> o.getString("c").takeIf { it.isNotEmpty() }?.let { content ->
            LinkCommand.SendPublic(
                o.optString("id").ifEmpty { null }, content,
                o.optJSONArray("mn")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                o.optString("ch").ifEmpty { null }
            )
        }
        "dm" -> {
            val id = o.getString("id")
            val content = o.getString("c")
            val to = o.getString("to")
            if (id.isBlank() || content.isEmpty() || to.isBlank()) null
            else LinkCommand.SendPrivate(id, content, to, o.optString("n"))
        }
        "read" -> LinkCommand.ReadReceipt(o.getString("id"), o.getString("to"))
        "fav" -> LinkCommand.SetFavorite(o.getString("to"), o.getBoolean("f"))
        "nick" -> o.getString("n").trim().take(MAX_NICKNAME).takeIf { it.isNotEmpty() }?.let { LinkCommand.Announce(it) }
        "file" -> {
            val id = o.getString("id")
            if (id.isBlank()) null else LinkCommand.SendFile(
                id, o.optString("to").ifEmpty { null }, o.getString("fn"), o.optString("mt"), o.optString("k")
            )
        }
        else -> null
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
