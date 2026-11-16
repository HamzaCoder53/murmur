package com.bitchat.android.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.MessageHistory
import com.bitchat.android.ui.DataManager
import com.bitchat.android.wearlink.IdentityBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

/**
 * Glue between the pure backup format and this phone's storage: what to export, where imports
 * land, and how the app restarts after an identity was replaced.
 */
object DeviceBackup {
    private const val TAG = "DeviceBackup"

    /** Import staging lives in filesDir: Replace clears cacheDir while staged files are still needed. */
    fun stagingDir(context: Context) = File(context.filesDir, "backup-staging")

    /** Scratch space for an encrypted transfer archive (old phone builds it, new phone receives it). */
    fun transferFile(context: Context, name: String) =
        File(context.filesDir, "backup-transfer").apply { mkdirs() }.resolve(name)

    // --- Export ---

    /**
     * All private conversations: the full saved history (when "Save message history" is on) plus
     * whatever is only in memory. In-memory chats are compacted to their latest message once
     * closed, so the database is the authoritative source when it exists.
     */
    suspend fun collectConversations(context: Context): List<BackupConversation> = withContext(Dispatchers.IO) {
        val chats = LinkedHashMap<String, LinkedHashMap<String, BitchatMessage>>()
        val read = HashSet<String>()
        val names = HashMap<String, String>()
        if (MessageHistory.isEnabled(context)) {
            val repository = ConversationRepository.getInstance(context.applicationContext)
            repository.awaitPendingWrites()
            val snapshot = repository.loadAllAndWait()
                ?: throw IOException("Saved chats could not be read")
            snapshot.chats.forEach { (id, messages) ->
                val bucket = chats.getOrPut(ContactDirectory.canonicalConversationId(id)) { LinkedHashMap() }
                messages.forEach { bucket[it.id] = it }
            }
            read += snapshot.readMessageIDs
            snapshot.displayNames.forEach { (id, name) -> names[ContactDirectory.canonicalConversationId(id)] = name }
        }
        AppStateStore.privateMessages.value.forEach { (id, messages) ->
            val bucket = chats.getOrPut(ContactDirectory.canonicalConversationId(id)) { LinkedHashMap() }
            messages.forEach { bucket.putIfAbsent(it.id, it) }
        }
        read += AppStateStore.readPrivateMessageIDs.value
        AppStateStore.privateConversationDisplayNames.value.forEach { (id, name) ->
            names.putIfAbsent(ContactDirectory.canonicalConversationId(id), name)
        }
        chats.filterValues { it.isNotEmpty() }.map { (id, messages) ->
            BackupConversation(
                id = id,
                displayName = names[id],
                messages = messages.values.sortedBy { it.timestamp.time },
                readMessageIDs = messages.keys.filterTo(HashSet()) { it in read }
            )
        }
    }

    /** This phone's identity bundle, or null if it has none yet. */
    fun readIdentity(context: Context): ByteArray? {
        val nickname = DataManager(context.applicationContext).loadNickname()
        return IdentityBundle.read(context, nickname)?.let(IdentityBundle::encode)
    }

    /** Only files inside the app's own storage are ever read for a backup. */
    fun mediaFileFor(context: Context, message: BitchatMessage): File? {
        val path = message.content.takeIf { it.startsWith("/") } ?: return null
        val file = try { File(path).canonicalFile } catch (_: IOException) { return null }
        val roots = listOf(context.filesDir, context.cacheDir).map { it.canonicalFile }
        return file.takeIf { f -> f.isFile && roots.any { f.path.startsWith(it.path + File.separator) } }
    }

    suspend fun buildContent(context: Context, includeIdentity: Boolean, kind: SkeinArchive.Kind): BackupContent {
        val conversations = collectConversations(context)
        val identity = if (includeIdentity) {
            readIdentity(context) ?: throw IOException("This phone has no identity yet")
        } else null
        val app = context.applicationContext
        return BackupContent(conversations, identity, kind) { mediaFileFor(app, it) }
    }

