package com.bitchat.desktop.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bitchat.android.backup.BackupConversation
import com.bitchat.android.backup.BackupTarget
import com.bitchat.android.backup.ImportMode
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.MessageHistory
import com.bitchat.android.wearlink.IdentityBundle
import com.bitchat.desktop.ble.BleMeshTransport
import com.bitchat.desktop.core.DesktopIdentity
import com.bitchat.desktop.core.DesktopMeshNode
import com.bitchat.desktop.core.EncryptedConversationStore
import com.bitchat.desktop.core.LoopbackHub
import com.bitchat.desktop.link.DesktopBackupService
import com.bitchat.desktop.platform.AutostartController
import com.bitchat.desktop.platform.BleState
import com.bitchat.desktop.platform.DesktopPlatform
import com.bitchat.desktop.platform.HostOs
import com.bitchat.desktop.platform.NoopBleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files

enum class SkeinTab { CHAT, DMS, PEOPLE, HISTORY, BACKUPS, SETTINGS }

data class DesktopUiSettings(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val showInDock: Boolean = true,
    val startAtLogin: Boolean = false,
    val crashReporting: Boolean = false,
    val saveHistory: Boolean = MessageHistory.DEFAULT_ENABLED,
)

/** Everything the screens need, wired once in [runDesktopApp]. */
class DesktopAppState(
    val identity: DesktopIdentity,
    val node: DesktopMeshNode,
    val platform: DesktopPlatform,
    val settingsStore: DesktopSettingsStore,
    val alerts: PrivateMessageAlerts,
    val bleState: StateFlow<BleState>,
    val scope: CoroutineScope,
)

fun runDesktopApp(platform: DesktopPlatform, startHidden: Boolean = false) {
    val ctx = Context(platform.paths.dataDir)
    val identity = DesktopIdentity.loadOrCreate(ctx)
    MessageHistory.initialize(ctx)
    val conversationStore = EncryptedConversationStore(ctx) { MessageHistory.isEnabled(ctx) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var bleTransport: BleMeshTransport? = null
    var loopbackTransport: com.bitchat.desktop.core.LoopbackTransport? = null
    val bleState = MutableStateFlow(platform.ble.state)
    val node: DesktopMeshNode = if (platform.ble !== NoopBleTransport) {
        lateinit var createdNode: DesktopMeshNode
        val bleMesh = BleMeshTransport(
            ble = platform.ble,
            scope = appScope,
            onPacketReceived = { pkt, senderPeerID, linkId ->
                createdNode.receive(pkt, senderPeerID, linkId)
            },
            onStateChanged = { bleState.value = it },
            onPeerUnreachable = { peer -> createdNode.onPeerLinkLost(peer) },
        )
        createdNode = DesktopMeshNode(identity, bleMesh, conversationStore)
        bleMesh.attachFragmentManager(createdNode.fragmentManager)
        bleTransport = bleMesh
        bleMesh.start()
        createdNode
    } else {
        // No BLE helper (or no BLE on this OS): the node runs with nobody to talk to, and the UI
        // says so instead of silently showing zero peers.
        bleState.value = BleState.UNSUPPORTED
        val (n, lt) = DesktopMeshNode.overLoopback(identity, LoopbackHub(), conversationStore)
        loopbackTransport = lt
        n
    }
    node.start()

    val alerts = PrivateMessageAlerts(platform.notifications, platform.tray)
    appScope.launch {
        node.incomingPrivate.collect { m ->
            alerts.onIncoming(m, m.senderPeerID?.let(node::peerNickname) ?: m.sender)
        }
    }
    val state = DesktopAppState(
        identity = identity,
        node = node,
        platform = platform,
        settingsStore = DesktopSettingsStore(platform.paths.dataDir),
        alerts = alerts,
        bleState = bleState,
        scope = appScope,
    )

    application {
        var windowVisible by remember { mutableStateOf(!startHidden) }
        val shutdown = {
            platform.tray.hide()
            node.stop()
            bleTransport?.stop()
            loopbackTransport?.close()
            appScope.cancel()
            exitApplication()
        }
        LaunchedEffect(platform.tray) {
            platform.tray.show(
                tooltip = "Skein",
                onOpen = { windowVisible = true },
                onQuit = shutdown,
            )
        }
        LaunchedEffect(windowVisible) {
            if (!windowVisible) alerts.setAppInFront(false)
        }
        Window(
            onCloseRequest = {
                if (platform.tray.isSupported) windowVisible = false else shutdown()
            },
            visible = windowVisible,
            title = "Skein",
            state = rememberWindowState(width = 860.dp, height = 640.dp),
        ) {
            val focused = LocalWindowInfo.current.isWindowFocused
            LaunchedEffect(focused, windowVisible) {
                alerts.setAppInFront(windowVisible && focused)
            }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    SkeinDesktopRoot(state)
                }
            }
        }
    }
}

