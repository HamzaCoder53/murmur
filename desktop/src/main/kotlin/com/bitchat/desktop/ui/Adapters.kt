package com.bitchat.desktop.ui

import android.content.Context
import com.bitchat.android.favorites.FavoritesChangeListener
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.desktop.core.DesktopMeshNode
import com.bitchat.desktop.platform.DesktopPlatform
import com.bitchat.desktop.platform.HostOs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [MessagingPort] over the real [DesktopMeshNode]. Own private messages come from the node's
 * [DesktopMeshNode.outgoingPrivate] (one message ID locally and on the wire, so receipts and the
 * session outbox update them); the node's received lists never contain own echoes, but they are
 * filtered defensively.
 */
class MeshMessagingAdapter(
    private val node: DesktopMeshNode,
    private val scope: CoroutineScope,
    private val fingerprintLookup: (String) -> String? = node::peerFingerprint,
    private val handshakeTimeoutMs: Long = DesktopMeshNode.PENDING_PRIVATE_TIMEOUT_MS,
) : MessagingPort {
    override val myPeerID: String get() = node.peerID
    override val myFingerprint: String get() = node.identity.fingerprint

    /** Peers we started a handshake with that has not finished (or timed out) yet. */
    private val handshaking = MutableStateFlow<Set<String>>(emptySet())

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerSummary>>(emptyList())
    override val peers: StateFlow<List<PeerSummary>> = _peers.asStateFlow()

    init {
        combine(node.publicMessages, node.privateMessages, node.outgoingPrivate) { pub, priv, out ->
            pub.map { toChatMessage(it, myPeerID, ConversationId.Mesh) } +
                priv.filter { it.senderPeerID != myPeerID }.mapNotNull { m ->
                    m.senderPeerID?.let { toChatMessage(m, myPeerID, ConversationId.Private(it)) }
                } +
                out.map { toChatMessage(it.message, myPeerID, ConversationId.Private(it.recipientPeerID)) }
        }.onEach { _messages.value = it }.launchIn(scope)

        // Session state and nicknames have no flow of their own: the node bumps a revision on
        // session established/lost and on every processed announce.
        combine(node.peers, node.peerStateRevision, handshaking) { ids, _, pending ->
            val established = pending.filterTo(mutableSetOf()) { node.hasEstablishedSession(it) }
            if (established.isNotEmpty()) handshaking.update { it - established }
            ids.map { id ->
                val session = when {
                    node.hasEstablishedSession(id) -> SessionState.ESTABLISHED
                    id in pending || node.hasPendingPrivate(id) -> SessionState.HANDSHAKING
                    else -> SessionState.NONE
                }
                PeerSummary(id, node.peerNickname(id) ?: id.take(8), session)
            }
        }.onEach { _peers.value = it }.launchIn(scope)
    }

    override fun sendPublic(text: String) = node.sendPublic(text)

    override fun sendPrivate(peerID: String, text: String) {
        val nick = node.peerNickname(peerID) ?: peerID.take(8)
        node.sendPrivate(text, peerID, nick) ?: return
        if (!node.hasEstablishedSession(peerID)) markHandshaking(peerID)
    }

    override fun startSession(peerID: String) {
        if (node.hasEstablishedSession(peerID)) return
        markHandshaking(peerID)
        node.initiateHandshake(peerID)
    }

    private fun markHandshaking(peerID: String) {
        handshaking.update { it + peerID }
        // No handshake-failed callback exists: give up showing "Handshaking" after the timeout.
        scope.launch {
            delay(handshakeTimeoutMs)
            handshaking.update { it - peerID }
        }
    }

    override fun peerFingerprint(peerID: String): String? = fingerprintLookup(peerID)

    override fun setNickname(nickname: String) {
        val before = node.identity.nickname
        node.identity.nickname = nickname
        if (node.identity.nickname != before) node.announce()
    }

    override fun nickname(): String = node.identity.nickname
}

/**
 * [ContactsPort] over the shared stores the phone uses: favorites in [FavoritesPersistenceService]
 * (keyed by Noise static public key) and verified fingerprints in [SecureIdentityStateManager].
 * Favorites are exposed by mesh peer ID, which is derived from the Noise key.
 */
