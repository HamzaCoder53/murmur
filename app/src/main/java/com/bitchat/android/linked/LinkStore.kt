package com.bitchat.android.linked

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class LinkedDevice(
    val deviceId: String,
    val name: String,
    val key: ByteArray,
    val lastSeen: Long = 0L,
    /** When the two last finished exchanging history; the next backlog starts here. */
    val lastSyncAt: Long = 0L
) {
    override fun equals(other: Any?) = other is LinkedDevice && other.deviceId == deviceId &&
        other.name == name && other.lastSeen == lastSeen && other.lastSyncAt == lastSyncAt && other.key.contentEquals(key)

    override fun hashCode() = deviceId.hashCode()
}

/**
 * Persistent link state. Keys live only here (encrypted at rest in the app implementation) and
 * never in the identity bundle, so one device's links cannot be lifted by pairing another.
 */
interface LinkStore {
    val myDeviceId: String
    fun devices(): List<LinkedDevice>
    fun device(id: String): LinkedDevice? = devices().firstOrNull { it.deviceId == id }
    fun put(device: LinkedDevice)
    fun remove(id: String)

    /** Devices that must still be told to erase the identity, with the key to reach them. */
    fun pendingWipes(): Map<String, ByteArray>
    fun addPendingWipe(id: String, key: ByteArray, panic: Boolean)
    fun isPanicWipe(id: String): Boolean
    fun removePendingWipe(id: String)

    /** Introductions waiting to be delivered to a device the next time it is reachable. */
    fun pendingIntroductions(forDevice: String): List<LinkFrame.Introduce>
    fun addPendingIntroduction(forDevice: String, intro: LinkFrame.Introduce)
    fun clearPendingIntroductions(forDevice: String)

    var nicknameAt: Long
    var lastKnownNickname: String?

    /** Forget every link but keep pending wipes (and the device ID that authenticates them). */
    fun clearLinks()
}

class InMemoryLinkStore(override val myDeviceId: String) : LinkStore {
    private val devices = LinkedHashMap<String, LinkedDevice>()
    private val wipes = LinkedHashMap<String, Pair<ByteArray, Boolean>>()
    private val intros = HashMap<String, MutableList<LinkFrame.Introduce>>()
    override var nicknameAt: Long = 0L
    override var lastKnownNickname: String? = null

    @Synchronized override fun devices() = devices.values.toList()
    @Synchronized override fun put(device: LinkedDevice) { devices[device.deviceId] = device }
    @Synchronized override fun remove(id: String) { devices.remove(id); intros.remove(id) }
    @Synchronized override fun pendingWipes() = wipes.mapValues { it.value.first }
    @Synchronized override fun addPendingWipe(id: String, key: ByteArray, panic: Boolean) {
        wipes[id] = key to (panic || wipes[id]?.second == true)
    }
    @Synchronized override fun isPanicWipe(id: String) = wipes[id]?.second == true
    @Synchronized override fun removePendingWipe(id: String) { wipes.remove(id) }
    @Synchronized override fun pendingIntroductions(forDevice: String) = intros[forDevice].orEmpty().toList()
    @Synchronized override fun addPendingIntroduction(forDevice: String, intro: LinkFrame.Introduce) {
        intros.getOrPut(forDevice) { mutableListOf() }.apply { removeAll { it.deviceId == intro.deviceId }; add(intro) }
    }
    @Synchronized override fun clearPendingIntroductions(forDevice: String) { intros.remove(forDevice) }
    @Synchronized override fun clearLinks() {
        devices.clear(); intros.clear(); nicknameAt = 0L; lastKnownNickname = null
    }
}

/** [LinkStore] in an encrypted preferences file of its own (not part of any identity bundle). */
class PrefsLinkStore(context: Context) : LinkStore {
    companion object {
        private const val FILE = "skein_linked_devices"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICES = "devices"
        private const val KEY_WIPES = "pending_wipes"
        private const val KEY_INTROS = "pending_intros"
        private const val KEY_NICK_AT = "nickname_at"
        private const val KEY_NICK = "nickname"
    }

    private val prefs: SharedPreferences = run {
        val app = context.applicationContext
        val masterKey = MasterKey.Builder(app, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app, FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override val myDeviceId: String = synchronized(this) {
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf(LinkHandshake::isDeviceId) ?: LinkCrypto.randomBytes(16).hex().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).commit()
        }
    }

