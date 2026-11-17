package com.bitchat.desktop.ui

import android.content.Context
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.desktop.core.DesktopMeshNode
import com.bitchat.desktop.platform.AutostartController
import com.bitchat.desktop.platform.DesktopPlatform
import com.bitchat.desktop.platform.HostOs
import java.util.Date
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
 * [MessagingPort] over the real [DesktopMeshNode]. The node keeps private messages per
 * conversation (incoming and outgoing, with delivery status), so the window shows exactly what
 * linked devices see through [com.bitchat.desktop.link.DesktopLinkedAppBridge].
 */
class MeshMessagingAdapter(
    private val node: DesktopMeshNode,
    private val scope: CoroutineScope,
    private val fingerprintLookup: (String) -> String? = node::peerFingerprint,
    private val handshakeTimeoutMs: Long = DesktopMeshNode.PENDING_PRIVATE_TIMEOUT_MS,
) : MessagingPort {
    override val myPeerID: String get() = node.peerID
    override val myFingerprint: String get() = node.identity.fingerprint

    private val handshaking = MutableStateFlow<Set<String>>(emptySet())

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerSummary>>(emptyList())
    override val peers: StateFlow<List<PeerSummary>> = _peers.asStateFlow()

    init {
        combine(node.publicMessages, node.privateChats) { pub, chats ->
            pub.map { toChatMessage(it, myPeerID, ConversationId.Mesh) } +
                chats.flatMap { (peerID, list) -> list.map { toChatMessage(it, myPeerID, ConversationId.Private(peerID)) } }
        }.onEach { _messages.value = it }.launchIn(scope)

        // Session state and nicknames have no flow of their own: the node bumps sessionVersion on
        // session established/lost, on a queued message timing out and on every processed announce.
        combine(node.peers, node.sessionVersion, handshaking) { ids, _, pending ->
            val done = pending.filterTo(mutableSetOf()) { node.hasEstablishedSession(it) }
            if (done.isNotEmpty()) handshaking.update { it - done }
            ids.map { id ->
                val established = node.hasEstablishedSession(id)
                val session = when {
                    established -> SessionState.ESTABLISHED
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
        node.sendPrivate(text, peerID, nick)
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
    override fun setNickname(nickname: String) = node.rename(nickname)
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

/**
 * Saved mesh/private history back to the node's model, for seeding [DesktopMeshNode.restore].
 * Returns (conversation peer ID or null for #mesh, message); location messages are not mesh state.
 */
internal fun toBitchatMessage(m: ChatMessage): Pair<String?, BitchatMessage>? {
    val conv: String? = when (val c = m.conversation) {
        ConversationId.Mesh -> null
        is ConversationId.Private -> c.peerID
        is ConversationId.Location -> return null
    }
    val msg = BitchatMessage(
        id = m.id,
        sender = m.senderNickname,
        content = m.text,
        timestamp = m.timestamp,
        isPrivate = conv != null,
        senderPeerID = m.senderPeerID,
        deliveryStatus = m.delivery?.let(::fromDeliveryState),
    )
    return conv to msg
}

internal fun fromDeliveryState(s: DeliveryState): DeliveryStatus = when (s) {
    DeliveryState.SENDING -> DeliveryStatus.Sending
    DeliveryState.SENT -> DeliveryStatus.Sent
    DeliveryState.DELIVERED -> DeliveryStatus.Delivered("", Date(0))
    DeliveryState.READ -> DeliveryStatus.Read("", Date(0))
    DeliveryState.FAILED -> DeliveryStatus.Failed("")
}

internal fun toDeliveryState(s: DeliveryStatus): DeliveryState = when (s) {
    is DeliveryStatus.Sending -> DeliveryState.SENDING
    is DeliveryStatus.Sent -> DeliveryState.SENT
    is DeliveryStatus.Delivered -> DeliveryState.DELIVERED
    is DeliveryStatus.PartiallyDelivered -> DeliveryState.DELIVERED
    is DeliveryStatus.Read -> DeliveryState.READ
    is DeliveryStatus.Failed -> DeliveryState.FAILED
}

/** Sets start-at-login and returns what the OS now reports, so the switch never lies. */
internal fun applyAutostart(controller: AutostartController, enabled: Boolean): Boolean {
    if (!controller.isSupported) return false
    controller.setEnabled(enabled)
    return controller.isEnabled()
}

/** [PlatformTogglesPort] over [DesktopPlatform]. */
class DesktopPlatformToggles(private val platform: DesktopPlatform) : PlatformTogglesPort {
    override val isMac: Boolean = HostOs.current() == HostOs.MAC
    override val startAtLoginSupported: Boolean get() = platform.autostart.isSupported
    override val crashReportsAvailable: Boolean get() = platform.crashReporter.isAvailable
    override fun crashReportsDecided(): Boolean = platform.crashReporter.hasOptInDecision()
    override fun startAtLogin(): Boolean = platform.autostart.isEnabled()
    override fun setStartAtLogin(enabled: Boolean) { applyAutostart(platform.autostart, enabled) }
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
            firstRunPromptsShown = prefs.getBoolean(KEY_FIRST_RUN, d.firstRunPromptsShown),
        )
    }

    override fun save(settings: DesktopUiSettings) {
        prefs.edit()
            .putString(KEY_LANGUAGE, settings.language.code)
            .putString(KEY_POWER, settings.broadcastPower.name)
            .putBoolean(KEY_DOCK, settings.showInDock)
            .putBoolean(KEY_HISTORY, settings.saveHistory)
            .putBoolean(KEY_FIRST_RUN, settings.firstRunPromptsShown)
            .apply()
    }

    private companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_POWER = "broadcast_power"
        const val KEY_DOCK = "show_in_dock"
        const val KEY_HISTORY = "save_history"
        const val KEY_FIRST_RUN = "first_run_prompts_shown"
    }
}
