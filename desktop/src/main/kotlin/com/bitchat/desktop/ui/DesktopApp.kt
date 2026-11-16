package com.bitchat.desktop.ui

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bitchat.android.linked.PrefsLinkStore
import com.bitchat.android.services.AppStateStore
import com.bitchat.desktop.DesktopRestart
import com.bitchat.desktop.ble.BleMeshTransport
import com.bitchat.desktop.core.DesktopIdentity
import com.bitchat.desktop.core.DesktopMeshNode
import com.bitchat.desktop.core.LoopbackHub
import com.bitchat.desktop.link.DesktopLinkController
import com.bitchat.desktop.link.DesktopLinkedAppBridge
import com.bitchat.desktop.nostr.DesktopNostrRelayClient
import com.bitchat.desktop.nostr.NostrLocationChannelsPort
import com.bitchat.desktop.platform.DesktopPlatform
import com.bitchat.desktop.platform.NoopBleTransport
import com.bitchat.desktop.storage.DesktopContactsStore
import com.bitchat.desktop.storage.EncryptedHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Hook for other desktop builders: replace in-memory ports with real ones (DesktopStore history
 * and contacts, Nostr location channels, linking/backup panels) before the window opens.
 */
typealias PortsCustomizer = (ports: SkeinPorts, context: Context, platform: DesktopPlatform) -> SkeinPorts

