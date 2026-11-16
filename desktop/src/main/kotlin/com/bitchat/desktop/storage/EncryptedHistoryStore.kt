package com.bitchat.desktop.storage

import com.bitchat.desktop.ui.ChatMessage
import com.bitchat.desktop.ui.ConversationId
import com.bitchat.desktop.ui.DeliveryState
import com.bitchat.desktop.ui.HistoryPort
import java.io.File
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent [HistoryPort]: conversation history in one [SealedFile] (AES-256-GCM, key from the
 * OS key store). Messages are kept in memory and written in the background, coalescing bursts
 * into one write. [append] is an upsert by message ID, so a later delivery/read status replaces
 * the saved copy. [clear] deletes the file synchronously and drops any pending write, which is
 * what the "Save message history" switch relies on.
 */
class EncryptedHistoryStore(
    private val sealed: SealedFile,
    private val maxMessages: Int = MAX_MESSAGES,
) : HistoryPort, AutoCloseable {

    private val lock = Any()
    private val messages = LinkedHashMap<String, ChatMessage>()
    private var generation = 0L
    private var writeScheduled = false
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "skein-history-writer").apply { isDaemon = true }
    }

    init {
        sealed.read()?.let { bytes ->
            decode(bytes).sortedBy { it.timestamp.time }.forEach { messages[it.id] = it }
        }
    }

    override fun load(): List<ChatMessage> = synchronized(lock) { messages.values.toList() }

    override fun append(message: ChatMessage) {
        synchronized(lock) {
            if (messages[message.id] == message) return
            messages[message.id] = message
            while (messages.size > maxMessages) {
                val oldest = messages.values.minByOrNull { it.timestamp.time } ?: break
                messages.remove(oldest.id)
            }
            scheduleWrite()
        }
    }

    override fun clear() {
        synchronized(lock) {
            messages.clear()
            generation++
            writeScheduled = false
            sealed.delete()
        }
    }

    /** Blocks until pending writes are on disk. */
    fun flush() {
        writer.submit { }.get(10, TimeUnit.SECONDS)
    }

    override fun close() {
        flush()
        writer.shutdown()
    }

    private fun scheduleWrite() {
        if (writeScheduled) return
        writeScheduled = true
        val gen = generation
        writer.execute {
            val snapshot = synchronized(lock) {
                if (gen != generation || !writeScheduled) return@execute
                writeScheduled = false
                messages.values.toList()
            }
            val bytes = encode(snapshot)
            synchronized(lock) {
                // A clear() that ran while encoding wins: never resurrect erased history.
                if (gen == generation) sealed.write(bytes)
            }
        }
    }

    companion object {
        const val MAX_MESSAGES = 5_000
        const val FILE_NAME = "history.sealed"
        const val KEY_ALIAS = "skein_history"
        private const val PURPOSE = "skein-history-v1"
        private const val VERSION = 1

        /** The history file under [dataDir], keyed by [keyFor] (normally the OS key store). */
        fun open(dataDir: File, keyFor: (String) -> ByteArray): EncryptedHistoryStore =
            EncryptedHistoryStore(SealedFile(File(dataDir, FILE_NAME), keyFor(KEY_ALIAS), PURPOSE))

        internal fun encode(list: List<ChatMessage>): ByteArray {
            val arr = JSONArray()
            for (m in list) {
                val o = JSONObject()
                o.put("id", m.id)
                when (val c = m.conversation) {
                    ConversationId.Mesh -> o.put("conv", "mesh")
                    is ConversationId.Location -> { o.put("conv", "loc"); o.put("convId", c.geohash) }
                    is ConversationId.Private -> { o.put("conv", "dm"); o.put("convId", c.peerID) }
                }
                m.senderPeerID?.let { o.put("from", it) }
                o.put("nick", m.senderNickname)
                o.put("text", m.text)
                o.put("ts", m.timestamp.time)
                o.put("mine", m.isMine)
                m.delivery?.let { o.put("delivery", it.name) }
                arr.put(o)
            }
            return JSONObject().put("v", VERSION).put("messages", arr).toString().toByteArray(Charsets.UTF_8)
        }

        internal fun decode(bytes: ByteArray): List<ChatMessage> {
            val root = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                return emptyList()
            }
            val arr = root.optJSONArray("messages") ?: return emptyList()
            val out = ArrayList<ChatMessage>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val conversation = when (o.optString("conv")) {
                    "mesh" -> ConversationId.Mesh
                    "loc" -> o.optString("convId").takeIf { it.isNotEmpty() }?.let { ConversationId.Location(it) }
                    "dm" -> o.optString("convId").takeIf { it.isNotEmpty() }?.let { ConversationId.Private(it) }
                    else -> null
                } ?: continue
                val id = o.optString("id").takeIf { it.isNotEmpty() } ?: continue
                out += ChatMessage(
                    id = id,
                    conversation = conversation,
                    senderPeerID = if (o.has("from")) o.optString("from") else null,
                    senderNickname = o.optString("nick"),
                    text = o.optString("text"),
                    timestamp = Date(o.optLong("ts")),
                    isMine = o.optBoolean("mine"),
                    delivery = o.optString("delivery").takeIf { it.isNotEmpty() }
                        ?.let { name -> DeliveryState.entries.firstOrNull { it.name == name } },
                )
            }
            return out
        }
    }
}
