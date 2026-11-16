package com.bitchat.desktop.link

import android.content.Context
import com.bitchat.android.backup.BackupContent
import com.bitchat.android.backup.BackupConversation
import com.bitchat.android.backup.BackupImporter
import com.bitchat.android.backup.BackupTarget
import com.bitchat.android.backup.ImportMode
import com.bitchat.android.backup.ImportResult
import com.bitchat.android.backup.SkeinArchive
import com.bitchat.android.backup.SkeinCrypto
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.wearlink.IdentityBundle
import java.io.File
import java.io.IOException

/**
 * Exports and imports password-protected `.skein` chat/identity archives on desktop using the
 * shared [SkeinArchive], [SkeinCrypto], and [BackupImporter], so archives move freely between
 * desktop and phone.
 */
object DesktopBackupService {

    /** What an export holds. An identity export lets whoever has the file and passphrase be you. */
    enum class Scope { CHATS_ONLY, CHATS_AND_IDENTITY }

    /** Throws [IllegalArgumentException] for a passphrase the phone would also refuse. */
    fun requireValidPassphrase(passphrase: String) {
        require(passphrase.length >= SkeinCrypto.MIN_PASSPHRASE_LENGTH) {
            "Passphrase must be at least ${SkeinCrypto.MIN_PASSPHRASE_LENGTH} characters"
        }
    }

    /** Private conversations as AppStateStore holds them (the phone's backups cover the same). */
    fun collectConversations(): List<BackupConversation> {
        val read = AppStateStore.readPrivateMessageIDs.value
        return AppStateStore.privateMessages.value
            .filterValues { it.isNotEmpty() }
            .map { (id, messages) ->
                BackupConversation(
                    id = id,
                    displayName = null,
                    messages = messages.sortedBy { it.timestamp.time },
                    readMessageIDs = messages.map { it.id }.filterTo(HashSet()) { it in read },
                )
            }
    }

    /** Only files inside the app's own storage are ever read for a backup. */
    fun mediaFileFor(context: Context, message: BitchatMessage): File? {
        val file = try { File(message.content).canonicalFile } catch (_: IOException) { return null }
        if (!file.isAbsolute) return null
        val roots = listOf(context.filesDir, context.cacheDir).map { it.canonicalFile }
        return file.takeIf { f -> f.isFile && roots.any { f.path.startsWith(it.path + File.separator) } }
    }

    /** Exports the current chats (and, for [Scope.CHATS_AND_IDENTITY], the identity) to [outFile]. */
    fun exportCurrent(context: Context, nickname: String, outFile: File, passphrase: String, scope: Scope) {
        val identity = if (scope == Scope.CHATS_AND_IDENTITY) {
            IdentityBundle.read(context, nickname)?.let(IdentityBundle::encode)
                ?: throw IOException("This computer has no identity yet")
        } else {
            null
        }
        exportArchive(outFile, passphrase, collectConversations(), identity) { mediaFileFor(context, it) }
    }

    fun exportArchive(
        outFile: File,
        passphrase: String,
        conversations: List<BackupConversation>,
        identityBundle: ByteArray? = null,
        mediaResolver: (BitchatMessage) -> File? = { null },
    ) {
        requireValidPassphrase(passphrase)
        val content = BackupContent(
            kind = SkeinArchive.Kind.EXPORT,
            conversations = conversations,
            identity = identityBundle,
            mediaFile = mediaResolver,
        )
        // Write beside the target and move into place, so a failed export never leaves a
        // truncated archive under the chosen name.
        val tmp = File(outFile.absoluteFile.parentFile, ".${outFile.name}.partial")
        try {
            tmp.outputStream().use { fileOut ->
                SkeinCrypto.encrypt(fileOut, SkeinCrypto.KeySource.Passphrase(passphrase.toCharArray())).use { encOut ->
                    SkeinArchive.write(encOut, content)
                }
            }
            if (outFile.exists() && !outFile.delete()) throw IOException("Cannot replace the existing file")
            if (!tmp.renameTo(outFile)) throw IOException("Cannot write the backup file")
        } finally {
            tmp.delete()
        }
    }

    /**
     * Reads and authenticates [archiveFile], then applies it. A wrong passphrase or damaged file
     * throws [com.bitchat.android.backup.SkeinFormatException] before anything is changed.
     */
    suspend fun importArchive(
        archiveFile: File,
        passphrase: String,
        mode: ImportMode,
        target: BackupTarget,
        stagingDir: File,
    ): ImportResult {
        val imported = archiveFile.inputStream().use { inp ->
            SkeinArchive.read(
                inp,
                SkeinCrypto.KeySource.Passphrase(passphrase.toCharArray()),
                stagingDir,
                totalBytes = archiveFile.length(),
            )
        }
        return BackupImporter.apply(imported, mode, target, stagingDir)
    }

    /** Import target backed by AppStateStore and the app-private media folders. */
    class AppStateTarget(
        private val context: Context,
        private val onIdentityReplaced: (IdentityBundle.Contents) -> Unit = {},
    ) : BackupTarget {
        override suspend fun existingMessageIDs(): Set<String> =
            AppStateStore.privateMessages.value.values.flatten().mapTo(HashSet()) { it.id } +
                AppStateStore.publicMessages.value.map { it.id }

        override suspend fun eraseChats(): Boolean {
            AppStateStore.clearAll()
            listOf("images", "files", DesktopLinkedAppBridge.MEDIA_DIR).forEach {
                File(context.cacheDir, it).deleteRecursively()
            }
            return true
        }

        override suspend fun insert(conversation: BackupConversation, message: BitchatMessage, isRead: Boolean): Boolean {
            val added = AppStateStore.addPrivateMessage(conversation.id, message, forceRead = isRead)
            if (isRead) AppStateStore.markPrivateMessageRead(message.id)
            return added
        }

        override suspend fun replaceIdentity(identity: IdentityBundle.Contents): Boolean {
            val ok = IdentityBundle.replace(context, identity)
            if (ok) onIdentityReplaced(identity)
            return ok
        }

        override fun mediaDirectory(type: BitchatMessageType): File =
            File(context.cacheDir, if (type == BitchatMessageType.Image) "images/incoming" else "files/incoming")

        override suspend fun reloadChats() = Unit
    }
}
