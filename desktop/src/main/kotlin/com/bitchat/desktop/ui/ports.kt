package com.bitchat.desktop.ui

import androidx.compose.runtime.Composable
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * Ports: the small interfaces the desktop UI talks to. Other desktop builders plug their real
 * implementations in here (mesh node, DesktopStore persistence, Nostr location channels, linked
 * devices / backups panels, OS toggles). Every port has an in-memory fake so the UI runs and is
 * unit-testable without radios, network or disk.
 */

/** Which conversation a message belongs to. */
sealed class ConversationId {
    data object Mesh : ConversationId()
    data class Location(val geohash: String) : ConversationId()
    data class Private(val peerID: String) : ConversationId()
}

enum class SessionState { NONE, HANDSHAKING, ESTABLISHED }

enum class DeliveryState { SENDING, SENT, DELIVERED, READ, FAILED }

data class ChatMessage(
    val id: String,
    val conversation: ConversationId,
    val senderPeerID: String?,
    val senderNickname: String,
    val text: String,
    val timestamp: Date,
    val isMine: Boolean,
    val delivery: DeliveryState? = null,
)

data class PeerSummary(
    val peerID: String,
    val nickname: String,
    val session: SessionState = SessionState.NONE,
)

data class LocationChannelInfo(val geohash: String, val participants: Int = 0)

/** The mesh (Bluetooth / loopback) messenger. Real adapter: [MeshMessagingAdapter]. */
interface MessagingPort {
    val myPeerID: String
    val myFingerprint: String
    val peers: StateFlow<List<PeerSummary>>
    val messages: StateFlow<List<ChatMessage>>
    fun sendPublic(text: String)
    fun sendPrivate(peerID: String, text: String)
    fun startSession(peerID: String)
    /** Full Noise static-key fingerprint (64 hex chars) of a peer, if known. */
    fun peerFingerprint(peerID: String): String?
    fun setNickname(nickname: String)
    fun nickname(): String
}

/** Favorites and fingerprint verification (persisted by the storage builder's DesktopStore). */
interface ContactsPort {
    val favorites: StateFlow<Set<String>>
    val verifiedFingerprints: StateFlow<Set<String>>
    fun setFavorite(peerID: String, nickname: String, favorite: Boolean)
    fun setVerified(fingerprint: String, verified: Boolean)
}

/** Message history persistence (DesktopStore). Only used while "save history" is on. */
interface HistoryPort {
    fun load(): List<ChatMessage>
    fun append(message: ChatMessage)
    fun clear()
}

/** Geohash location channels over the Nostr internet client. */
interface LocationChannelsPort {
    val channels: StateFlow<List<LocationChannelInfo>>
    val messages: StateFlow<List<ChatMessage>>
    fun join(geohash: String)
    fun leave(geohash: String)
    fun send(geohash: String, text: String)
}

/** Persisted UI settings. */
interface SettingsPort {
    fun load(): DesktopUiSettings
    fun save(settings: DesktopUiSettings)
}

/** OS integrations behind settings switches (DesktopPlatform). */
interface PlatformTogglesPort {
    val isMac: Boolean
    /** False when this install cannot register itself to start at login (e.g. `gradlew run`). */
    val startAtLoginSupported: Boolean get() = true
    /** False when this build has no crash-report endpoint. */
    val crashReportsAvailable: Boolean get() = true
    /** Whether the user already answered the crash-report opt-in question. */
    fun crashReportsDecided(): Boolean = true
    fun startAtLogin(): Boolean
    fun setStartAtLogin(enabled: Boolean)
    fun crashReports(): Boolean
    fun setCrashReports(enabled: Boolean)
    fun setShowInDock(visible: Boolean)
    fun setBroadcastPower(power: BroadcastPower)
}

/** Composable panels other builders own (linked devices pairing, backup export/import). */
interface FeaturePanels {
    @Composable fun LinkedDevices(strings: UiStrings)
    @Composable fun Backups(strings: UiStrings)
}

data class AppInfo(val version: String = "1.0.0")

/** Everything the UI needs, bundled. */
data class SkeinPorts(
    val messaging: MessagingPort,
    val contacts: ContactsPort = InMemoryContactsPort(),
    val history: HistoryPort = InMemoryHistoryPort(),
    val locations: LocationChannelsPort = InMemoryLocationChannelsPort(),
    val settings: SettingsPort = InMemorySettingsPort(),
    val platform: PlatformTogglesPort = InMemoryPlatformToggles(),
    val panels: FeaturePanels = PlaceholderFeaturePanels,
    val appInfo: AppInfo = AppInfo(),
)

// ---------------------------------------------------------------------------------------------
// In-memory fakes
// ---------------------------------------------------------------------------------------------

