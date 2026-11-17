package com.bitchat.desktop.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitchat.android.backup.BackupChats
import com.bitchat.android.model.BitchatMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where [DesktopMeshNode] keeps conversations between runs. Private chats are keyed by the
 * conversation partner's peer ID.
 */
interface ConversationStore {
    data class Snapshot(
        val publicMessages: List<BitchatMessage> = emptyList(),
        val privateConversations: Map<String, List<BitchatMessage>> = emptyMap(),
    )

    fun load(): Snapshot
    fun save(snapshot: Snapshot)
    fun clear()

    object None : ConversationStore {
        override fun load() = Snapshot()
        override fun save(snapshot: Snapshot) = Unit
        override fun clear() = Unit
    }
}

/**
 * Conversation store sealed with AES-256-GCM through the EncryptedSharedPreferences shim, whose
 * key lives in the OS credential store (Keychain / DPAPI). Writes nothing while [isEnabled]
 * returns false (the "Save message history" switch) and erases what was saved.
 */
class EncryptedConversationStore(
    private val context: Context,
    private val isEnabled: () -> Boolean,
) : ConversationStore {
    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): ConversationStore.Snapshot {
        if (!isEnabled()) return ConversationStore.Snapshot()
        val raw = prefs.getString(KEY, null) ?: return ConversationStore.Snapshot()
        return try {
            val root = JSONObject(raw)
            val pub = decodeList(root.optJSONArray("public"))
            val privObj = root.optJSONObject("private") ?: JSONObject()
            val priv = privObj.keys().asSequence().associateWith { decodeList(privObj.optJSONArray(it)) }
            ConversationStore.Snapshot(pub, priv)
        } catch (_: Exception) {
            ConversationStore.Snapshot()
        }
    }

    override fun save(snapshot: ConversationStore.Snapshot) {
        if (!isEnabled()) {
            clear()
            return
        }
        val root = JSONObject()
            .put("public", encodeList(snapshot.publicMessages))
            .put(
                "private",
                JSONObject().apply {
                    snapshot.privateConversations.forEach { (peer, list) -> put(peer, encodeList(list)) }
                },
            )
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    override fun clear() {
        if (prefs.contains(KEY)) prefs.edit().remove(KEY).apply()
    }

    private fun encodeList(list: List<BitchatMessage>): JSONArray =
        JSONArray().apply { list.forEach { put(BackupChats.encodeMessage(it, null)) } }

    private fun decodeList(arr: JSONArray?): List<BitchatMessage> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try { BackupChats.decodeMessage(arr.getJSONObject(i)).message } catch (_: Exception) { null }
        }
    }

    private companion object {
        const val FILE_NAME = "skein_desktop_conversations"
        const val KEY = "conversations_v1"
    }
}