    /** Writes an encrypted archive of [content] to [uri] (Storage Access Framework). */
    suspend fun exportToUri(
        context: Context,
        uri: Uri,
        content: BackupContent,
        keySource: SkeinCrypto.KeySource,
        progress: ProgressListener
    ) = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open file")
        SkeinCrypto.encrypt(stream, keySource).use { SkeinArchive.write(it, content, progress) }
    }

    suspend fun exportToFile(file: File, content: BackupContent, keySource: SkeinCrypto.KeySource, progress: ProgressListener) =
        withContext(Dispatchers.IO) {
            SkeinCrypto.encrypt(file.outputStream(), keySource).use { SkeinArchive.write(it, content, progress) }
        }

    // --- Import ---

    suspend fun readFromUri(
        context: Context,
        uri: Uri,
        keySource: SkeinCrypto.KeySource,
        progress: ProgressListener
    ): ImportedArchive = withContext(Dispatchers.IO) {
        val size = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) { -1L }
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
        input.use { SkeinArchive.read(it, keySource, stagingDir(context), size, progress) }
    }

    /**
     * Restarts the app so every service loads the restored identity from disk.
     *
     * The running mesh, Noise, Nostr, favorites and watch-link singletons all hold the old keys in
     * memory, so recreating only the mesh (as panic does after generating fresh keys) would leave
     * some of them speaking as the old identity. A clean process start is the one path that
     * re-reads everything; the activity is relaunched first so the user lands back in the app.
     */
    fun restartApp(context: Context) {
        val app = context.applicationContext
        try { com.bitchat.android.wearlink.WearLinkController.stop() } catch (_: Exception) { }
        try { com.bitchat.android.service.MeshForegroundService.stop(app) } catch (_: Exception) { }
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)?.component
        if (launch != null) {
            app.startActivity(Intent.makeRestartActivityTask(launch))
        }
        exitProcess(0)
    }
}

/** Real import target: the encrypted conversation database (or memory while history is off). */
class DeviceBackupTarget(context: Context) : BackupTarget {
    private val app = context.applicationContext
    private val historyOn get() = MessageHistory.isEnabled(app)

    override suspend fun existingMessageIDs(): Set<String> {
        val ids = HashSet<String>()
        if (historyOn) {
            val repository = ConversationRepository.getInstance(app)
            repository.awaitPendingWrites()
            val snapshot = repository.loadAllAndWait() ?: throw IOException("Saved chats could not be read")
            snapshot.chats.values.forEach { list -> list.forEach { ids += it.id } }
            ids += snapshot.deletedMessageIDs
        }
        AppStateStore.privateMessages.value.values.forEach { list -> list.forEach { ids += it.id } }
        return ids
    }

    override suspend fun insert(conversation: BackupConversation, message: BitchatMessage, isRead: Boolean): Boolean {
        val id = ContactDirectory.canonicalConversationId(conversation.id)
        return if (historyOn) {
            ConversationRepository.getInstance(app).upsertMessageAndWait(
                conversationID = id,
                aliases = setOf(conversation.id, id),
                displayName = conversation.displayName,
                message = message,
                isRead = isRead
            )
        } else {
            AppStateStore.addPrivateMessage(id, message, forceRead = isRead)
        }
    }

    override suspend fun eraseChats(): Boolean {
        val cleared = AppStateStore.panicClearPrivateConversations()
        val media = com.bitchat.android.features.file.FileUtils.clearAllMedia(app)
        try { com.bitchat.android.services.SeenMessageStore.getInstance(app).clear() } catch (_: Exception) { }
        try { com.bitchat.android.services.MessageRouter.tryGetInstance()?.clearAll() } catch (_: Exception) { }
        AppStateStore.resumePrivateConversationsAfterPanic()
        if (!media) Log.w("DeviceBackupTarget", "Some media could not be erased")
        return cleared
    }

    override suspend fun replaceIdentity(identity: IdentityBundle.Contents): Boolean = withContext(Dispatchers.IO) {
        if (!IdentityBundle.replace(app, identity)) return@withContext false
        if (identity.nickname.isNotBlank()) {
            app.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).edit()
                .putString("nickname", identity.nickname.take(32)).commit()
        }
        true
    }

    override fun mediaDirectory(type: BitchatMessageType): File = when (type) {
        BitchatMessageType.Image -> File(app.filesDir, "images/incoming")
        BitchatMessageType.Audio -> File(app.filesDir, "voicenotes/incoming")
        else -> File(app.filesDir, "files/incoming")
    }

    override suspend fun reloadChats() {
        if (historyOn) AppStateStore.reloadConversationPersistence(app)
    }
}
