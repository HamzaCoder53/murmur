package com.bitchat.android.backup

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What goes into an archive. [mediaFile] returns the local file of a photo/voice/file message. */
class BackupContent(
    val conversations: List<BackupConversation>,
    val identity: ByteArray?,
    val kind: SkeinArchive.Kind,
    val mediaFile: (BitchatMessage) -> File?
)

data class BackupManifest(
    val formatVersion: Int,
    val createdAt: Long,
    val kind: SkeinArchive.Kind,
    val includesIdentity: Boolean,
    val conversationCount: Int,
    val messageCount: Int,
    val mediaCount: Int
)

/** A fully read and authenticated archive. Media sit in the staging directory until applied. */
class ImportedArchive(
    val manifest: BackupManifest,
    val conversations: List<BackupConversation>,
    /** Staged media file by message ID; the message content still holds the old path. */
    val stagedMedia: Map<String, File>,
    val identity: ByteArray?
)

fun interface ProgressListener {
    fun onProgress(done: Long, total: Long)
}

/**
 * Inner layer of `.skein`: a ZIP stream inside [SkeinCrypto]'s authenticated encryption.
 *
 * Entries, in order: `manifest.json`, `chats.json`, optional `identity.json` (IdentityBundle
 * bytes), then `media/<n>.<ext>`. Any other entry name is rejected, so archive paths never reach
 * the file system: staged media are named from the validated reference only.
 */
object SkeinArchive {
    enum class Kind(val code: String) { EXPORT("export"), TRANSFER("transfer") }

    const val FORMAT = "skein-backup"
    const val FORMAT_VERSION = 1
    const val MANIFEST = "manifest.json"
    const val CHATS = "chats.json"
    const val IDENTITY = "identity.json"

    /** Hard caps on import (and export) to bound storage use and zip bombs. */
    const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 64L * 1024
    const val MAX_CHATS_BYTES = 128L * 1024 * 1024
    const val MAX_IDENTITY_BYTES = 1L * 1024 * 1024
    const val MAX_MEDIA_FILE_BYTES = 512L * 1024 * 1024

    private val MEDIA_ENTRY = Regex("^media/[0-9]{1,9}(\\.[a-z0-9]{1,8})?$")

    fun isValidMediaEntry(name: String): Boolean = MEDIA_ENTRY.matches(name)

    fun isMedia(message: BitchatMessage): Boolean = message.type != BitchatMessageType.Message

    // --- Writing ---

