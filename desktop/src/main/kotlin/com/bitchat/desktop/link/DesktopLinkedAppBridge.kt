package com.bitchat.desktop.link

import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.linked.LinkCommand
import com.bitchat.android.linked.LinkEntry
import com.bitchat.android.linked.LinkFavorite
import com.bitchat.android.linked.LinkPeer
import com.bitchat.android.linked.LinkedAppBridge
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.wearlink.IdentityBundle
import com.bitchat.desktop.core.DesktopIdentity
import com.bitchat.desktop.core.DesktopMeshNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.net.InetAddress

/**
 * Desktop implementation of [LinkedAppBridge] connecting [com.bitchat.android.linked.LinkCoordinator]
 * to [DesktopIdentity], [AppStateStore], and [DesktopMeshNode].
 */
class DesktopLinkedAppBridge(
    private val identity: DesktopIdentity,
    private val node: DesktopMeshNode,
    private val onRestartRequested: () -> Unit = {},
    private val onRadiosSuppressedChanged: (Boolean) -> Unit = {},
) : LinkedAppBridge {

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    override val changes: Flow<Unit> = _changes.asSharedFlow()

    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    override fun nickname(): String = identity.nickname

    /** Same rename path as the window: persist, update the shared store and announce at once. */
    override fun setNickname(name: String) {
        node.rename(name)
        notifyChanged()
    }

    override suspend fun entriesSince(since: Long): List<LinkEntry> =
        currentEntries().filter { it.message.timestamp.time >= since }

    /** Reads the node, the same store the window shows, so both see one history. */
    override fun currentEntries(): List<LinkEntry> {
        val out = ArrayList<LinkEntry>()
        for (m in node.publicMessages.value) {
            out += LinkEntry(message = m, conversation = null, read = true)
        }
        val readIds = node.readPrivateIDs.value
        for ((peerId, list) in node.privateChats.value) {
            for (m in list) {
                out += LinkEntry(message = m, conversation = peerId, read = m.id in readIds)
            }
        }
        return out
    }

    override suspend fun applyEntries(entries: List<LinkEntry>): List<String> {
        val missingMedia = ArrayList<String>()
        for (entry in entries) {
            node.restore(entry.conversation, entry.message, read = entry.read)
            if (entry.conversation != null && entry.read) node.markPrivateRead(listOf(entry.message.id))
            if (entry.message.type != BitchatMessageType.Message && mediaFile(entry.message.id) == null) {
                missingMedia += entry.message.id
            }
        }
        return missingMedia
    }

    override fun favorites(): List<LinkFavorite> =
        FavoritesPersistenceService.shared.getAllRelationships().map { rel ->
            LinkFavorite(
                noiseKeyHex = rel.peerNoisePublicKey.joinToString("") { "%02x".format(it) },
                nickname = rel.peerNickname,
                isFavorite = rel.isFavorite,
                updatedAt = rel.favoritedAt.time,
            )
        }

    /**
     * Writes favorites synced from a linked device into the shared [FavoritesPersistenceService]
     * (persisted, encrypted). Entries without a valid 32-byte Noise key are ignored, and an entry
     * older than what is stored does not overwrite it.
     */
    override fun applyFavorites(favorites: List<LinkFavorite>) {
        if (favorites.isEmpty()) return
        val service = FavoritesPersistenceService.shared
        for (fav in favorites) {
            val hex = fav.noiseKeyHex.trim().lowercase()
            if (!ContactIdentityResolver.isNoiseKeyHex(hex)) continue
            val key = ContactIdentityResolver.bytesFromHex(hex)?.takeIf { it.size == 32 } ?: continue
            val existing = service.getFavoriteStatus(key)
            if (existing != null && existing.lastUpdated.time > fav.updatedAt) continue
            if (existing != null && existing.isFavorite == fav.isFavorite && existing.peerNickname == fav.nickname) continue
            val nickname = fav.nickname.trim().take(MAX_NICKNAME_CHARS).ifEmpty { existing?.peerNickname ?: "" }
            service.updateFavoriteStatus(key, nickname, fav.isFavorite)
        }
        notifyChanged()
    }

    override fun peers(): List<LinkPeer> =
        node.peers.value.map { peerId ->
            LinkPeer(
                peerID = peerId,
                nickname = node.peerNickname(peerId) ?: peerId.take(8),
                connected = peerId in AppStateStore.getDirectPeers(),
                hasSession = node.hasEstablishedSession(peerId),
            )
        }

    override fun applyMirrorPeers(peers: List<LinkPeer>?) {
        if (peers != null) {
            AppStateStore.setTransportPeers("link-mirror", peers.map { it.peerID })
        } else {
            AppStateStore.setTransportPeers("link-mirror", emptyList())
        }
    }

    override fun setRadiosSuppressed(suppressed: Boolean) {
        onRadiosSuppressedChanged(suppressed)
    }

    override suspend fun execute(command: LinkCommand, media: ByteArray?) {
        when (command) {
            is LinkCommand.SendPublic -> node.sendPublic(command.content)
            is LinkCommand.SendPrivate ->
                node.sendPrivate(command.content, command.peerID, command.recipientNickname, command.messageID)
            else -> Unit
        }
    }

    override fun mediaFile(messageID: String): Pair<String, ByteArray>? {
        val dir = File(identity.context.cacheDir, "link_media")
        val safeId = messageID.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val f = dir.listFiles()?.firstOrNull { it.name.startsWith("${safeId}_") } ?: return null
        return f.name.removePrefix("${safeId}_") to f.readBytes()
    }

    override suspend fun onMediaReceived(messageID: String, fileName: String, data: ByteArray) {
        val dir = File(identity.context.cacheDir, "link_media").apply { mkdirs() }
        val safeId = messageID.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        File(dir, "${safeId}_$safeName").writeBytes(data)
    }

    override fun identityBundle(): ByteArray? =
        IdentityBundle.read(identity.context, identity.nickname)?.let { IdentityBundle.encode(it) }

    override suspend fun adoptPairing(
        identity: ByteArray,
        entries: List<LinkEntry>,
        favorites: List<LinkFavorite>,
        media: Map<String, Pair<String, ByteArray>>,
    ): Boolean {
        val decoded = IdentityBundle.decode(identity) ?: return false
        if (!IdentityBundle.replace(this.identity.context, decoded)) return false
        this.identity.nickname = decoded.nickname
        for ((id, pair) in media) {
            onMediaReceived(id, pair.first, pair.second)
        }
        applyEntries(entries)
        applyFavorites(favorites)
        return true
    }

    override suspend fun eraseIdentity() {
        AppStateStore.clearAll()
    }

    override fun restartProcess() = onRestartRequested()

    override fun deviceName(): String = try {
        InetAddress.getLocalHost().hostName
    } catch (_: Exception) {
        "Skein Desktop"
    }

    private companion object {
        const val MAX_NICKNAME_CHARS = 32
    }
}
