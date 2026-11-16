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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bitchat.desktop.core.DesktopIdentity
import com.bitchat.desktop.core.DesktopMeshNode
import com.bitchat.desktop.core.LoopbackHub
import com.bitchat.desktop.platform.DesktopPlatform
import com.bitchat.desktop.platform.HostOs

enum class SkeinTab { CHAT, DMS, PEOPLE, LOCATIONS, HISTORY, LINKED, BACKUPS, SETTINGS }

data class DesktopUiSettings(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val broadcastPower: BroadcastPower = BroadcastPower.MEDIUM,
    val showInDock: Boolean = true,
    val startAtLogin: Boolean = false,
    val crashReporting: Boolean = false,
)

fun runDesktopApp(platform: DesktopPlatform, startHidden: Boolean = false) {
    val ctx = Context(platform.paths.dataDir)
    val identity = DesktopIdentity.loadOrCreate(ctx)
    val (node, transport) = DesktopMeshNode.overLoopback(identity, LoopbackHub())
    node.start()
    application {
        var windowVisible by remember { mutableStateOf(!startHidden) }
        Window(
            onCloseRequest = {
                node.stop()
                transport.close()
                exitApplication()
            },
            visible = windowVisible,
            title = "Skein",
            state = rememberWindowState(width = 860.dp, height = 640.dp),
        ) {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    SkeinDesktopRoot(identity, node, platform)
                }
            }
        }
    }
}

@Composable
fun SkeinDesktopRoot(
    identity: DesktopIdentity,
    node: DesktopMeshNode,
    platform: DesktopPlatform,
) {
    var activeTab by remember { mutableStateOf(SkeinTab.CHAT) }
    var settings by remember {
        mutableStateOf(
            DesktopUiSettings(
                startAtLogin = platform.autostart.isEnabled(),
                crashReporting = platform.crashReporter.isEnabled,
            ),
        )
    }
    val strings = stringsFor(settings.language)

    CompositionLocalProvider(LocalLayoutDirection provides settings.language.layoutDirection) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.fillMaxHeight().width(116.dp)) {
                SkeinTab.entries.forEach { tab ->
                    val label = when (tab) {
                        SkeinTab.CHAT -> strings.tabChat
                        SkeinTab.DMS -> strings.tabDms
                        SkeinTab.PEOPLE -> strings.tabPeople
                        SkeinTab.LOCATIONS -> strings.tabLocations
                        SkeinTab.HISTORY -> strings.tabHistory
                        SkeinTab.LINKED -> strings.tabLinked
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
                when (activeTab) {
                    SkeinTab.CHAT -> PublicChatScreen(identity, node, strings)
                    SkeinTab.DMS -> DirectMessagesScreen(node, strings)
                    SkeinTab.PEOPLE -> PeopleScreen(node, strings)
                    SkeinTab.LOCATIONS -> LocationsScreen(node, strings)
                    SkeinTab.HISTORY -> HistoryScreen(node, strings)
                    SkeinTab.LINKED -> LinkedDevicesScreen(strings)
                    SkeinTab.BACKUPS -> BackupsScreen(strings)
                    SkeinTab.SETTINGS -> SettingsScreen(identity, platform, settings, strings) { updated ->
                        settings = updated
                    }
                }
            }
        }
    }
}

@Composable
private fun PublicChatScreen(identity: DesktopIdentity, node: DesktopMeshNode, strings: UiStrings) {
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
    val privateMessages by node.privateMessages.collectAsState()
    var selectedPeer by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.tabDms, style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            peers.forEach { p ->
                FilterChip(
                    selected = selectedPeer == p,
                    onClick = { selectedPeer = p },
                    label = { Text(node.peerNickname(p) ?: p.take(8)) },
                )
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val filtered = privateMessages.filter {
                selectedPeer == null || it.senderPeerID == selectedPeer
            }
            items(filtered, key = { it.id }) { m -> Text("<${m.sender}> ${m.content}") }
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
private fun LocationsScreen(node: DesktopMeshNode, strings: UiStrings) {
    var geohash by remember { mutableStateOf("dr5r") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.tabLocations, style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(geohash, { geohash = it.take(8) }, label = { Text("Geohash channel") })
        Text("Channel #$geohash — ${node.peers.collectAsState().value.size} nearby peers")
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

@Composable
private fun LinkedDevicesScreen(strings: UiStrings) {
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.tabLinked, style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text(strings.pairWithCode) })
        Button(enabled = code.length == 6, onClick = { code = "" }) { Text(strings.pairWithCode) }
    }
}

@Composable
private fun BackupsScreen(strings: UiStrings) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings.tabBackups, style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { }) { Text(strings.exportBackup) }
        OutlinedButton(onClick = { }) { Text(strings.importBackup) }
    }
}

@Composable
private fun SettingsScreen(
    identity: DesktopIdentity,
    platform: DesktopPlatform,
    settings: DesktopUiSettings,
    strings: UiStrings,
    onUpdate: (DesktopUiSettings) -> Unit,
) {
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
                    onClick = { onUpdate(settings.copy(language = lang)) },
                    label = { Text(lang.label) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.broadcastPower)
            BroadcastPower.entries.forEach { power ->
                FilterChip(
                    selected = settings.broadcastPower == power,
                    onClick = { onUpdate(settings.copy(broadcastPower = power)) },
                    label = {
                        Text(if (settings.language == AppLanguage.ARABIC) power.labelAr else power.label)
                    },
                )
            }
        }
        if (HostOs.current() == HostOs.MAC) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(strings.showInDock)
                Switch(checked = settings.showInDock, onCheckedChange = { onUpdate(settings.copy(showInDock = it)) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(strings.startAtLogin)
            Switch(
                checked = settings.startAtLogin,
                onCheckedChange = { enabled ->
                    platform.autostart.setEnabled(enabled)
                    onUpdate(settings.copy(startAtLogin = enabled))
                },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(strings.crashReporting)
            Switch(
                checked = settings.crashReporting,
                onCheckedChange = { enabled ->
                    platform.crashReporter.setEnabled(enabled)
                    onUpdate(settings.copy(crashReporting = enabled))
                },
            )
        }
    }
}
