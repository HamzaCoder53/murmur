package com.bitchat.desktop.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DesktopUiSettings(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val broadcastPower: BroadcastPower = BroadcastPower.MEDIUM,
    val showInDock: Boolean = true,
    val startAtLogin: Boolean = false,
    val crashReporting: Boolean = false,
    val saveHistory: Boolean = true,
)

/** What fills the main pane next to the sidebar. */
sealed class MainPane {
    data object Conversation : MainPane()
    data class PeerDetail(val peerID: String) : MainPane()
    data object Settings : MainPane()
    data object LinkedDevices : MainPane()
    data object Backups : MainPane()
}

data class ConversationSummary(
    val id: ConversationId,
    val title: String,
    val unread: Int,
    val lastMessage: ChatMessage?,
    val isFavorite: Boolean = false,
    val mentionsMe: Boolean = false,
)

data class PersonRow(
    val peerID: String,
    val nickname: String,
    val isFavorite: Boolean,
    val isVerified: Boolean,
    val session: SessionState,
    val unread: Int,
)

data class MessageRow(
    val message: ChatMessage,
    val mentionsMe: Boolean,
    val mentions: List<String>,
)

data class PeerDetailState(
    val peerID: String,
    val nickname: String,
    val isFavorite: Boolean,
    val isVerified: Boolean,
    val session: SessionState,
    val connected: Boolean,
    val myFingerprint: String,
    val theirFingerprint: String?,
)

data class SkeinUiState(
    val settings: DesktopUiSettings = DesktopUiSettings(),
    val nickname: String = "",
    val myPeerID: String = "",
    val selected: ConversationId = ConversationId.Mesh,
    val pane: MainPane = MainPane.Conversation,
    val mesh: ConversationSummary = ConversationSummary(ConversationId.Mesh, "#mesh", 0, null),
    val locationChannels: List<ConversationSummary> = emptyList(),
    val privateChats: List<ConversationSummary> = emptyList(),
    val people: List<PersonRow> = emptyList(),
    val messages: List<MessageRow> = emptyList(),
    val selectedTitle: String = "#mesh",
    val selectedSession: SessionState? = null,
    val peerDetail: PeerDetailState? = null,
    val draft: String = "",
    val mentionSuggestions: List<String> = emptyList(),
) {
    val strings: UiStrings get() = stringsFor(settings.language)
    val totalUnread: Int get() = mesh.unread + locationChannels.sumOf { it.unread } + privateChats.sumOf { it.unread }
}

private data class LocalState(
    val settings: DesktopUiSettings,
    val nickname: String,
    val selected: ConversationId = ConversationId.Mesh,
    val pane: MainPane = MainPane.Conversation,
    val readIds: Set<String> = emptySet(),
    val draft: String = "",
    val history: List<ChatMessage> = emptyList(),
    /** Bumped when something outside the flows (fingerprints, sessions) may have changed. */
    val refresh: Int = 0,
)

/**
 * State holder for the desktop messenger. Pure Kotlin (no Compose): all logic here is unit tested.
 * Everything external is reached through [SkeinPorts].
 */
