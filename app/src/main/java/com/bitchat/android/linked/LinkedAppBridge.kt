package com.bitchat.android.linked

import kotlinx.coroutines.flow.Flow

/**
 * Everything the link coordinator needs from the app. The production implementation
 * ([AndroidLinkedAppBridge]) talks to AppStateStore, the mesh and storage; tests use a fake.
 */
interface LinkedAppBridge {
    /** Emits whenever chat state changed (messages, read state, peers), so the leader pushes. */
    val changes: Flow<Unit>

    fun nickname(): String
    fun setNickname(name: String)

    /** History to hand over: every message with a timestamp at or after [since]. */
    suspend fun entriesSince(since: Long): List<LinkEntry>

    /** What this device currently shows, for the leader's mirror deltas. */
    fun currentEntries(): List<LinkEntry>

    /** Merge entries from a linked device. Returns IDs of media messages whose file is not here. */
    suspend fun applyEntries(entries: List<LinkEntry>): List<String>

    fun favorites(): List<LinkFavorite>
    fun applyFavorites(favorites: List<LinkFavorite>)

    /** Mesh peers as this device sees them (leader only). */
    fun peers(): List<LinkPeer>

    /** Show the leader's peers while following; null when this device stops following. */
    fun applyMirrorPeers(peers: List<LinkPeer>?)

    /** Turn this device's own mesh radios off (follower) or back on. */
    fun setRadiosSuppressed(suppressed: Boolean)

    /** Leader: carry out a follower's request on the mesh. [media] holds a [LinkCommand.SendFile]'s bytes. */
    suspend fun execute(command: LinkCommand, media: ByteArray?)

    /** File name and bytes of a media message, if this device has the file. */
    fun mediaFile(messageID: String): Pair<String, ByteArray>?

    /** A media file fetched from a linked device arrived. */
    suspend fun onMediaReceived(messageID: String, fileName: String, data: ByteArray)

    /** This device's identity, encoded for a newly linked device; null if it has none. */
    fun identityBundle(): ByteArray?

    /**
     * New device: replace this device's identity and history with what the existing device sent.
     * Returns false (changing nothing that matters) when the identity cannot be read or stored.
     */
    suspend fun adoptPairing(
        identity: ByteArray,
        entries: List<LinkEntry>,
        favorites: List<LinkFavorite>,
        media: Map<String, Pair<String, ByteArray>>
    ): Boolean

    /** Erase the shared identity, saved chats and media on this device. */
    suspend fun eraseIdentity()

    /** Restart the app process so the mesh comes back with the (new) identity. */
    fun restartProcess()

    fun deviceName(): String
}
