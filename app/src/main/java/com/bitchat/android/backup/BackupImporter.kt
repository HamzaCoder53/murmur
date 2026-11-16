package com.bitchat.android.backup

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.wearlink.IdentityBundle
import java.io.File
import java.io.IOException
import java.util.UUID

/** Where an import lands. [DeviceBackupTarget] is the real one; tests use fakes. */
interface BackupTarget {
    /** IDs of every private message already on this device (saved and in memory). */
    suspend fun existingMessageIDs(): Set<String>

    suspend fun insert(conversation: BackupConversation, message: BitchatMessage, isRead: Boolean): Boolean

    /** Erase every private chat and stored media (Replace). False if the wipe did not confirm. */
    suspend fun eraseChats(): Boolean

    /** Store [identity] durably as this device's identity. Takes effect after [restartWithNewIdentity]. */
    suspend fun replaceIdentity(identity: IdentityBundle.Contents): Boolean

    /** App-private directory for imported media of [type]. */
    fun mediaDirectory(type: BitchatMessageType): File

    /** Refresh in-memory chat state after a Merge or a chats-only Replace. */
    suspend fun reloadChats()
}

enum class ImportMode { MERGE, REPLACE }

data class ImportResult(
    val added: Int,
    val skipped: Int,
    val failed: Int,
    /** True if a Replace also restored the identity: the caller must restart the app. */
    val identityRestored: Boolean
)

object BackupImporter {
    /**
     * Applies an authenticated [archive]. Merge adds messages whose IDs are new and never touches
     * the identity. Replace erases chats and media first, restores everything, and stores the
     * archive's identity if it has one (decoded before anything is erased). Staged media are moved
     * into app-private storage; the staging directory is deleted afterwards either way.
     */
    suspend fun apply(archive: ImportedArchive, mode: ImportMode, target: BackupTarget, stagingDir: File): ImportResult {
        try {
            val identity = if (mode == ImportMode.REPLACE && archive.identity != null) {
                IdentityBundle.decode(archive.identity)
                    ?: throw SkeinFormatException("The identity in this backup is unreadable")
            } else {
                null
            }
            val existing = if (mode == ImportMode.MERGE) target.existingMessageIDs() else emptySet()
            if (mode == ImportMode.REPLACE && !target.eraseChats()) {
                throw IOException("Could not erase the current chats")
            }
            val seen = HashSet(existing)
            var added = 0
            var skipped = 0
            var failed = 0
            archive.conversations.forEach { conversation ->
                conversation.messages.forEach { original ->
                    if (!seen.add(original.id)) {
                        skipped++
                        return@forEach
                    }
                    val message = archive.stagedMedia[original.id]?.let { staged ->
                        val local = moveMedia(staged, target.mediaDirectory(original.type))
                        original.copy(content = local.absolutePath)
                    } ?: original.takeUnless { SkeinArchive.isMedia(it) }
                    ?: original.copy(type = BitchatMessageType.Message, content = "[" + original.content.substringAfterLast('/').take(80) + "]")
                    val isRead = original.id in conversation.readMessageIDs
                    if (target.insert(conversation, message, isRead)) added++ else failed++
                }
            }
            val identityRestored = identity != null && target.replaceIdentity(identity)
            if (identity != null && !identityRestored) throw IOException("Could not store the identity")
            if (!identityRestored) target.reloadChats()
            return ImportResult(added, skipped, failed, identityRestored)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun moveMedia(staged: File, directory: File): File {
        directory.mkdirs()
        val name = "import_" + UUID.randomUUID().toString().replace("-", "").take(16) +
            (staged.extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: "")
        val target = File(directory, name)
        if (!staged.renameTo(target)) {
            staged.copyTo(target, overwrite = false)
            staged.delete()
        }
        return target
    }
}