    /** Writes the archive as plaintext ZIP to [out] (the caller wraps it in encryption). */
    fun write(out: OutputStream, content: BackupContent, progress: ProgressListener? = null, now: Long = System.currentTimeMillis()) {
        val mediaRefs = HashMap<String, String>()
        val mediaFiles = LinkedHashMap<String, File>()
        val conversations = content.conversations.map { conversation ->
            val messages = conversation.messages.map { message ->
                if (!isMedia(message)) return@map message
                val file = content.mediaFile(message)?.takeIf { it.isFile && it.canRead() }
                if (file == null) {
                    // The file is gone on this device; keep the message as text so history survives.
                    message.copy(
                        type = BitchatMessageType.Message,
                        content = "[" + message.content.substringAfterLast('/').take(80) + "]"
                    )
                } else {
                    val ext = file.extension.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(8)
                    val ref = "media/${mediaFiles.size}" + if (ext.isEmpty()) "" else ".$ext"
                    mediaRefs[message.id] = ref
                    mediaFiles[ref] = file
                    message
                }
            }
            conversation.copy(messages = messages)
        }
        val chats = BackupChats.encode(conversations.map { c -> c to c.messages.mapNotNull { m -> mediaRefs[m.id]?.let { m.id to it } }.toMap() })
        val totalMedia = mediaFiles.values.sumOf { it.length() }
        if (totalMedia + chats.size > MAX_TOTAL_UNCOMPRESSED_BYTES) throw IOException("Backup would be larger than 2 GB")
        val manifest = BackupManifest(
            formatVersion = FORMAT_VERSION,
            createdAt = now,
            kind = content.kind,
            includesIdentity = content.identity != null,
            conversationCount = conversations.size,
            messageCount = conversations.sumOf { it.messages.size },
            mediaCount = mediaFiles.size
        )
        val total = totalMedia + chats.size
        var done = 0L
        ZipOutputStream(out).use { zip ->
            zip.putBytes(MANIFEST, encodeManifest(manifest))
            zip.putBytes(CHATS, chats)
            done += chats.size
            progress?.onProgress(done, total)
            content.identity?.let { zip.putBytes(IDENTITY, it) }
            val buffer = ByteArray(64 * 1024)
            mediaFiles.forEach { (ref, file) ->
                zip.putNextEntry(ZipEntry(ref))
                file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                        done += n
                        progress?.onProgress(done, total)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun ZipOutputStream.putBytes(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    fun encodeManifest(m: BackupManifest): ByteArray = JSONObject().apply {
        put("format", FORMAT)
        put("v", m.formatVersion)
        put("createdAt", m.createdAt)
        put("kind", m.kind.code)
        put("includes", JSONObject().put("chats", true).put("media", true).put("identity", m.includesIdentity))
        put("counts", JSONObject()
            .put("conversations", m.conversationCount)
            .put("messages", m.messageCount)
            .put("media", m.mediaCount))
    }.toString().toByteArray(Charsets.UTF_8)

    fun decodeManifest(bytes: ByteArray): BackupManifest = try {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        if (json.getString("format") != FORMAT) throw SkeinFormatException("Not a Skein backup")
        val version = json.getInt("v")
        if (version != FORMAT_VERSION) throw SkeinFormatException("Backup is from a newer version of the app")
        val counts = json.optJSONObject("counts") ?: JSONObject()
        BackupManifest(
            formatVersion = version,
            createdAt = json.optLong("createdAt", 0),
            kind = Kind.entries.firstOrNull { it.code == json.optString("kind") } ?: Kind.EXPORT,
            includesIdentity = json.getJSONObject("includes").optBoolean("identity", false),
            conversationCount = counts.optInt("conversations", 0),
            messageCount = counts.optInt("messages", 0),
            mediaCount = counts.optInt("media", 0)
        )
    } catch (e: SkeinFormatException) {
        throw e
    } catch (e: Exception) {
        throw SkeinFormatException("Backup manifest is damaged", e)
    }

    // --- Reading ---

    /**
     * Decrypts and reads a whole archive, staging media in [stagingDir] (created and emptied
     * first). Returns only after the encrypted stream has been authenticated to its final chunk.
     * On any failure the staging directory is deleted and [SkeinFormatException] (or an
     * [IOException]) is thrown.
     */
    fun read(
        encrypted: InputStream,
        keySource: SkeinCrypto.KeySource,
        stagingDir: File,
        totalBytes: Long = -1,
        progress: ProgressListener? = null
    ): ImportedArchive {
        if (totalBytes > MAX_ARCHIVE_BYTES) throw SkeinFormatException("Backup is larger than 2 GB")
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) throw IOException("Cannot prepare import")
        try {
            val counted = CappedInputStream(encrypted, MAX_ARCHIVE_BYTES) { read ->
                if (totalBytes > 0) progress?.onProgress(read, totalBytes)
            }
            val plain = SkeinCrypto.decrypt(counted, keySource)
            val result = readZip(plain, stagingDir)
            // ZipInputStream may stop before the central directory: drain to authenticate the end.
            val sink = ByteArray(8192)
            while (plain.read(sink) >= 0) {
                // drain to authenticate trailing stream bytes
            }
            return result
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            throw when (e) {
                is IOException -> e
                else -> SkeinFormatException("Backup is damaged", e)
            }
        }
    }

    private fun readZip(plain: InputStream, stagingDir: File): ImportedArchive {
        val zip = ZipInputStream(plain)
        var manifest: BackupManifest? = null
        var chats: List<Pair<BackupConversation, Map<String, String>>>? = null
        var identity: ByteArray? = null
        val staged = HashMap<String, File>()
        val seen = HashSet<String>()
        var uncompressed = 0L
        val canonicalStaging = stagingDir.canonicalFile
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            if (!seen.add(name)) throw SkeinFormatException("Duplicate entry in backup")
            if (entry.isDirectory) throw SkeinFormatException("Unexpected entry in backup")
            when {
                name == MANIFEST -> {
                    if (seen.size != 1) throw SkeinFormatException("Backup manifest is misplaced")
                    manifest = decodeManifest(zip.readLimited(MAX_MANIFEST_BYTES))
                }
                manifest == null -> throw SkeinFormatException("Backup manifest is missing")
                name == CHATS -> {
                    val bytes = zip.readLimited(MAX_CHATS_BYTES)
                    uncompressed += bytes.size
                    chats = BackupChats.decode(bytes)
                }
                name == IDENTITY -> {
                    if (!manifest.includesIdentity) throw SkeinFormatException("Unexpected identity in backup")
                    identity = zip.readLimited(MAX_IDENTITY_BYTES)
                }
                isValidMediaEntry(name) -> {
                    val target = File(canonicalStaging, name.removePrefix("media/")).canonicalFile
                    if (target.parentFile != canonicalStaging) throw SkeinFormatException("Unsafe entry in backup")
                    var written = 0L
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            written += n
                            uncompressed += n
                            if (written > MAX_MEDIA_FILE_BYTES || uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                throw SkeinFormatException("Backup content is too large")
                            }
                            out.write(buffer, 0, n)
                        }
                    }
                    staged[name] = target
                }
                else -> throw SkeinFormatException("Unsafe entry in backup")
            }
            if (uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) throw SkeinFormatException("Backup content is too large")
        }
        val m = manifest ?: throw SkeinFormatException("Backup manifest is missing")
        val c = chats ?: throw SkeinFormatException("Backup has no chats")
        if (m.includesIdentity && identity == null) throw SkeinFormatException("Backup identity is missing")
        val mediaByMessage = HashMap<String, File>()
        c.forEach { (_, refs) ->
            refs.forEach { (messageID, ref) ->
                mediaByMessage[messageID] = staged[ref] ?: throw SkeinFormatException("Backup media is missing")
            }
        }
        return ImportedArchive(m, c.map { it.first }, mediaByMessage, identity)
    }

    private fun InputStream.readLimited(limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw SkeinFormatException("Backup entry is too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** Fails once more than [limit] bytes have been read. */
    private class CappedInputStream(
        input: InputStream,
        private val limit: Long,
        private val onRead: (Long) -> Unit
    ) : FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) advance(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) advance(n.toLong())
            return n
        }

        private fun advance(n: Long) {
            count += n
            if (count > limit) throw SkeinFormatException("Backup is larger than 2 GB")
            if (count - lastReport >= 256 * 1024) {
                lastReport = count
                onRead(count)
            }
        }
    }
}
