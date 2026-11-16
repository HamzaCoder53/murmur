package com.bitchat.desktop.shim

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Transforms the serialized preference file (for example AES-GCM encryption). */
interface PreferenceCodec {
    fun encode(plain: ByteArray): ByteArray
    fun decode(stored: ByteArray): ByteArray
}

/**
 * SharedPreferences backed by one JSON file. Every value is stored with its type tag so that
 * Int/Long/Float survive a round trip exactly. Writes go to an owner-only temporary file that is
 * then moved over the original, so a crash never leaves a half-written file. `commit()` writes
 * synchronously; `apply()` updates memory immediately and persists on a single-thread executor.
 */
class FileSharedPreferences(
    private val file: File,
    private val codec: PreferenceCodec?,
) : SharedPreferences {
    private val lock = Any()
    private val values: MutableMap<String, Any> = load()
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = synchronized(lock) { HashMap(values) }

    override fun getString(key: String?, defValue: String?): String? =
        typedGet(key, defValue) { it as String }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        typedGet(key, defValues) { (it as Set<String>).toSet() }

    override fun getInt(key: String?, defValue: Int): Int =
        typedGet(key, defValue) { it as Int }

    override fun getLong(key: String?, defValue: Long): Long =
        typedGet(key, defValue) { it as Long }

    override fun getFloat(key: String?, defValue: Float): Float =
        typedGet(key, defValue) { it as Float }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        typedGet(key, defValue) { it as Boolean }

    override fun contains(key: String?): Boolean = synchronized(lock) { values.containsKey(key) }
    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners.addIfAbsent(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listeners.remove(listener)
    }

    private inline fun <T> typedGet(key: String?, default: T, cast: (Any) -> T): T {
        val raw = synchronized(lock) { values[key] } ?: return default
        return cast(raw)
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>() // null value = remove
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = value }
        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor =
            apply { pending[requireNotNull(key)] = values?.toSet() }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = value }
        override fun remove(key: String?): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = null }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        private fun applyToMemory(): Pair<List<String>, ByteArray?> = synchronized(lock) {
            val changed = ArrayList<String>()
            if (clear) {
                for (existing in values.keys) {
                    if (existing !in pending) changed += existing
                }
                values.clear()
            }
            for ((k, v) in pending) {
                if (v == null) values.remove(k) else values[k] = v
                changed += k
            }
            changed to serializeSnapshot()
        }

        private fun notifyListeners(changed: List<String>) {
            for (key in changed) {
                for (l in listeners) l.onSharedPreferenceChanged(this@FileSharedPreferences, key)
            }
        }

        override fun commit(): Boolean {
            val (changed, plain) = applyToMemory()
            val ok = plain != null && writeSnapshot(plain)
            notifyListeners(changed)
            return ok
        }

        override fun apply() {
            val (changed, plain) = applyToMemory()
            if (plain != null) {
                applyExecutor.execute { writeSnapshot(plain) }
            }
            notifyListeners(changed)
        }
    }

    private fun load(): MutableMap<String, Any> {
        val result = HashMap<String, Any>()
        if (!file.isFile) return result
        try {
            val raw = file.readBytes()
            val bytes = codec?.decode(raw) ?: raw
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            for (key in json.keys()) {
                val entry = json.getJSONObject(key)
                val value: Any = when (entry.getString("t")) {
                    "s" -> entry.getString("v")
                    "i" -> entry.getInt("v")
                    "l" -> entry.getLong("v")
                    "f" -> entry.optInt("bits", Int.MIN_VALUE).let { bits ->
                        if (entry.has("bits")) Float.fromBits(bits) else entry.getDouble("v").toFloat()
                    }
                    "b" -> entry.getBoolean("v")
                    "ss" -> entry.getJSONArray("v").let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                    else -> continue
                }
                result[key] = value
            }
            return result
        } catch (e: Exception) {
            moveAsideCorruptFile()
            android.util.Log.e("FileSharedPreferences", "Unreadable preference file ${file.name}: ${e.javaClass.simpleName}")
            if (codec != null) {
                throw GeneralSecurityException("Failed to decrypt preference file ${file.name}", e)
            }
            return result
        }
    }

    private fun moveAsideCorruptFile() {
        try {
            if (file.isFile) {
                val backup = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
                Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
        }
    }

    private fun serializeSnapshot(): ByteArray? = try {
        val json = JSONObject()
        for ((k, v) in values) {
            val entry = JSONObject()
            when (v) {
                is String -> entry.put("t", "s").put("v", v)
                is Int -> entry.put("t", "i").put("v", v)
                is Long -> entry.put("t", "l").put("v", v)
                is Float -> entry.put("t", "f").put("bits", v.toRawBits())
                is Boolean -> entry.put("t", "b").put("v", v)
                is Set<*> -> entry.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            json.put(k, entry)
        }
        json.toString().toByteArray(Charsets.UTF_8)
    } catch (e: Exception) {
        android.util.Log.e("FileSharedPreferences", "Failed to serialize ${file.name}: ${e.javaClass.simpleName}")
        null
    }

    private fun writeSnapshot(plain: ByteArray): Boolean = synchronized(fileWriteLock) {
        try {
            val stored = codec?.encode(plain) ?: plain
            OwnerOnlyFiles.writeAtomically(file, stored)
            true
        } catch (e: Exception) {
            android.util.Log.e("FileSharedPreferences", "Failed to write ${file.name}: ${e.javaClass.simpleName}")
            false
        }
    }

    private val fileWriteLock = Any()

    companion object {
        private val applyExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "skein-prefs-io").apply { isDaemon = true }
        }

        /** Blocks until all queued async apply() writes have finished. */
        fun flushPendingWrites() {
            try {
                applyExecutor.submit { }.get()
            } catch (_: Exception) {
            }
        }
    }
}

/** Best-effort owner-only permissions (POSIX on macOS; ACL inheritance on Windows). */
object OwnerOnlyFiles {
    private val posixFilePerms = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    private val posixDirPerms = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

    fun ensureDir(dir: File) {
        if (!dir.exists()) {
            try {
                if (Files.getFileAttributeView(dir.toPath().parent ?: dir.toPath(), PosixFileAttributeView::class.java) != null) {
                    Files.createDirectories(dir.toPath(), posixDirPerms)
                    return
                }
            } catch (_: Exception) {
            }
            dir.mkdirs()
        }
        restrict(dir)
    }

    fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.let { ensureDir(it) }
        val tmpPath = try {
            val view = target.parentFile?.let {
                Files.getFileAttributeView(it.toPath(), PosixFileAttributeView::class.java)
            }
            if (view != null) {
                Files.createTempFile(target.parentFile.toPath(), target.name, ".tmp", posixFilePerms)
            } else {
                Files.createTempFile(target.parentFile.toPath(), target.name, ".tmp")
            }
        } catch (_: Exception) {
            File(target.parentFile, "${target.name}.tmp").toPath()
        }
        try {
            Files.write(tmpPath, bytes)
            restrict(tmpPath.toFile())
            try {
                Files.move(tmpPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmpPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            try { Files.deleteIfExists(tmpPath) } catch (_: Exception) { }
            throw e
        }
    }

    fun restrict(file: File) {
        try {
            val view = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java)
            if (view != null) {
                val perms = if (file.isDirectory) "rwx------" else "rw-------"
                view.setPermissions(PosixFilePermissions.fromString(perms))
            }
        } catch (_: Exception) {
        }
    }
}