fun runDesktopApp(
    platform: DesktopPlatform,
    startHidden: Boolean = false,
    customizePorts: PortsCustomizer = { ports, _, _ -> ports },
) {
    val ctx = Context(platform.paths.dataDir)
    val identity = DesktopIdentity.loadOrCreate(ctx)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var bleTransport: BleMeshTransport? = null
    var loopbackTransport: com.bitchat.desktop.core.LoopbackTransport? = null
    val node: DesktopMeshNode = if (platform.ble !== NoopBleTransport) {
        lateinit var createdNode: DesktopMeshNode
        val bleMesh = BleMeshTransport(
            ble = platform.ble,
            scope = appScope,
            onPacketReceived = { pkt, senderPeerID, linkId ->
                createdNode.receive(pkt, senderPeerID, linkId)
            },
        )
        createdNode = DesktopMeshNode(identity, bleMesh)
        bleTransport = bleMesh
        bleMesh.start()
        createdNode
    } else {
        val (n, lt) = DesktopMeshNode.overLoopback(identity, LoopbackHub())
        loopbackTransport = lt
        n
    }

    val settingsPort = PrefsSettingsPort(ctx)
    val initialSettings = settingsPort.load()
    applyDockPreference(initialSettings)

    // Encrypted on-disk history and contacts, keyed from the OS key store (Keychain / DPAPI).
    val keyFor: (String) -> ByteArray = platform.keyStore::getOrCreateKey
    val historyStore = EncryptedHistoryStore.open(platform.paths.dataDir, keyFor)
    val contactsStore = DesktopContactsStore.open(ctx, platform.paths.dataDir, keyFor, node::peerNoiseKey)
    if (initialSettings.saveHistory) {
        // The node is the one live store for the window and linked devices; seed it from disk.
        historyStore.load().forEach { m -> toBitchatMessage(m)?.let { (conv, msg) -> node.restore(conv, msg) } }
    }
    node.peers.onEach { ids ->
        ids.forEach { contactsStore.promotePending(it, node.peerNickname(it) ?: it.take(8)) }
    }.launchIn(appScope)

    // Keep AppStateStore in sync for linked devices and encrypted backups.
    AppStateStore.setNickname(identity.nickname)
    node.messageSink = { msg, conversation ->
        if (conversation == null) {
            AppStateStore.addPublicMessage(msg)
        } else {
            AppStateStore.addPrivateMessage(conversation, msg, forceRead = msg.senderPeerID == identity.peerID)
        }
    }
    appScope.launch { node.peers.collect { AppStateStore.setTransportPeers("mesh", it) } }
    node.start()

    val link: DesktopLinkController? = try {
        val bridge = DesktopLinkedAppBridge(
            identity = identity,
            node = node,
            onRestartRequested = { DesktopRestart.relaunch() },
            onRadiosSuppressedChanged = { suppressed ->
                // A following computer lets the leading device's radios speak for the identity.
                if (suppressed) bleTransport?.stop() else bleTransport?.start()
            },
        )
        DesktopLinkController(appScope, PrefsLinkStore(ctx), bridge, platform.discovery).also { it.startIfNeeded() }
    } catch (e: Exception) {
        android.util.Log.w("DesktopApp", "Linked devices unavailable: ${e.javaClass.simpleName}")
        null
    }

    val panels = object : FeaturePanels {
        @Composable
        override fun LinkedDevices(strings: UiStrings) {
            val lang = if (strings.appTitle == "سكين") AppLanguage.ARABIC else AppLanguage.ENGLISH
            LinkedDevicesScreen(link = link, strings = strings, ls = linkBackupStringsFor(lang))
        }

        @Composable
        override fun Backups(strings: UiStrings) {
            val lang = if (strings.appTitle == "سكين") AppLanguage.ARABIC else AppLanguage.ENGLISH
            BackupsScreen(
                context = ctx,
                nickname = { identity.nickname },
                strings = strings,
                ls = linkBackupStringsFor(lang),
                onIdentityRestored = { DesktopRestart.relaunch() },
            )
        }
    }

    val relayClient = DesktopNostrRelayClient(DesktopNostrRelayClient.DEFAULT_RELAYS, appScope)
    val nostrLocations = NostrLocationChannelsPort(
        context = ctx,
        myNickname = { identity.nickname },
        relayClient = relayClient,
        scope = appScope,
    )

    val ports = customizePorts(
        SkeinPorts(
            messaging = MeshMessagingAdapter(node, appScope),
            contacts = contactsStore,
            history = historyStore,
            locations = nostrLocations,
            settings = settingsPort,
            platform = DesktopPlatformToggles(platform),
            panels = panels,
            appInfo = AppInfo(version = System.getProperty("skein.version") ?: AppInfo().version),
        ),
        ctx,
        platform,
    )
    val viewModel = SkeinViewModel(ports, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    application {
        var windowVisible by remember { mutableStateOf(!startHidden) }
        val uiState by viewModel.state.collectAsState()
        val totalUnread = uiState.privateChats.sumOf { it.unread } + uiState.mesh.unread
        LaunchedEffect(totalUnread) {
            platform.tray.setUnreadCount(totalUnread)
        }
        LaunchedEffect(node) {
            node.privateMessages.collect { list ->
                val latest = list.lastOrNull() ?: return@collect
                if (!windowVisible && latest.senderPeerID != identity.peerID) {
                    platform.notifications.notify(latest.id, latest.sender, "New private message")
                }
            }
        }
        val shutdown = {
            platform.tray.hide()
            viewModel.close()
            relayClient.stop()
            link?.stop()
            (platform.discovery as? AutoCloseable)?.close()
            node.stop()
            runCatching { historyStore.close() }
            contactsStore.close()
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
        Window(
            onCloseRequest = {
                if (platform.tray.isSupported) windowVisible = false else shutdown()
            },
            visible = windowVisible,
            title = "Skein",
            state = rememberWindowState(width = 1080.dp, height = 720.dp),
        ) {
            SkeinDesktopRoot(viewModel, ports)
        }
    }
}

@Composable
fun SkeinDesktopRoot(viewModel: SkeinViewModel, ports: SkeinPorts) {
    val state by viewModel.state.collectAsState()
    SkeinTheme(state.settings.language) {
        CompositionLocalProvider(LocalLayoutDirection provides state.settings.language.layoutDirection) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(state, viewModel, Modifier.width(280.dp).fillMaxHeight())
                    VerticalDivider()
                    MainArea(state, viewModel, ports, Modifier.weight(1f))
                }
            }
        }
    }
}