class InMemoryMessagingPort(
    override val myPeerID: String = "0123456789abcdef",
    override val myFingerprint: String = "0123456789abcdef".repeat(4),
    private var myNickname: String = "anon1234",
    private val clock: () -> Date = { Date() },
) : MessagingPort {
    private val _peers = MutableStateFlow<List<PeerSummary>>(emptyList())
    override val peers: StateFlow<List<PeerSummary>> = _peers.asStateFlow()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val fingerprints = mutableMapOf<String, String>()

    fun addPeer(peer: PeerSummary, fingerprint: String? = null) {
        _peers.update { list -> list.filterNot { it.peerID == peer.peerID } + peer }
        if (fingerprint != null) fingerprints[peer.peerID] = fingerprint
    }

    fun removePeer(peerID: String) = _peers.update { list -> list.filterNot { it.peerID == peerID } }

    fun receive(message: ChatMessage) = _messages.update { it + message }

    fun updateDelivery(id: String, state: DeliveryState) =
        _messages.update { list -> list.map { if (it.id == id) it.copy(delivery = state) else it } }

    override fun sendPublic(text: String) = post(ConversationId.Mesh, text)

    override fun sendPrivate(peerID: String, text: String) = post(ConversationId.Private(peerID), text)

    private fun post(conversation: ConversationId, text: String) {
        _messages.update {
            it + ChatMessage(
                id = UUID.randomUUID().toString(),
                conversation = conversation,
                senderPeerID = myPeerID,
                senderNickname = myNickname,
                text = text,
                timestamp = clock(),
                isMine = true,
                delivery = if (conversation is ConversationId.Private) DeliveryState.SENT else null,
            )
        }
    }

    override fun startSession(peerID: String) = _peers.update { list ->
        list.map { if (it.peerID == peerID) it.copy(session = SessionState.ESTABLISHED) else it }
    }

    override fun peerFingerprint(peerID: String): String? = fingerprints[peerID]
    override fun setNickname(nickname: String) { myNickname = nickname }
    override fun nickname(): String = myNickname
}

class InMemoryContactsPort : ContactsPort {
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    override val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()
    private val _verified = MutableStateFlow<Set<String>>(emptySet())
    override val verifiedFingerprints: StateFlow<Set<String>> = _verified.asStateFlow()

    override fun setFavorite(peerID: String, nickname: String, favorite: Boolean) =
        _favorites.update { if (favorite) it + peerID else it - peerID }

    override fun setVerified(fingerprint: String, verified: Boolean) {
        val key = fingerprint.lowercase()
        _verified.update { if (verified) it + key else it - key }
    }
}

class InMemoryHistoryPort : HistoryPort {
    private val stored = mutableListOf<ChatMessage>()
    override fun load(): List<ChatMessage> = synchronized(stored) { stored.toList() }
    override fun append(message: ChatMessage) { synchronized(stored) { stored += message } }
    override fun clear() { synchronized(stored) { stored.clear() } }
}

class InMemoryLocationChannelsPort(
    private val myNickname: () -> String = { "anon1234" },
    private val clock: () -> Date = { Date() },
) : LocationChannelsPort {
    private val _channels = MutableStateFlow<List<LocationChannelInfo>>(emptyList())
    override val channels: StateFlow<List<LocationChannelInfo>> = _channels.asStateFlow()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    override fun join(geohash: String) = _channels.update { list ->
        if (list.any { it.geohash == geohash }) list else list + LocationChannelInfo(geohash)
    }

    override fun leave(geohash: String) = _channels.update { list -> list.filterNot { it.geohash == geohash } }

    override fun send(geohash: String, text: String) = _messages.update {
        it + ChatMessage(
            id = UUID.randomUUID().toString(),
            conversation = ConversationId.Location(geohash),
            senderPeerID = null,
            senderNickname = myNickname(),
            text = text,
            timestamp = clock(),
            isMine = true,
        )
    }

    fun receive(message: ChatMessage) = _messages.update { it + message }
}

class InMemorySettingsPort(private var current: DesktopUiSettings = DesktopUiSettings()) : SettingsPort {
    override fun load(): DesktopUiSettings = current
    override fun save(settings: DesktopUiSettings) { current = settings }
}

class InMemoryPlatformToggles(override val isMac: Boolean = false) : PlatformTogglesPort {
    var autostart = false
    var crash = false
    var dockVisible = true
    var power = BroadcastPower.MEDIUM
    override fun startAtLogin(): Boolean = autostart
    override fun setStartAtLogin(enabled: Boolean) { autostart = enabled }
    override fun crashReports(): Boolean = crash
    override fun setCrashReports(enabled: Boolean) { crash = enabled }
    override fun setShowInDock(visible: Boolean) { dockVisible = visible }
    override fun setBroadcastPower(power: BroadcastPower) { this.power = power }
}
