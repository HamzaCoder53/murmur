package com.bitchat.desktop.link

import com.bitchat.android.backup.BackupContent
import com.bitchat.android.backup.BackupConversation
import com.bitchat.android.backup.BackupImporter
import com.bitchat.android.backup.BackupTarget
import com.bitchat.android.backup.ImportMode
import com.bitchat.android.backup.ImportResult
import com.bitchat.android.backup.SkeinArchive
import com.bitchat.android.backup.SkeinCrypto
import java.io.File

/**
 * Exports and imports password-protected `.skein` chat/identity archives on desktop using
 * the mirrored [SkeinArchive], [SkeinCrypto], and [BackupImporter].
 */
object DesktopBackupService {
    fun exportArchive(
        outFile: File,
        passphrase: String,
        conversations: List<BackupConversation>,
        identityBundle: ByteArray? = null,
        mediaResolver: (com.bitchat.android.model.BitchatMessage) -> File? = { null },
    ) {
        val content = BackupContent(
            kind = SkeinArchive.Kind.EXPORT,
            conversations = conversations,
            identity = identityBundle,
            mediaFile = mediaResolver,
        )
        outFile.outputStream().use { fileOut ->
            SkeinCrypto.encrypt(fileOut, SkeinCrypto.KeySource.Passphrase(passphrase.toCharArray())).use { encOut ->
                SkeinArchive.write(encOut, content)
            }
        }
    }

    suspend fun importArchive(
        archiveFile: File,
        passphrase: String,
        mode: ImportMode,
        target: BackupTarget,
        stagingDir: File,
    ): ImportResult {
        val imported = archiveFile.inputStream().use { inp ->
            SkeinArchive.read(inp, SkeinCrypto.KeySource.Passphrase(passphrase.toCharArray()), stagingDir)
        }
        return BackupImporter.apply(imported, mode, target, stagingDir)
    }
}
