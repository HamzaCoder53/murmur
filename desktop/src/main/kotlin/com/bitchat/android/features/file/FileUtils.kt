package com.bitchat.android.features.file

import android.content.Context
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessageType
import java.io.File

/**
 * Desktop variant (same FQN) of the phone's FileUtils, holding only what the mirrored mesh core
 * calls (MessageHandler). The phone version also needs ContentResolver, FileProvider and
 * Environment, which have no desktop meaning; the desktop file picker and "open with" support
 * come in the UI-parity stage. Behaviour matches the phone: incoming files land in the cache
 * directory under images/incoming or files/incoming with a sanitized, de-duplicated name.
 */
object FileUtils {
    fun saveIncomingFile(context: Context, file: BitchatFilePacket): String {
        val lowerMime = file.mimeType.lowercase()
        val isImage = lowerMime.startsWith("image/")
        val dir = File(context.cacheDir, if (isImage) "images/incoming" else "files/incoming").apply { mkdirs() }
        val ext = when (lowerMime) {
            "image/jpeg", "image/jpg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "application/pdf" -> ".pdf"
            "text/plain" -> ".txt"
            else -> if (isImage) ".jpg" else ".bin"
        }
        val baseName = (file.fileName.takeIf { it.isNotBlank() } ?: (if (isImage) "img" else "file"))
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        var safeName = if (baseName.contains('.')) baseName else baseName + ext
        var idx = 1
        while (File(dir, safeName).exists() && idx < 1000) {
            val dot = safeName.lastIndexOf('.')
            safeName = if (dot > 0) "${safeName.substring(0, dot)} ($idx)${safeName.substring(dot)}" else "$safeName ($idx)"
            idx++
        }
        val out = File(dir, safeName)
        out.outputStream().use { it.write(file.content) }
        return out.absolutePath
    }

    fun messageTypeForMime(mime: String): BitchatMessageType {
        val lower = mime.lowercase()
        return when {
            lower.startsWith("image/") -> BitchatMessageType.Image
            lower.startsWith("audio/") -> BitchatMessageType.Audio
            else -> BitchatMessageType.File
        }
    }
}
