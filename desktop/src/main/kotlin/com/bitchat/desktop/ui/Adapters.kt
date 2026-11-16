package com.bitchat.desktop.ui

import android.content.Context
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.desktop.core.DesktopMeshNode
import com.bitchat.desktop.platform.DesktopPlatform
import com.bitchat.desktop.platform.HostOs
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * [MessagingPort] over the real [DesktopMeshNode]. The node does not report the recipient of a
 * private message it sends, so outgoing private messages are tracked here and the node's own
 * local echoes are dropped.
 */
class MeshMessagingAdapter(
    private val node: DesktopMeshNode,
    scope: CoroutineScope,
    private val fingerprintLookup: (String) -> String? = { PeerFingerprintManager.getInstance().getFingerprintForPeer(it) },
) : MessagingPort {
    override val myPeerID: String get() = node.peerID
    override val myFingerprint: String get() = node.identity.fingerprint

    private val outgoingPrivate = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val handshaking = MutableStateFlow<Set<String>>(emptySet())

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerSummary>>(emptyList())
    override val peers: StateFlow<List<PeerSummary>> = _peers.asStateFlow()

    init {
        combine(node.publicMessages, node.privateMessages, outgoingPrivate) { pub, priv, out ->
            pub.map { toChatMessage(it, myPeerID, ConversationId.Mesh) } +
                priv.filter { it.senderPeerID != myPeerID }.mapNotNull { m ->
                    m.senderPeerID?.let { toChatMessage(m, myPeerID, ConversationId.Private(it)) }
                } + out
        }.onEach { _messages.value = it }.launchIn(scope)

        // Session state has no flow of its own; re-read it whenever peers or messages change.
        combine(node.peers, node.privateMessages, handshaking) { ids, _, pending ->
            ids.map { id ->
                val session = when {
                    node.hasEstablishedSession(id) -> SessionState.ESTABLISHED
                    id in pending -> SessionState.HANDSHAKING
                    else -> SessionState.NONE
                }
                PeerSummary(id, node.peerNickname(id) ?: id.take(8), session)
            }
        }.onEach { _peers.value = it }.launchIn(scope)
    }

    override fun sendPublic(text: String) = node.sendPublic(text)

    override fun sendPrivate(peerID: String, text: String) {
        val nick = node.peerNickname(peerID) ?: peerID.take(8)
        node.sendPrivate(text, peerID, nick)
        outgoingPrivate.update {
            it + ChatMessage(
                id = UUID.randomUUID().toString(),
                conversation = ConversationId.Private(peerID),
                senderPeerID = myPeerID,
                senderNickname = node.identity.nickname,
                text = text,
                timestamp = Date(),
                isMine = true,
                delivery = DeliveryState.SENT,
            )
        }
    }

    override fun startSession(peerID: String) {
        if (node.hasEstablishedSession(peerID)) return
        handshaking.update { it + peerID }
        node.initiateHandshake(peerID)
    }

    override fun peerFingerprint(peerID: String): String? = fingerprintLookup(peerID)
    override fun setNickname(nickname: String) { node.identity.nickname = nickname }
    override fun nickname(): String = node.identity.nickname
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
