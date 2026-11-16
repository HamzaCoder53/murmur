package com.bitchat.desktop.link

import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.linked.BacklogMerge
import com.bitchat.android.linked.LinkCommand
import com.bitchat.android.linked.LinkEntry
import com.bitchat.android.linked.LinkFavorite
import com.bitchat.android.linked.LinkPeer
import com.bitchat.android.linked.LinkProtocol
import com.bitchat.android.linked.LinkedAppBridge
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.wearlink.IdentityBundle
import com.bitchat.desktop.core.DesktopIdentity
import com.bitchat.desktop.core.DesktopMeshNode
import com.bitchat.desktop.shim.OwnerOnlyFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.File
import java.security.MessageDigest

/**
 * Desktop implementation of [LinkedAppBridge] connecting [com.bitchat.android.linked.LinkCoordinator]
 * to [DesktopIdentity], [AppStateStore] (the store the desktop UI shows) and [DesktopMeshNode].
 * Mirrors the phone's AndroidLinkedAppBridge.
 */
class DesktopLinkedAppBridge(
    private val identity: DesktopIdentity,
    private val node: DesktopMeshNode,
    private val onRestartRequested: () -> Unit = {},
    private val onRadiosSuppressedChanged: (Boolean) -> Unit = {},
    private val onMirrorPeersChanged: (List<LinkPeer>?) -> Unit = {},
) : LinkedAppBridge {

    companion object {
        private const val TAG = "DesktopLinkedAppBridge"
        private const val MIRROR_TRANSPORT_ID = "linked_mirror"
        const val MEDIA_DIR = "link_media"
    }

    private val context = identity.context
    private val mediaRoot: File get() = File(context.cacheDir, MEDIA_DIR)

    override val changes: Flow<Unit> = combine(
        AppStateStore.publicMessages,
        AppStateStore.privateMessages,
        AppStateStore.readPrivateMessageIDs,
        node.peers,
    ) { _, _, _, _ -> }

    override fun nickname(): String = identity.nickname

    /** Same rename path as the window: persist, update the shared store and announce at once. */
    override fun setNickname(name: String) {
        val clean = name.trim().take(LinkProtocol.MAX_NICKNAME)
        if (clean.isEmpty()) return
        node.rename(clean)
    }

    override suspend fun entriesSince(since: Long): List<LinkEntry> =
        currentEntries().filter { it.message.timestamp.time >= since }

    /** Reads the node and AppStateStore (deduplicated by message ID) so window, backups and tests agree. */
    override fun currentEntries(): List<LinkEntry> {
        val byId = LinkedHashMap<String, LinkEntry>()
        for (m in node.publicMessages.value) {
            byId[m.id] = LinkEntry(message = m, conversation = null, read = true)
        }
        val readIds = node.readPrivateIDs.value + AppStateStore.readPrivateMessageIDs.value
        for ((peerId, list) in node.privateChats.value) {
            for (m in list) {
                byId[m.id] = LinkEntry(message = m, conversation = peerId, read = m.id in readIds)
            }
        }
        for (m in AppStateStore.publicMessages.value) {
            byId.putIfAbsent(m.id, LinkEntry(message = m, conversation = null, read = true))
        }
        for ((peerId, list) in AppStateStore.privateMessages.value) {
            for (m in list) {
                byId.putIfAbsent(m.id, LinkEntry(message = m, conversation = peerId, read = m.id in readIds))
            }
        }
        return byId.values.toList()
    }

    override suspend fun applyEntries(entries: List<LinkEntry>): List<String> {
        if (entries.isEmpty()) return emptyList()
        val known = currentEntries().associate { it.message.id to (it.message to it.read) }
        val plan = BacklogMerge.plan(known, entries)
        val missingMedia = ArrayList<String>()
        for (entry in plan.add) {
            val msg = entry.message
            val conv = if (msg.isPrivate) (entry.conversation ?: msg.senderPeerID ?: msg.sender) else null
            node.restore(conv, msg, read = entry.read)
            if (conv != null && entry.read) node.markPrivateRead(listOf(msg.id))
            if (msg.type != BitchatMessageType.Message && mediaFile(msg.id) == null) {
                missingMedia += msg.id
            }
        }
        for (entry in plan.update) {
            val status = entry.message.deliveryStatus ?: continue
            if (entry.message.isPrivate) AppStateStore.updatePrivateMessageStatus(entry.message.id, status)
        }
        plan.markRead.forEach { AppStateStore.markPrivateMessageRead(it) }
        return missingMedia
    }

    override fun favorites(): List<LinkFavorite> =
        FavoritesPersistenceService.shared.getAllRelationships().map { rel ->
            LinkFavorite(
                noiseKeyHex = rel.peerNoisePublicKey.toHex(),
                nickname = rel.peerNickname,
                isFavorite = rel.isFavorite,
                updatedAt = rel.lastUpdated.time,
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
            val nickname = fav.nickname.trim().take(LinkProtocol.MAX_NICKNAME).ifEmpty { existing?.peerNickname ?: "" }
            service.updateFavoriteStatus(key, nickname, fav.isFavorite)
        }
    }

    override fun peers(): List<LinkPeer> =
        node.peers.value.filter { it != node.peerID }.map { peerId ->
            val noiseKey = node.peerNoiseKey(peerId)
            val relationship = noiseKey?.let {
                runCatching { FavoritesPersistenceService.shared.getFavoriteStatus(it) }.getOrNull()
            }
            LinkPeer(
                peerID = peerId,
                nickname = node.peerNickname(peerId) ?: peerId.take(8),
                connected = true,
                hasSession = node.hasEstablishedSession(peerId),
                noiseKeyHex = noiseKey?.toHex(),
                isFavorite = relationship?.isFavorite == true,
                theyFavoritedUs = relationship?.theyFavoritedUs == true,
            )
        }

    override fun applyMirrorPeers(peers: List<LinkPeer>?) {
        onMirrorPeersChanged(peers)
        val ids = peers.orEmpty().filter { it.connected }.map { it.peerID }
        AppStateStore.setTransportPeers(MIRROR_TRANSPORT_ID, ids)
        AppStateStore.setTransportDirectPeers(MIRROR_TRANSPORT_ID, ids)
    }

    override fun setRadiosSuppressed(suppressed: Boolean) {
        onRadiosSuppressedChanged(suppressed)
    }

    /**
     * Carries out a follower's request on the mesh. Every [LinkCommand] is handled; a command
     * whose input is invalid is refused (logged, nothing sent) rather than half-applied.
     */
    override suspend fun execute(command: LinkCommand, media: ByteArray?) {
        when (command) {
            // As on the phone, the follower's message ID is kept, so the local copy merges with the
            // follower's (AppStateStore and the node de-duplicate by ID) and is never shown twice.
            is LinkCommand.SendPublic ->
                node.sendPublic(command.content, command.messageID, command.mentions, command.channel)
            is LinkCommand.SendPrivate ->
                node.sendPrivate(command.content, command.peerID, command.recipientNickname, command.messageID)
            is LinkCommand.ReadReceipt -> {
                AppStateStore.markPrivateMessageRead(command.messageID)
                node.sendReadReceipt(command.messageID, command.peerID)
            }
            is LinkCommand.SetFavorite -> {
                val key = node.peerNoiseKey(command.peerID)
                if (key == null) {
                    Log.w(TAG, "Refusing favorite change: peer has no known Noise key")
                    return
                }
                val nick = node.peerNickname(command.peerID) ?: command.peerID.take(8)
                FavoritesPersistenceService.shared.updateFavoriteStatus(key, nick, command.isFavorite)
            }
            is LinkCommand.Announce -> setNickname(command.nickname)
            is LinkCommand.SendFile -> {
                val bytes = media
                if (bytes == null || bytes.isEmpty() || bytes.size > LinkProtocol.MAX_MEDIA_BYTES) {
                    Log.w(TAG, "Refusing file command without valid media")
                    return
                }
                val stored = saveMediaFile(command.messageID, command.fileName, bytes) ?: return
                node.sendFile(
                    command.peerID,
                    BitchatFilePacket(
                        fileName = stored.name,
                        fileSize = stored.length(),
                        mimeType = command.mimeType,
                        content = bytes,
                    ),
                )
            }
        }
    }

    override fun mediaFile(messageID: String): Pair<String, ByteArray>? {
        val f = mediaDirFor(messageID).listFiles()?.singleOrNull { it.isFile } ?: return null
        if (f.length() > LinkProtocol.MAX_MEDIA_BYTES) return null
        return try {
            f.name to f.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun onMediaReceived(messageID: String, fileName: String, data: ByteArray) {
        saveMediaFile(messageID, fileName, data)
    }

    override fun identityBundle(): ByteArray? =
        IdentityBundle.read(context, identity.nickname)?.let { IdentityBundle.encode(it) }

    override suspend fun adoptPairing(
        identity: ByteArray,
        entries: List<LinkEntry>,
        favorites: List<LinkFavorite>,
        media: Map<String, Pair<String, ByteArray>>,
    ): Boolean {
        val decoded = IdentityBundle.decode(identity) ?: return false
        if (!IdentityBundle.replace(context, decoded)) return false
        if (decoded.nickname.isNotBlank()) {
            this.identity.nickname = decoded.nickname
            AppStateStore.setNickname(this.identity.nickname)
        }
        for ((id, pair) in media) {
            saveMediaFile(id, pair.first, pair.second)
        }
        applyEntries(entries)
        applyFavorites(favorites)
        return true
    }

    /**
     * Erases the shared identity, chats, favorites, seen-message state and media. Everything is
     * attempted; if the identity keys could not be erased this throws, so the caller does not
     * restart as if the device were clean.
     */
    override suspend fun eraseIdentity() {
        try { AppStateStore.clearAll() } catch (_: Exception) { }
        try { FavoritesPersistenceService.shared.clearAllFavorites() } catch (_: Exception) { }
        try { SeenMessageStore.getInstance(context).clear() } catch (_: Exception) { }
        for (dir in listOf(MEDIA_DIR, "images", "files")) {
            try { File(context.cacheDir, dir).deleteRecursively() } catch (_: Exception) { }
        }
        val keysErased = try { IdentityBundle.erase(context) } catch (_: Exception) { false }
        check(keysErased) { "Could not erase the shared identity keys" }
    }

    override fun restartProcess() = onRestartRequested()

    /** A generic name: the host name would disclose the computer's identity to the other device. */
    override fun deviceName(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val kind = when {
            os.startsWith("mac") -> "Mac"
            os.startsWith("windows") -> "Windows PC"
            else -> "Computer"
        }
        return "Skein on $kind".take(LinkProtocol.MAX_DEVICE_NAME)
    }

    // --- Media storage: one directory per message, named by a hash of its ID (collision-free) ---

    private fun mediaDirFor(messageID: String): File = File(mediaRoot, sha256Hex(messageID))

    private fun saveMediaFile(messageID: String, fileName: String, data: ByteArray): File? {
        if (data.isEmpty() || data.size > LinkProtocol.MAX_MEDIA_BYTES) {
            Log.w(TAG, "Dropping linked media outside the size limit")
            return null
        }
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').take(96).ifBlank { "media.bin" }
        val dir = mediaDirFor(messageID)
        dir.deleteRecursively()
        OwnerOnlyFiles.ensureDir(dir)
        val file = File(dir, safeName)
        OwnerOnlyFiles.writeAtomically(file, data)
        return file
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** A 32-byte Noise key in hex, or null for anything else. */
    private fun decodeNoiseKey(hex: String): ByteArray? {
        if (hex.length != 64 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