    private fun readArray(key: String): JSONArray = try {
        JSONArray(prefs.getString(key, "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    @Synchronized
    override fun devices(): List<LinkedDevice> = readArray(KEY_DEVICES).let { a ->
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val key = o.optString("k").unb64() ?: return@mapNotNull null
            LinkedDevice(o.getString("id"), o.optString("n"), key, o.optLong("ls"), o.optLong("sy"))
        }
    }

    private fun writeDevices(list: List<LinkedDevice>) {
        val a = JSONArray()
        list.forEach { d ->
            a.put(JSONObject().put("id", d.deviceId).put("n", d.name).put("k", d.key.b64())
                .put("ls", d.lastSeen).put("sy", d.lastSyncAt))
        }
        prefs.edit().putString(KEY_DEVICES, a.toString()).commit()
    }

    @Synchronized
    override fun put(device: LinkedDevice) = writeDevices(devices().filter { it.deviceId != device.deviceId } + device)

    @Synchronized
    override fun remove(id: String) {
        writeDevices(devices().filter { it.deviceId != id })
        clearPendingIntroductions(id)
    }

    private fun wipes(): Map<String, Pair<ByteArray, Boolean>> = readArray(KEY_WIPES).let { a ->
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val key = o.optString("k").unb64() ?: return@mapNotNull null
            o.getString("id") to (key to o.optBoolean("p"))
        }.toMap()
    }

    private fun writeWipes(map: Map<String, Pair<ByteArray, Boolean>>) {
        val a = JSONArray()
        map.forEach { (id, v) -> a.put(JSONObject().put("id", id).put("k", v.first.b64()).put("p", v.second)) }
        prefs.edit().putString(KEY_WIPES, a.toString()).commit()
    }

    @Synchronized override fun pendingWipes() = wipes().mapValues { it.value.first }
    @Synchronized override fun addPendingWipe(id: String, key: ByteArray, panic: Boolean) {
        val current = wipes()
        writeWipes(current + (id to (key to (panic || current[id]?.second == true))))
    }
    @Synchronized override fun isPanicWipe(id: String) = wipes()[id]?.second == true
    @Synchronized override fun removePendingWipe(id: String) = writeWipes(wipes() - id)

    private fun intros(): Map<String, List<LinkFrame.Introduce>> = try {
        val o = JSONObject(prefs.getString(KEY_INTROS, "{}") ?: "{}")
        o.keys().asSequence().associateWith { target ->
            val a = o.getJSONArray(target)
            (0 until a.length()).mapNotNull { i ->
                val x = a.getJSONObject(i)
                val key = x.optString("k").unb64() ?: return@mapNotNull null
                LinkFrame.Introduce(x.getString("id"), x.optString("n"), key)
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writeIntros(map: Map<String, List<LinkFrame.Introduce>>) {
        val o = JSONObject()
        map.filterValues { it.isNotEmpty() }.forEach { (target, list) ->
            o.put(target, JSONArray().apply {
                list.forEach { put(JSONObject().put("id", it.deviceId).put("n", it.name).put("k", it.key.b64())) }
            })
        }
        prefs.edit().putString(KEY_INTROS, o.toString()).commit()
    }

    @Synchronized override fun pendingIntroductions(forDevice: String) = intros()[forDevice].orEmpty()
    @Synchronized override fun addPendingIntroduction(forDevice: String, intro: LinkFrame.Introduce) {
        val all = intros().toMutableMap()
        all[forDevice] = all[forDevice].orEmpty().filter { it.deviceId != intro.deviceId } + intro
        writeIntros(all)
    }
    @Synchronized override fun clearPendingIntroductions(forDevice: String) = writeIntros(intros() - forDevice)

    override var nicknameAt: Long
        get() = prefs.getLong(KEY_NICK_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_NICK_AT, value).apply() }

    override var lastKnownNickname: String?
        get() = prefs.getString(KEY_NICK, null)
        set(value) { prefs.edit().putString(KEY_NICK, value).apply() }

    @Synchronized
    override fun clearLinks() {
        prefs.edit().remove(KEY_DEVICES).remove(KEY_INTROS).remove(KEY_NICK_AT).remove(KEY_NICK).commit()
    }
}
