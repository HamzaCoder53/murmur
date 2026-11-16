package com.bitchat.android.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Custom [BackupAgent] for opt-in Android Auto Backup.
 *
 * Raw app-private storage (`shared_prefs`, `databases`, `files`) remains excluded by
 * `backup_rules.xml` and `data_extraction_rules.xml`. When enabled by the user, [onFullBackup]
 * packages a portable encrypted snapshot of the identity and text chats (without media) and
 * streams only that file. On restore, [onRestoreFile] saves it to a pending file applied by
 * [AndroidBackupRestorer.restoreIfPending] before services initialize.
 */
class SkeinBackupAgent : BackupAgent() {

    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) = Unit

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?
    ) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) {
        val app = applicationContext
        if (!AndroidBackupPreferences.isEnabled(app)) return
        val staged = AndroidBackupRestorer.stagedBackupFile(app)
        try {
            val identity = DeviceBackup.readIdentity(app) ?: return
            val conversations = runBlocking { DeviceBackup.collectConversations(app) }
            val content = BackupContent(
                conversations = conversations,
                identity = identity,
                kind = SkeinArchive.Kind.EXPORT,
                mediaFile = { null }
            )
            staged.parentFile?.mkdirs()
            staged.outputStream().buffered().use { out ->
                AndroidBackupArchive.write(out, content)
            }
            if (staged.length() > AndroidBackupArchive.MAX_BACKUP_BYTES) {
                Log.w(TAG, "Android backup skipped: archive exceeds 24 MB quota")
                return
            }
            fullBackupFile(staged, data)
        } catch (e: Exception) {
            Log.w(TAG, "Android full backup failed: ${e.javaClass.simpleName}")
        } finally {
            staged.delete()
        }
    }

    override fun onRestoreFile(
        data: ParcelFileDescriptor,
        size: Long,
        destination: File,
        type: Int,
        mode: Long,
        mtime: Long
    ) {
        val isExpected = destination.name == AndroidBackupRestorer.BACKUP_FILE_NAME &&
            size in 1L..AndroidBackupArchive.MAX_BACKUP_BYTES
        FileInputStream(data.fileDescriptor).use { input ->
            if (!isExpected) {
                drainBytes(input, size)
                return
            }
            val pending = AndroidBackupRestorer.pendingRestoreFile(applicationContext)
            val tmp = File(pending.parentFile, "${pending.name}.tmp")
            tmp.parentFile?.mkdirs()
            var copied = 0L
            try {
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (copied < size) {
                        val toRead = minOf(buf.size.toLong(), size - copied).toInt()
                        val n = input.read(buf, 0, toRead)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        copied += n
                    }
                }
                if (copied == size) {
                    if (pending.exists()) pending.delete()
                    if (!tmp.renameTo(pending)) {
                        tmp.copyTo(pending, overwrite = true)
                        tmp.delete()
                    }
                } else {
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete()
                Log.w(TAG, "Android restore file failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun drainBytes(input: FileInputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) break
            remaining -= n
        }
    }

    companion object {
        private const val TAG = "SkeinBackupAgent"
    }
}