/** Sets start-at-login and returns what the OS now reports, so the switch never lies. */
internal fun applyAutostart(controller: AutostartController, enabled: Boolean): Boolean {
    if (!controller.isSupported) return false
    controller.setEnabled(enabled)
    return controller.isEnabled()
}

/** People to show in the DM list: everyone reachable now plus everyone we have a chat with. */
internal fun dmPeers(livePeers: List<String>, conversations: Map<String, List<BitchatMessage>>): List<String> =
    (livePeers + conversations.keys).distinct()

/** The conversation with [selectedPeer], both directions, or nothing when no one is selected. */
internal fun conversationWith(
    conversations: Map<String, List<BitchatMessage>>,
    selectedPeer: String?,
): List<BitchatMessage> = selectedPeer?.let { conversations[it] }.orEmpty()

@Composable
fun SkeinDesktopRoot(state: DesktopAppState) {
    val platform = state.platform
    val saved = remember { state.settingsStore.load() }
    var activeTab by remember { mutableStateOf(SkeinTab.CHAT) }
    var settings by remember {
        mutableStateOf(
            DesktopUiSettings(
                language = saved.language,
                showInDock = saved.showInDock,
                startAtLogin = platform.autostart.isEnabled(),
                crashReporting = platform.crashReporter.isEnabled,
                saveHistory = MessageHistory.enabled.value,
            ),
        )
    }
    var askAutostart by remember {
        mutableStateOf(platform.autostart.isSupported && !saved.autostartPrompted && !settings.startAtLogin)
    }
    var askCrashReports by remember {
        mutableStateOf(platform.crashReporter.isAvailable && !platform.crashReporter.hasOptInDecision())
    }
    val strings = stringsFor(settings.language)

    LaunchedEffect(activeTab) {
        state.alerts.setViewingDms(activeTab == SkeinTab.DMS)
    }

    if (askAutostart) {
        val answer = { yes: Boolean ->
            state.settingsStore.setAutostartPrompted()
            if (yes) settings = settings.copy(startAtLogin = applyAutostart(platform.autostart, true))
            askAutostart = false
        }
        AlertDialog(
            onDismissRequest = { answer(false) },
            title = { Text(strings.autostartPromptTitle) },
            text = { Text(strings.autostartPromptBody) },
            confirmButton = { TextButton(onClick = { answer(true) }) { Text(strings.yes) } },
            dismissButton = { TextButton(onClick = { answer(false) }) { Text(strings.notNow) } },
        )
    } else if (askCrashReports) {
        val answer = { yes: Boolean ->
            platform.crashReporter.setEnabled(yes)
            settings = settings.copy(crashReporting = platform.crashReporter.isEnabled)
            askCrashReports = false
        }
        AlertDialog(
            onDismissRequest = { answer(false) },
            title = { Text(strings.crashPromptTitle) },
            text = { Text(strings.crashPromptBody) },
            confirmButton = { TextButton(onClick = { answer(true) }) { Text(strings.yes) } },
            dismissButton = { TextButton(onClick = { answer(false) }) { Text(strings.notNow) } },
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides settings.language.layoutDirection) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.fillMaxHeight().width(116.dp)) {
                SkeinTab.entries.forEach { tab ->
                    val label = when (tab) {
                        SkeinTab.CHAT -> strings.tabChat
                        SkeinTab.DMS -> strings.tabDms
                        SkeinTab.PEOPLE -> strings.tabPeople
                        SkeinTab.HISTORY -> strings.tabHistory
                        SkeinTab.BACKUPS -> strings.tabBackups
                        SkeinTab.SETTINGS -> strings.tabSettings
                    }
                    NavigationRailItem(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        icon = { Text(label.take(2).uppercase()) },
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                BluetoothBanner(state.bleState, strings)
                when (activeTab) {
                    SkeinTab.CHAT -> PublicChatScreen(state.node, strings)
                    SkeinTab.DMS -> DirectMessagesScreen(state.node, strings)
                    SkeinTab.PEOPLE -> PeopleScreen(state.node, strings)
                    SkeinTab.HISTORY -> HistoryScreen(state.node, strings)
                    SkeinTab.BACKUPS -> BackupsScreen(state, strings)
                    SkeinTab.SETTINGS -> SettingsScreen(state, settings, strings) { updated ->
                        settings = updated
                    }
                }
            }
        }
    }
}