class SharedContactsPort(
    private val favoritesStore: FavoritesPersistenceService,
    private val identityState: SecureIdentityStateManager,
    private val noiseKeyFor: (String) -> ByteArray?,
) : ContactsPort {
    private val _favorites = MutableStateFlow(readFavorites())
    override val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()
    private val _verified = MutableStateFlow(readVerified())
    override val verifiedFingerprints: StateFlow<Set<String>> = _verified.asStateFlow()

    init {
        favoritesStore.addListener(object : FavoritesChangeListener {
            override fun onFavoriteChanged(noiseKeyHex: String) { _favorites.value = readFavorites() }
            override fun onAllCleared() { _favorites.value = readFavorites() }
        })
    }

    override fun setFavorite(peerID: String, nickname: String, favorite: Boolean) {
        val key = noiseKeyFor(peerID) ?: favoritesStore.getFavoriteStatus(peerID)?.peerNoisePublicKey ?: return
        favoritesStore.updateFavoriteStatus(key, nickname, favorite)
        _favorites.value = readFavorites()
    }

    override fun setVerified(fingerprint: String, verified: Boolean) {
        identityState.setVerifiedFingerprint(fingerprint.lowercase(), verified)
        _verified.value = readVerified()
    }

    private fun readFavorites(): Set<String> =
        favoritesStore.getOurFavorites().mapTo(mutableSetOf()) { ContactIdentityResolver.peerIdForNoiseKey(it.peerNoisePublicKey) }

    private fun readVerified(): Set<String> = identityState.getVerifiedFingerprints().mapTo(mutableSetOf()) { it.lowercase() }
}

internal fun toChatMessage(m: BitchatMessage, myPeerID: String, conversation: ConversationId): ChatMessage =
    ChatMessage(
        id = m.id,
        conversation = conversation,
        senderPeerID = m.senderPeerID,
        senderNickname = m.sender,
        text = m.content,
        timestamp = m.timestamp,
        isMine = m.senderPeerID == myPeerID,
        delivery = m.deliveryStatus?.let(::toDeliveryState),
    )

internal fun toDeliveryState(s: DeliveryStatus): DeliveryState = when (s) {
    is DeliveryStatus.Sending -> DeliveryState.SENDING
    is DeliveryStatus.Sent -> DeliveryState.SENT
    is DeliveryStatus.Delivered -> DeliveryState.DELIVERED
    is DeliveryStatus.PartiallyDelivered -> DeliveryState.DELIVERED
    is DeliveryStatus.Read -> DeliveryState.READ
    is DeliveryStatus.Failed -> DeliveryState.FAILED
}

/** [PlatformTogglesPort] over [DesktopPlatform]. */
class DesktopPlatformToggles(private val platform: DesktopPlatform) : PlatformTogglesPort {
    override val isMac: Boolean = HostOs.current() == HostOs.MAC
    override fun startAtLogin(): Boolean = platform.autostart.isEnabled()
    override fun setStartAtLogin(enabled: Boolean) { platform.autostart.setEnabled(enabled) }
    override fun crashReports(): Boolean = platform.crashReporter.isEnabled
    override fun setCrashReports(enabled: Boolean) = platform.crashReporter.setEnabled(enabled)

    /** Saved in settings and applied at the next launch (see [applyDockPreference]). */
    override fun setShowInDock(visible: Boolean) = Unit

    /** No radio-power API on the desktop BLE helpers yet; the choice is saved for when there is. */
    override fun setBroadcastPower(power: BroadcastPower) = Unit
}

/** Hides the Dock icon on macOS. Must run before AWT starts. */
fun applyDockPreference(settings: DesktopUiSettings) {
    if (HostOs.current() == HostOs.MAC && !settings.showInDock) {
        System.setProperty("apple.awt.UIElement", "true")
    }
}

/** [SettingsPort] over the shimmed SharedPreferences (owner-only JSON file in the data dir). */
class PrefsSettingsPort(context: Context) : SettingsPort {
    private val prefs = context.getSharedPreferences("skein_desktop_ui", Context.MODE_PRIVATE)

    override fun load(): DesktopUiSettings {
        val d = DesktopUiSettings()
        return DesktopUiSettings(
            language = AppLanguage.fromCode(prefs.getString(KEY_LANGUAGE, d.language.code)),
            broadcastPower = BroadcastPower.entries.firstOrNull { it.name == prefs.getString(KEY_POWER, null) }
                ?: d.broadcastPower,
            showInDock = prefs.getBoolean(KEY_DOCK, d.showInDock),
            saveHistory = prefs.getBoolean(KEY_HISTORY, d.saveHistory),
        )
    }

    override fun save(settings: DesktopUiSettings) {
        prefs.edit()
            .putString(KEY_LANGUAGE, settings.language.code)
            .putString(KEY_POWER, settings.broadcastPower.name)
            .putBoolean(KEY_DOCK, settings.showInDock)
            .putBoolean(KEY_HISTORY, settings.saveHistory)
            .apply()
    }

    private companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_POWER = "broadcast_power"
        const val KEY_DOCK = "show_in_dock"
        const val KEY_HISTORY = "save_history"
    }
}
