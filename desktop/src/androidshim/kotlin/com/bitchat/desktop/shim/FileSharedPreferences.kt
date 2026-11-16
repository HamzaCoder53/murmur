package com.bitchat.desktop.shim

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList

/** Transforms the serialized preference file (for example AES-GCM encryption). */
interface PreferenceCodec {
    fun encode(plain: ByteArray): ByteArray
    fun decode(stored: ByteArray): ByteArray
}

/**
 * SharedPreferences backed by one JSON file. Every value is stored with its type tag so that
 * Int/Long/Float survive a round trip exactly. Writes go to a temporary file that is then moved
 * over the original, so a crash never leaves a half-written file. Both commit() and apply()
 * write synchronously; the stores the shared core keeps are tiny.
 */
class FileSharedPreferences(
    private val file: File,
    private val codec: PreferenceCodec?,
) : SharedPreferences {
    private val lock = Any()
    private val values: MutableMap<String, Any> = load()
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = synchronized(lock) { HashMap(values) }
    override fun getString(key: String?, defValue: String?): String? = get(key) as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        (get(key) as? Set<String>)?.toSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = get(key) as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = get(key) as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = get(key) as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = get(key) as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = synchronized(lock) { values.containsKey(key) }
    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners.addIfAbsent(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listeners.remove(listener)
    }

    private fun get(key: String?): Any? = synchronized(lock) { values[key] }

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

        override fun commit(): Boolean {
            val changed = ArrayList<String>()
            val ok = synchronized(lock) {
                if (clear) values.clear()
                for ((k, v) in pending) {
                    if (v == null) values.remove(k) else values[k] = v
                    changed += k
                }
                persist()
            }
            for (key in changed) for (l in listeners) l.onSharedPreferenceChanged(this@FileSharedPreferences, key)
            return ok
        }

        override fun apply() {
            commit()
        }
    }

    private fun load(): MutableMap<String, Any> {
        val result = HashMap<String, Any>()
        if (!file.isFile) return result
        val bytes = try {
            val raw = file.readBytes()
            codec?.decode(raw) ?: raw
        } catch (e: Exception) {
            // Fail closed: an unreadable (tampered or wrong-key) store starts empty and is
            // replaced on the next write, like EncryptedSharedPreferences after a key loss.
            android.util.Log.e("FileSharedPreferences", "Unreadable preference file ${file.name}: ${e.javaClass.simpleName}")
            return result
        }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        for (key in json.keys()) {
            val entry = json.getJSONObject(key)
            val value: Any = when (entry.getString("t")) {
                "s" -> entry.getString("v")
                "i" -> entry.getInt("v")
                "l" -> entry.getLong("v")
                "f" -> entry.getDouble("v").toFloat()
                "b" -> entry.getBoolean("v")
                "ss" -> entry.getJSONArray("v").let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                else -> continue
            }
            result[key] = value
        }
        return result
    }

    private fun persist(): Boolean = try {
        val json = JSONObject()
        for ((k, v) in values) {
            val entry = JSONObject()
            when (v) {
                is String -> entry.put("t", "s").put("v", v)
                is Int -> entry.put("t", "i").put("v", v)
                is Long -> entry.put("t", "l").put("v", v)
                is Float -> entry.put("t", "f").put("v", v.toDouble())
                is Boolean -> entry.put("t", "b").put("v", v)
                is Set<*> -> entry.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            json.put(k, entry)
        }
        val plain = json.toString().toByteArray(Charsets.UTF_8)
        val stored = codec?.encode(plain) ?: plain
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(stored)
        OwnerOnlyFiles.restrict(tmp)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        true
    } catch (e: Exception) {
        android.util.Log.e("FileSharedPreferences", "Failed to write ${file.name}: ${e.javaClass.simpleName}")
        false
    }
}

/** Best-effort owner-only permissions (POSIX on macOS; ACL inheritance from %APPDATA% on Windows). */
object OwnerOnlyFiles {
    fun restrict(file: File) {
        try {
            val view = Files.getFileAttributeView(file.toPath(), java.nio.file.attribute.PosixFileAttributeView::class.java)
            if (view != null) {
                val perms = if (file.isDirectory) "rwx------" else "rw-------"
                view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString(perms))
            }
        } catch (_: Exception) {
        }
    }
}