class SkeinViewModel(
    private val ports: SkeinPorts,
    private val scope: CoroutineScope,
) {
    private val messaging = ports.messaging

    private val local = MutableStateFlow(
        run {
            val stored = ports.settings.load()
            val settings = stored.copy(
                startAtLogin = ports.platform.startAtLogin(),
                crashReporting = ports.platform.crashReports(),
            )
            val history = if (settings.saveHistory) ports.history.load() else emptyList()
            LocalState(
                settings = settings,
                nickname = messaging.nickname(),
                history = history,
                // Messages restored from disk were already seen in an earlier run.
                readIds = history.mapTo(mutableSetOf()) { it.id },
            )
        },
    )

    private val liveMessages = combine(messaging.messages, ports.locations.messages) { mesh, loc -> mesh + loc }
    private val contacts = combine(ports.contacts.favorites, ports.contacts.verifiedFingerprints) { f, v -> f to v }

    val state: StateFlow<SkeinUiState> = combine(
        messaging.peers,
        liveMessages,
        contacts,
        ports.locations.channels,
        local,
    ) { peers, live, (favorites, verified), channels, l ->
        buildState(peers, live, favorites, verified, channels, l)
    }.stateIn(scope, SharingStarted.Eagerly, SkeinUiState())

    /** IDs already written to [HistoryPort]. */
    private val savedIds = mutableSetOf<String>().apply { addAll(local.value.history.map { it.id }) }

    init {
        scope.launch {
            liveMessages.collect { all ->
                val l = local.value
                // Messages arriving in the open conversation count as read.
                val visible = all.filter { it.conversation == l.selected && l.pane == MainPane.Conversation }
                if (visible.any { it.id !in l.readIds }) {
                    local.update { s -> s.copy(readIds = s.readIds + visible.map { it.id }) }
                }
                if (local.value.settings.saveHistory) saveNew(all)
            }
        }
    }

    // ---- navigation -------------------------------------------------------------------------

    fun selectConversation(id: ConversationId) {
        val ids = allMessages().filter { it.conversation == id }.map { it.id }
        local.update { it.copy(selected = id, pane = MainPane.Conversation, readIds = it.readIds + ids) }
    }

    fun openPeer(peerID: String) = local.update { it.copy(pane = MainPane.PeerDetail(peerID)) }

    fun openPane(pane: MainPane) {
        if (pane == MainPane.Conversation) selectConversation(local.value.selected) else local.update { it.copy(pane = pane) }
    }

    fun closePane() = selectConversation(local.value.selected)

    fun refresh() = local.update { it.copy(refresh = it.refresh + 1) }

    // ---- composer ---------------------------------------------------------------------------

    fun updateDraft(text: String) = local.update { it.copy(draft = text) }

    fun pickMention(nickname: String) = local.update { it.copy(draft = applyMention(it.draft, nickname)) }

    /** Sends the draft to the selected conversation. Returns false when nothing was sent. */
    fun send(): Boolean {
        val text = local.value.draft.trim()
        if (text.isEmpty()) return false
        when (val c = local.value.selected) {
            ConversationId.Mesh -> messaging.sendPublic(text)
            is ConversationId.Location -> ports.locations.send(c.geohash, text)
            is ConversationId.Private -> messaging.sendPrivate(c.peerID, text)
        }
        local.update { it.copy(draft = "") }
        return true
    }

    // ---- people -----------------------------------------------------------------------------

    fun toggleFavorite(peerID: String) {
        val fav = peerID in ports.contacts.favorites.value
        ports.contacts.setFavorite(peerID, nicknameFor(peerID), !fav)
    }

    fun setVerified(peerID: String, verified: Boolean) {
        val fp = messaging.peerFingerprint(peerID) ?: return
        ports.contacts.setVerified(fp, verified)
    }

    fun startSession(peerID: String) {
        messaging.startSession(peerID)
        refresh()
    }

    fun messagePeer(peerID: String) = selectConversation(ConversationId.Private(peerID))

    // ---- location channels ------------------------------------------------------------------

    fun joinLocation(input: String): Boolean {
        val geohash = normalizeGeohash(input) ?: return false
        ports.locations.join(geohash)
        selectConversation(ConversationId.Location(geohash))
        return true
    }

    fun leaveLocation(geohash: String) {
        ports.locations.leave(geohash)
        if (local.value.selected == ConversationId.Location(geohash)) selectConversation(ConversationId.Mesh)
    }

    // ---- settings ---------------------------------------------------------------------------

    fun setNickname(input: String): Boolean {
        val nick = validNickname(input) ?: return false
        messaging.setNickname(nick)
        local.update { it.copy(nickname = messaging.nickname()) }
        return true
    }

    fun setLanguage(language: AppLanguage) = updateSettings { it.copy(language = language) }

    fun setBroadcastPower(power: BroadcastPower) {
        ports.platform.setBroadcastPower(power)
        updateSettings { it.copy(broadcastPower = power) }
    }

    fun setShowInDock(visible: Boolean) {
        ports.platform.setShowInDock(visible)
        updateSettings { it.copy(showInDock = visible) }
    }

    fun setStartAtLogin(enabled: Boolean) {
        ports.platform.setStartAtLogin(enabled)
        updateSettings { it.copy(startAtLogin = ports.platform.startAtLogin()) }
    }

    fun setCrashReports(enabled: Boolean) {
        ports.platform.setCrashReports(enabled)
        updateSettings { it.copy(crashReporting = ports.platform.crashReports()) }
    }

    fun setSaveHistory(enabled: Boolean) {
        if (!enabled) {
            ports.history.clear()
            savedIds.clear()
            local.update { it.copy(history = emptyList()) }
        } else {
            // What is already on screen is saved too.
            saveNew(allMessages())
        }
        updateSettings { it.copy(saveHistory = enabled) }
    }

    private fun saveNew(messages: List<ChatMessage>) {
        messages.filter { savedIds.add(it.id) }.forEach { ports.history.append(it) }
    }

    private fun updateSettings(change: (DesktopUiSettings) -> DesktopUiSettings) {
        local.update { it.copy(settings = change(it.settings)) }
        ports.settings.save(local.value.settings)
    }

    // ---- derivation -------------------------------------------------------------------------

    private fun allMessages(): List<ChatMessage> =
        mergeHistory(local.value.history, messaging.messages.value + ports.locations.messages.value)

    private fun nicknameFor(peerID: String): String =
        messaging.peers.value.firstOrNull { it.peerID == peerID }?.nickname
            ?: allMessages().lastOrNull { it.senderPeerID == peerID && !it.isMine }?.senderNickname
            ?: peerID.take(8)

    private fun buildState(
        peers: List<PeerSummary>,
        live: List<ChatMessage>,
        favorites: Set<String>,
        verified: Set<String>,
        channels: List<LocationChannelInfo>,
        l: LocalState,
    ): SkeinUiState {
        val strings = stringsFor(l.settings.language)
        val all = mergeHistory(l.history, live)
        val byConversation = all.groupBy { it.conversation }
        val peerById = peers.associateBy { it.peerID }

        fun unread(id: ConversationId): Int {
            if (id == l.selected && l.pane == MainPane.Conversation) return 0
            return byConversation[id].orEmpty().count { !it.isMine && it.id !in l.readIds }
        }

        fun unreadMention(id: ConversationId): Boolean {
            if (id == l.selected && l.pane == MainPane.Conversation) return false
            return byConversation[id].orEmpty().any {
                !it.isMine && it.id !in l.readIds && mentionsNickname(it.text, l.nickname)
            }
        }

        fun peerName(peerID: String): String = peerById[peerID]?.nickname
            ?: all.lastOrNull { it.senderPeerID == peerID && !it.isMine }?.senderNickname
            ?: peerID.take(8)

        fun summary(id: ConversationId, title: String) = ConversationSummary(
            id = id,
            title = title,
            unread = unread(id),
            lastMessage = byConversation[id]?.lastOrNull(),
            isFavorite = id is ConversationId.Private && id.peerID in favorites,
            mentionsMe = unreadMention(id),
        )

        val locationIds = (channels.map { it.geohash } +
            byConversation.keys.filterIsInstance<ConversationId.Location>().map { it.geohash } +
            listOfNotNull((l.selected as? ConversationId.Location)?.geohash)).distinct()
        val locationSummaries = locationIds.map { summary(ConversationId.Location(it), "#$it") }

        val privateIds = (byConversation.keys.filterIsInstance<ConversationId.Private>() +
            listOfNotNull(l.selected as? ConversationId.Private)).distinct()
        val privateSummaries = privateIds
            .map { summary(it, peerName(it.peerID)) }
            .sortedWith(
                compareByDescending<ConversationSummary> { it.isFavorite }
                    .thenByDescending { it.lastMessage?.timestamp?.time ?: 0L },
            )

        val people = peers.map { p ->
            val fp = messaging.peerFingerprint(p.peerID)
            PersonRow(
                peerID = p.peerID,
                nickname = p.nickname,
                isFavorite = p.peerID in favorites,
                isVerified = fp != null && fp.lowercase() in verified,
                session = p.session,
                unread = unread(ConversationId.Private(p.peerID)),
            )
        }.sortedWith(compareByDescending<PersonRow> { it.isFavorite }.thenBy { it.nickname.lowercase() })

        val knownNicks = (peers.map { it.nickname } + l.nickname).toSet()
        val rows = byConversation[l.selected].orEmpty().map {
            MessageRow(it, !it.isMine && mentionsNickname(it.text, l.nickname), parseMentions(it.text, knownNicks))
        }

        val detail = (l.pane as? MainPane.PeerDetail)?.let { pane ->
            val fp = messaging.peerFingerprint(pane.peerID)
            PeerDetailState(
                peerID = pane.peerID,
                nickname = peerName(pane.peerID),
                isFavorite = pane.peerID in favorites,
                isVerified = fp != null && fp.lowercase() in verified,
                session = peerById[pane.peerID]?.session ?: SessionState.NONE,
                connected = pane.peerID in peerById,
                myFingerprint = messaging.myFingerprint,
                theirFingerprint = fp,
            )
        }

        val selectedTitle = when (val s = l.selected) {
            ConversationId.Mesh -> strings.meshChannel
            is ConversationId.Location -> "#${s.geohash}"
            is ConversationId.Private -> peerName(s.peerID)
        }

        return SkeinUiState(
            settings = l.settings,
            nickname = l.nickname,
            myPeerID = messaging.myPeerID,
            selected = l.selected,
            pane = l.pane,
            mesh = summary(ConversationId.Mesh, strings.meshChannel),
            locationChannels = locationSummaries,
            privateChats = privateSummaries,
            people = people,
            messages = rows,
            selectedTitle = selectedTitle,
            selectedSession = (l.selected as? ConversationId.Private)?.let { peerById[it.peerID]?.session ?: SessionState.NONE },
            peerDetail = detail,
            draft = l.draft,
            mentionSuggestions = mentionSuggestions(l.draft, peers.map { it.nickname }),
        )
    }
}

/** Live messages win over their saved copies (newer delivery state); deduplicated, ordered by time. */
internal fun mergeHistory(history: List<ChatMessage>, live: List<ChatMessage>): List<ChatMessage> {
    if (history.isEmpty()) return live.sortedBy { it.timestamp.time }
    val seen = HashSet<String>()
    return (live + history).filter { seen.add(it.id) }.sortedBy { it.timestamp.time }
}
