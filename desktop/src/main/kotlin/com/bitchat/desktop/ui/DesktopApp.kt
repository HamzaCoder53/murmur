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
import com.bitchat.desktop.ble.BleMeshTransport
import com.bitchat.desktop.core.DesktopIdentity
import com.bitchat.desktop.core.DesktopMeshNode
import com.bitchat.desktop.core.LoopbackHub
import com.bitchat.desktop.platform.DesktopPlatform
import com.bitchat.desktop.platform.NoopBleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Hook for other desktop builders: replace in-memory ports with real ones (DesktopStore history
 * and contacts, Nostr location channels, linking/backup panels) before the window opens.
 * Keep each builder's change to one line here.
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
    node.start()

    val settingsPort = PrefsSettingsPort(ctx)
    applyDockPreference(settingsPort.load())
    val ports = customizePorts(
        SkeinPorts(
            messaging = MeshMessagingAdapter(node, appScope),
            locations = InMemoryLocationChannelsPort(myNickname = { identity.nickname }),
            settings = settingsPort,
            platform = DesktopPlatformToggles(platform),
            appInfo = AppInfo(version = System.getProperty("skein.version") ?: AppInfo().version),
        ),
        ctx,
        platform,
    )
    val viewModel = SkeinViewModel(ports, CoroutineScope(SupervisorJob() + Dispatchers.Default))

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
