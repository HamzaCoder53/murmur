package com.bitchat.desktop.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bitchat.desktop.core.DesktopIdentity
import com.bitchat.desktop.core.DesktopMeshNode
import com.bitchat.desktop.core.LoopbackHub
import com.bitchat.desktop.platform.DesktopPlatform

/**
 * Minimal first window: identity plus a public timeline over the loopback transport (until the
 * BLE helpers land, messages only reach other nodes in this process). UI parity is a later stage.
 */
fun runDesktopApp(platform: DesktopPlatform) {
    val identity = DesktopIdentity.loadOrCreate(Context(platform.paths.dataDir))
    val (node, transport) = DesktopMeshNode.overLoopback(identity, LoopbackHub())
    node.start()
    application {
        Window(
            onCloseRequest = {
                node.stop()
                transport.close()
                exitApplication()
            },
            title = "Skein",
            state = rememberWindowState(width = 520.dp, height = 640.dp),
        ) {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    SkeinHome(identity, node)
                }
            }
        }
    }
}

@Composable
private fun SkeinHome(identity: DesktopIdentity, node: DesktopMeshNode) {
    val messages by node.publicMessages.collectAsState()
    val peers by node.peers.collectAsState()
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Skein", style = MaterialTheme.typography.headlineSmall)
        SelectionContainer {
            Column {
                Text("Nickname: ${identity.nickname}")
                Text("Peer ID: ${identity.peerID}", fontFamily = FontFamily.Monospace)
            }
        }
        Text("Peers nearby: ${peers.size}", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(messages, key = { it.id }) { m -> Text("<${m.sender}> ${m.content}") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Message") })
            Button(onClick = {
                if (draft.isNotBlank()) node.sendPublic(draft.trim())
                draft = ""
            }) { Text("Send") }
        }
    }
}