@Composable
private fun BluetoothBanner(bleState: StateFlow<BleState>, strings: UiStrings) {
    val ble by bleState.collectAsState()
    val text = when (ble) {
        BleState.READY -> null
        BleState.POWERED_OFF -> strings.bluetoothOff
        BleState.UNAUTHORIZED -> strings.bluetoothUnauthorized
        BleState.UNSUPPORTED -> strings.bluetoothUnavailable
        BleState.HELPER_CRASHED -> strings.bluetoothHelperCrashed
    } ?: return
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun PublicChatScreen(node: DesktopMeshNode, strings: UiStrings) {
    val messages by node.publicMessages.collectAsState()
    val peers by node.peers.collectAsState()
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${strings.appTitle} — ${strings.tabChat}", style = MaterialTheme.typography.headlineSmall)
        Text("${strings.peersNearby}: ${peers.size}", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(messages, key = { it.id }) { m ->
                Text("<${m.sender}> ${m.content}")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text(strings.messageHint) })
            Button(onClick = {
                if (draft.isNotBlank()) node.sendPublic(draft.trim())
                draft = ""
            }) { Text(strings.send) }
        }
    }
}

@Composable
private fun DirectMessagesScreen(node: DesktopMeshNode, strings: UiStrings) {
    val peers by node.peers.collectAsState()
    val conversations by node.privateConversations.collectAsState()
    var selectedPeer by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.tabDms, style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dmPeers(peers, conversations).forEach { p ->
                FilterChip(
                    selected = selectedPeer == p,
                    onClick = { selectedPeer = p },
                    label = { Text(node.peerNickname(p) ?: p.take(8)) },
                )
            }
        }
        val shown = conversationWith(conversations, selectedPeer)
        if (selectedPeer == null) {
            Text(strings.selectConversation, style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(shown, key = { it.id }) { m ->
                val status = when (m.deliveryStatus) {
                    is DeliveryStatus.Read -> " (${strings.read})"
                    is DeliveryStatus.Delivered -> " (${strings.delivered})"
                    else -> ""
                }
                Text("<${m.sender}> ${m.content}$status")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text(strings.messageHint) })
            Button(
                enabled = selectedPeer != null && draft.isNotBlank(),
                onClick = {
                    val target = selectedPeer ?: return@Button
                    node.sendPrivate(draft.trim(), target, node.peerNickname(target) ?: target.take(8))
                    draft = ""
                },
            ) { Text(strings.send) }
        }
    }
}

