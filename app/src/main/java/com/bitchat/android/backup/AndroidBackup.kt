package com.bitchat.android.backup

import android.app.backup.BackupManager
import android.content.Context
import android.util.Log
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.MessageHistory
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * Preferences for opt-in Android Auto Backup (cloud / device-to-device).
 *
 * Disabled by default. Enabling it allows [SkeinBackupAgent] to package an encrypted portable
 * snapshot of the identity and text chats (excluding media to stay under Android's 25 MB quota).
 */
object AndroidBackupPreferences {
    private const val PREFS = "bitchat_prefs"
    private const val KEY_ENABLED = "android_backup_enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (!enabled) {
            AndroidBackupRestorer.stagedBackupFile(app).delete()
            AndroidBackupRestorer.pendingRestoreFile(app).delete()
        }
        try {
            BackupManager(app).dataChanged()
        } catch (_: Exception) {
        }
    }

    fun clearOnPanic(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, false)
            .commit()
        AndroidBackupRestorer.stagedBackupFile(app).delete()
        AndroidBackupRestorer.pendingRestoreFile(app).delete()
        try {
            BackupManager(app).dataChanged()
        } catch (_: Exception) {
        }
    }
}

/**
 * Portable envelope for Android Auto Backup: `"SKEINAB1"` (8 bytes) + 256-bit random key
 * (32 bytes) + `.skein` encrypted archive. Android 9+ encrypts cloud backups end-to-end with
 * the user's screen lock, and this envelope keeps the exported identity independent of the old
 * device's hardware Keystore.
 */
object AndroidBackupArchive {
    private val MAGIC = "SKEINAB1".toByteArray(Charsets.US_ASCII)
    private const val KEY_BYTES = 32
    const val MAX_BACKUP_BYTES = 24L * 1024 * 1024

    fun write(output: OutputStream, content: BackupContent, random: SecureRandom = SecureRandom()) {
        val key = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
        try {
            output.write(MAGIC)
            output.write(key)
            SkeinCrypto.encrypt(output, SkeinCrypto.KeySource.RawKey(key)).use { plain ->
                SkeinArchive.write(plain, content, ProgressListener { _, _ -> })
            }
        } finally {
            key.fill(0)
        }
    }

    fun read(input: InputStream, stagingDir: File, maxBytes: Long = MAX_BACKUP_BYTES): ImportedArchive {
        val header = ByteArray(MAGIC.size + KEY_BYTES)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) throw SkeinFormatException("Android backup header is truncated")
            read += n
        }
        val magic = header.copyOfRange(0, MAGIC.size)
        if (!magic.contentEquals(MAGIC)) {
            throw SkeinFormatException("Unrecognized Android backup format")
        }
        val key = header.copyOfRange(MAGIC.size, header.size)
        return try {
            SkeinArchive.read(
                encrypted = input,
                keySource = SkeinCrypto.KeySource.RawKey(key),
                stagingDir = stagingDir,
                totalBytes = maxBytes,
                progress = ProgressListener { _, _ -> }
            )
        } finally {
            key.fill(0)
        }
    }
}

/**
 * Applies a pending Android Auto Backup restore on startup before identity/chat singletons load.
 */
object AndroidBackupRestorer {
    private const val TAG = "AndroidBackupRestorer"
    const val BACKUP_FILE_NAME = "android_auto_backup.skein"
    private const val PENDING_FILE_NAME = "pending_android_restore.skein"

    fun stagedBackupFile(context: Context): File =
        File(context.applicationContext.filesDir, BACKUP_FILE_NAME)

    fun pendingRestoreFile(context: Context): File =
        File(context.applicationContext.filesDir, PENDING_FILE_NAME)

    fun restoreIfPending(
        context: Context,
        target: BackupTarget = DeviceBackupTarget(context),
        stagingDir: File = DeviceBackup.stagingDir(context)
    ): Boolean {
        val app = context.applicationContext
        val pending = pendingRestoreFile(app)
        if (!pending.isFile) return false
        return try {
            val archive = pending.inputStream().buffered().use {
                AndroidBackupArchive.read(it, stagingDir, AndroidBackupArchive.MAX_BACKUP_BYTES)
            }
            // Ensure message history is enabled if the backup carried conversations.
            if (archive.conversations.isNotEmpty() && !MessageHistory.isEnabled(app)) {
                MessageHistory.setEnabled(app, true)
            }
            val result = runBlocking {
                BackupImporter.apply(archive, ImportMode.REPLACE, target, stagingDir)
            }
            Log.i(TAG, "Restored Android backup: ${result.added} messages, identity=${result.identityRestored}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply pending Android backup: ${e.javaClass.simpleName}")
            false
        } finally {
            pending.delete()
            stagingDir.deleteRecursively()
        }
    }
}
