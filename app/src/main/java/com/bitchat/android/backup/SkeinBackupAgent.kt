package com.bitchat.android.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Opt-in Android backup, as a key-value backup of one entity: an encrypted portable snapshot of
 * the identity and text chats (see [AndroidBackupArchive]).
 *
 * Key-value backup is used on purpose: file-based Auto Backup is filtered by the deny-by-default
 * rules in data_extraction_rules.xml / backup_rules.xml, which must keep excluding every raw
 * app-private file. Here nothing but this one snapshot can leave the device.
 *
 * The snapshot carries its own key, so it is only handed over when Android itself protects it:
 * a cloud backup that Android encrypts end to end with the screen lock, or a direct
 * device-to-device transfer. Otherwise nothing is backed up.
 */
class SkeinBackupAgent : BackupAgent() {

    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput,
        newState: ParcelFileDescriptor?
    ) {
        val app = applicationContext
        if (!AndroidBackupPreferences.isEnabled(app)) {
            // Writing a delete replaces any snapshot from before the switch was turned off.
            data.writeEntityHeader(ENTITY_KEY, -1)
            return
        }
        if (!isProtectedTransport(data.transportFlags)) {
            Log.w(TAG, "Android backup skipped: not end-to-end encrypted or device-to-device")
            return
        }
        try {
            val identity = DeviceBackup.readIdentity(app) ?: return
            val conversations = runBlocking { DeviceBackup.collectConversations(app) }
            var bytes = encode(BackupContent(conversations, identity, SkeinArchive.Kind.EXPORT) { null })
            if (bytes.size > MAX_ENTITY_BYTES) {
                Log.w(TAG, "Chats exceed the Android backup quota; backing up the identity only")
                bytes = encode(BackupContent(emptyList(), identity, SkeinArchive.Kind.EXPORT) { null })
            }
            if (bytes.size > MAX_ENTITY_BYTES) return
            data.writeEntityHeader(ENTITY_KEY, bytes.size)
            data.writeEntityData(bytes, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "Android backup failed: ${e.javaClass.simpleName}")
        }
    }

    override fun onRestore(data: BackupDataInput, appVersionCode: Int, newState: ParcelFileDescriptor?) {
        while (data.readNextHeader()) {
            val size = data.dataSize
            if (data.key != ENTITY_KEY || size <= 0 || size > MAX_ENTITY_BYTES) {
                data.skipEntityData()
                continue
            }
            val bytes = ByteArray(size)
            data.readEntityData(bytes, 0, size)
            val pending = AndroidBackupRestorer.pendingRestoreFile(applicationContext)
            val tmp = File(pending.parentFile, "${pending.name}.tmp")
            try {
                tmp.parentFile?.mkdirs()
                tmp.writeBytes(bytes)
                if (pending.exists()) pending.delete()
                if (!tmp.renameTo(pending)) {
                    tmp.copyTo(pending, overwrite = true)
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete()
                Log.w(TAG, "Android restore failed: ${e.javaClass.simpleName}")
            } finally {
                bytes.fill(0)
            }
        }
    }

    private fun encode(content: BackupContent): ByteArray =
        ByteArrayOutputStream().also { AndroidBackupArchive.write(it, content) }.toByteArray()

    companion object {
        private const val TAG = "SkeinBackupAgent"
        const val ENTITY_KEY = "skein_snapshot"

        /** Android's key-value backup quota is 5 MB per app; leave room for overhead. */
        const val MAX_ENTITY_BYTES = 4_500_000

        /** Only a backup Android encrypts end to end, or a direct phone-to-phone transfer. */
        fun isProtectedTransport(flags: Int): Boolean =
            flags and BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED != 0 ||
                flags and BackupAgent.FLAG_DEVICE_TO_DEVICE_TRANSFER != 0
    }
}