@Composable
private fun PeopleScreen(node: DesktopMeshNode, strings: UiStrings) {
    val peers by node.peers.collectAsState()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${strings.tabPeople} (${peers.size})", style = MaterialTheme.typography.headlineSmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(peers) { peerId ->
                val nick = node.peerNickname(peerId) ?: peerId.take(8)
                val session = if (node.hasEstablishedSession(peerId)) "Noise OK" else "Unverified"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$nick ($peerId)", fontFamily = FontFamily.Monospace)
                    Text(session, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(node: DesktopMeshNode, strings: UiStrings) {
    val pub by node.publicMessages.collectAsState()
    val priv by node.privateMessages.collectAsState()
    var query by remember { mutableStateOf("") }
    val all = (pub + priv).filter { query.isBlank() || it.content.contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.tabHistory, style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search history") })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(all, key = { it.id }) { m -> Text("<${m.sender}> ${m.content}") }
        }
    }
}

/** Imports a backup's private chats into the node (merge only; identity restore is phone-only). */
internal class NodeBackupTarget(private val node: DesktopMeshNode, private val mediaRoot: File) : BackupTarget {
    override suspend fun existingMessageIDs(): Set<String> = node.knownPrivateMessageIds()
    override suspend fun insert(conversation: BackupConversation, message: BitchatMessage, isRead: Boolean): Boolean =
        node.importPrivate(conversation.id, listOf(message)) > 0
    override suspend fun eraseChats(): Boolean {
        node.clearPrivate()
        return true
    }
    override suspend fun replaceIdentity(identity: IdentityBundle.Contents): Boolean = false
    override fun mediaDirectory(type: BitchatMessageType): File =
        File(mediaRoot, type.name.lowercase()).apply { mkdirs() }
    override suspend fun reloadChats() = Unit
}

internal fun backupConversations(node: DesktopMeshNode): List<BackupConversation> =
    node.privateConversations.value.map { (peer, messages) ->
        BackupConversation(
            id = peer,
            displayName = node.peerNickname(peer),
            messages = messages,
            readMessageIDs = messages.mapTo(HashSet()) { it.id },
        )
    }

private fun pickFile(title: String, mode: Int, defaultName: String? = null): File? {
    val dialog = FileDialog(null as Frame?, title, mode)
    if (defaultName != null) dialog.file = defaultName
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

@Composable
private fun BackupsScreen(state: DesktopAppState, strings: UiStrings) {
    var passphrase by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings.tabBackups, style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            passphrase,
            { passphrase = it },
            label = { Text(strings.passphrase) },
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(enabled = !busy && passphrase.length >= 8, onClick = {
            val out = pickFile(strings.exportBackup, FileDialog.SAVE, "skein-backup.skein") ?: return@Button
            busy = true
            uiScope.launch {
                status = try {
                    withContext(Dispatchers.IO) {
                        DesktopBackupService.exportArchive(out, passphrase, backupConversations(state.node))
                    }
                    "Saved ${out.name}"
                } catch (e: Exception) {
                    "Export failed: ${e.javaClass.simpleName}"
                }
                busy = false
            }
        }) { Text(strings.exportBackup) }
        OutlinedButton(enabled = !busy && passphrase.isNotEmpty(), onClick = {
            val inFile = pickFile(strings.importBackup, FileDialog.LOAD) ?: return@OutlinedButton
            busy = true
            uiScope.launch {
                status = try {
                    val res = withContext(Dispatchers.IO) {
                        val staging = Files.createTempDirectory(state.identity.context.cacheDir.toPath(), "import").toFile()
                        DesktopBackupService.importArchive(
                            inFile,
                            passphrase,
                            ImportMode.MERGE,
                            NodeBackupTarget(state.node, File(state.identity.context.filesDir, "media")),
                            staging,
                        )
                    }
                    "Imported ${res.added} messages (${res.skipped} already here, ${res.failed} failed)"
                } catch (e: Exception) {
                    "Import failed: wrong passphrase or damaged file"
                }
                busy = false
            }
        }) { Text(strings.importBackup) }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SettingsScreen(
    state: DesktopAppState,
    settings: DesktopUiSettings,
    strings: UiStrings,
    onUpdate: (DesktopUiSettings) -> Unit,
) {
    val identity = state.identity
    val platform = state.platform
    var nick by remember(identity.nickname) { mutableStateOf(identity.nickname) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings.tabSettings, style = MaterialTheme.typography.headlineSmall)
        SelectionContainer {
            Text("${strings.peerId}: ${identity.peerID}", fontFamily = FontFamily.Monospace)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(nick, { nick = it }, label = { Text(strings.nickname) })
            Button(onClick = { if (nick.isNotBlank()) identity.nickname = nick.trim() }) { Text("Save") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.language)
            AppLanguage.entries.forEach { lang ->
                FilterChip(
                    selected = settings.language == lang,
                    onClick = {
                        state.settingsStore.setLanguage(lang)
                        onUpdate(settings.copy(language = lang))
                    },
                    label = { Text(lang.label) },
                )
            }
        }
        if (HostOs.current() == HostOs.MAC) {
            SettingSwitch(strings.showInDock, strings.appliesAfterRestart, settings.showInDock, enabled = true) {
                state.settingsStore.setShowInDock(it)
                onUpdate(settings.copy(showInDock = it))
            }
        }
        SettingSwitch(
            strings.startAtLogin,
            if (platform.autostart.isSupported) null else strings.notAvailable,
            settings.startAtLogin,
            enabled = platform.autostart.isSupported,
        ) { enabled ->
            onUpdate(settings.copy(startAtLogin = applyAutostart(platform.autostart, enabled)))
        }
        SettingSwitch(
            strings.crashReporting,
            if (platform.crashReporter.isAvailable) null else strings.notAvailable,
            settings.crashReporting,
            enabled = platform.crashReporter.isAvailable,
        ) { enabled ->
            platform.crashReporter.setEnabled(enabled)
            onUpdate(settings.copy(crashReporting = platform.crashReporter.isEnabled))
        }
        SettingSwitch(strings.saveHistory, null, settings.saveHistory, enabled = true) { enabled ->
            MessageHistory.setEnabled(identity.context, enabled)
            // Saving now writes everything (on) or erases the saved file (off).
            state.scope.launch { state.node.persistNow() }
            onUpdate(settings.copy(saveHistory = MessageHistory.enabled.value))
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    hint: String?,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}
