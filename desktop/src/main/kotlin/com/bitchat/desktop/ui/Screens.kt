package com.bitchat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.util.Date

// ---------------------------------------------------------------------------------------------
// Sidebar
// ---------------------------------------------------------------------------------------------

@Composable
fun Sidebar(state: SkeinUiState, vm: SkeinViewModel, modifier: Modifier = Modifier) {
    val s = state.strings
    var geohash by remember { mutableStateOf("") }
    Column(modifier.background(SkeinColors.sidebar).padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${s.appTitle}/", style = MaterialTheme.typography.headlineSmall, color = SkeinColors.green)
            Spacer(Modifier.weight(1f))
            Text("@${state.nickname}", style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), color = SkeinColors.muted, maxLines = 1)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item { ConversationItem(state.mesh, s.meshChannelSubtitle, state, vm) }

            item { SectionHeader(s.locationChannels) }
            items(state.locationChannels, key = { "loc-" + it.title }) { c ->
                ConversationItem(c, c.lastMessage?.text, state, vm)
            }
            if (!state.locationChannelsAvailable) {
                item { Hint(s.comingSoon) }
            } else item {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        geohash,
                        { geohash = it.take(12) },
                        Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(s.geohashHint, style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (vm.joinLocation(geohash)) geohash = "" }),
                    )
                    TextButton(
                        enabled = normalizeGeohash(geohash) != null,
                        onClick = { if (vm.joinLocation(geohash)) geohash = "" },
                    ) { Text(s.joinChannel) }
                }
            }

            item { SectionHeader(s.privateChats) }
            if (state.privateChats.isEmpty()) {
                item { Hint(s.noPrivateChats) }
            }
            items(state.privateChats, key = { "dm-" + (it.id as ConversationId.Private).peerID }) { c ->
                ConversationItem(c, c.lastMessage?.text, state, vm)
            }

            item { SectionHeader("${s.tabPeople} (${state.people.size})") }
            if (state.people.isEmpty()) {
                item { Hint(s.noPeople) }
            }
            items(state.people, key = { "p-" + it.peerID }) { p -> PersonItem(p, state, vm) }
        }
        HorizontalDivider()
        SidebarButton(s.linkedDevices, state.pane == MainPane.LinkedDevices) { vm.openPane(MainPane.LinkedDevices) }
        SidebarButton(s.backups, state.pane == MainPane.Backups) { vm.openPane(MainPane.Backups) }
        SidebarButton(s.tabSettings, state.pane == MainPane.Settings) { vm.openPane(MainPane.Settings) }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = SkeinColors.tertiary,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = SkeinColors.tertiary)
}

@Composable
private fun SidebarButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.fillMaxWidth()
            .background(if (selected) SkeinColors.selected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) SkeinColors.green else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ConversationItem(c: ConversationSummary, subtitle: String?, state: SkeinUiState, vm: SkeinViewModel) {
    val selected = state.selected == c.id && state.pane == MainPane.Conversation
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) SkeinColors.selected else Color.Transparent)
            .clickable { vm.selectConversation(c.id) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.isFavorite) Text("★ ", color = SkeinColors.orange, style = MaterialTheme.typography.bodyMedium)
                Text(
                    c.title,
                    style = if (c.id is ConversationId.Private) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyMedium.technical(),
                    fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Normal,
                    color = if (c.id is ConversationId.Private) SkeinColors.orange else SkeinColors.green,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SkeinColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (c.unread > 0) UnreadBadge(c.unread, c.mentionsMe)
    }
}

@Composable
private fun UnreadBadge(count: Int, mention: Boolean) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (mention) SkeinColors.orange else SkeinColors.green)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(if (count > 99) "99+" else count.toString(), color = Color.Black, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PersonItem(p: PersonRow, state: SkeinUiState, vm: SkeinViewModel) {
    val s = state.strings
    val selected = (state.pane as? MainPane.PeerDetail)?.peerID == p.peerID
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) SkeinColors.selected else Color.Transparent)
            .clickable { vm.openPeer(p.peerID) }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionDot(p.session)
        Spacer(Modifier.width(6.dp))
        Text(
            p.nickname,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (p.isVerified) Text("✓", color = SkeinColors.blue, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (p.isFavorite) "★" else "☆",
            Modifier.clickable { vm.toggleFavorite(p.peerID) }.padding(horizontal = 4.dp),
            color = if (p.isFavorite) SkeinColors.orange else SkeinColors.tertiary,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (p.unread > 0) UnreadBadge(p.unread, false)
        TextButton(onClick = { vm.messagePeer(p.peerID) }) { Text("✉") }
    }
}

@Composable
private fun SessionDot(session: SessionState) {
    val color = when (session) {
        SessionState.ESTABLISHED -> SkeinColors.green
        SessionState.HANDSHAKING -> SkeinColors.orange
        SessionState.NONE -> SkeinColors.tertiary
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

// ---------------------------------------------------------------------------------------------
// Chat
// ---------------------------------------------------------------------------------------------

@Composable
fun ChatPane(state: SkeinUiState, vm: SkeinViewModel, now: () -> Date = { Date() }) {
    val s = state.strings
    val listState = rememberLazyListState()
    LaunchedEffect(state.selected, state.messages.size) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.size - 1)
    }
    Column(Modifier.fillMaxSize()) {
        // Header
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val private = state.selected as? ConversationId.Private
            Column(Modifier.weight(1f)) {
                Text(
                    if (private != null) "${s.privateChatWith} ${state.selectedTitle}" else state.selectedTitle,
                    style = if (private != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleMedium.technical(),
                    color = if (private != null) SkeinColors.orange else SkeinColors.green,
                )
                val sub = when {
                    private != null -> if (state.selectedSession == SessionState.ESTABLISHED) s.encryptedNote else s.notEncryptedNote
                    state.selected == ConversationId.Mesh -> "${s.peersNearby}: ${state.people.size}"
                    else -> null
                }
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = SkeinColors.muted)
            }
            when (val sel = state.selected) {
                is ConversationId.Private -> TextButton(onClick = { vm.openPeer(sel.peerID) }) { Text("ⓘ") }
                is ConversationId.Location -> TextButton(onClick = { vm.leaveLocation(sel.geohash) }) { Text(s.leaveChannel) }
                ConversationId.Mesh -> Unit
            }
        }
        HorizontalDivider()
        if (state.messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(s.emptyConversation, color = SkeinColors.tertiary, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.messages, key = { it.message.id }) { row -> MessageLine(row, state, now()) }
                }
            }
        }
        HorizontalDivider()
        Composer(state, vm)
    }
}

@Composable
private fun MessageLine(row: MessageRow, state: SkeinUiState, now: Date) {
    val m = row.message
    val s = state.strings
    val time = formatTimestamp(m.timestamp, now, ZoneId.systemDefault(), state.settings.language)
    val senderColor = when {
        m.isMine -> SkeinColors.green
        m.conversation is ConversationId.Private -> SkeinColors.orange
        else -> SkeinColors.blue
    }
    val text: AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(color = SkeinColors.tertiary)) { append("[$time] ") }
        withStyle(SpanStyle(color = senderColor, fontWeight = FontWeight.Bold)) {
            append("<@${m.senderNickname}${if (m.isMine) " (${s.you})" else ""}> ")
        }
        appendWithMentions(m.text, row.mentions)
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (row.mentionsMe) SkeinColors.mentionBackground else Color.Transparent)
            .padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Direction from the content: a Latin line stays LTR inside the Arabic (RTL) layout.
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
        val d = m.delivery
        if (m.isMine && d != null) {
            Text(
                deliveryGlyph(d),
                Modifier.padding(start = 6.dp),
                color = if (d == DeliveryState.FAILED) SkeinColors.red else if (d == DeliveryState.READ) SkeinColors.blue else SkeinColors.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(deliveryLabel(d, s), Modifier.padding(start = 2.dp), color = SkeinColors.tertiary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun AnnotatedString.Builder.appendWithMentions(text: String, mentions: List<String>) {
    if (mentions.isEmpty()) {
        append(text)
        return
    }
    val regex = Regex(
        "(?<![\\p{L}\\p{N}_])@(" + mentions.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}_])",
        RegexOption.IGNORE_CASE,
    )
    var last = 0
    regex.findAll(text).forEach { match ->
        append(text.substring(last, match.range.first))
        withStyle(SpanStyle(color = SkeinColors.orange, fontWeight = FontWeight.Bold)) { append(match.value) }
        last = match.range.last + 1
    }
    append(text.substring(last))
}

private fun deliveryGlyph(d: DeliveryState): String = when (d) {
    DeliveryState.SENDING -> "○"
    DeliveryState.SENT -> "✓"
    DeliveryState.DELIVERED -> "✓✓"
    DeliveryState.READ -> "✓✓"
    DeliveryState.FAILED -> "!"
}

@Composable
private fun Composer(state: SkeinUiState, vm: SkeinViewModel) {
    val s = state.strings
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (state.mentionSuggestions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.mentionSuggestions.forEach { nick ->
                    FilterChip(selected = false, onClick = { vm.pickMention(nick) }, label = { Text("@$nick") })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                state.draft,
                vm::updateDraft,
                Modifier.weight(1f).onPreviewKeyEvent { e ->
                    // Enter sends, Shift+Enter inserts a newline.
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                        vm.send()
                        true
                    } else {
                        false
                    }
                },
                placeholder = { Text(s.messageHint) },
                maxLines = 5,
            )
            Button(enabled = state.draft.isNotBlank(), onClick = { vm.send() }) { Text(s.send) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Peer detail
// ---------------------------------------------------------------------------------------------

@Composable
fun PeerDetailPane(state: SkeinUiState, vm: SkeinViewModel) {
    val s = state.strings
    val d = state.peerDetail ?: return
    LaunchedEffect(d.peerID) { vm.refresh() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.nickname, style = MaterialTheme.typography.headlineSmall, color = SkeinColors.orange, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::closePane) { Text(s.close) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${s.peerId}:", style = MaterialTheme.typography.bodySmall, color = SkeinColors.muted)
            Text(d.peerID, style = MaterialTheme.typography.bodySmall.technical(), color = SkeinColors.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SessionDot(d.session)
            Text(sessionLabel(d.session, s), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (d.isVerified) "✓ ${s.verified}" else s.notVerified,
                color = if (d.isVerified) SkeinColors.blue else SkeinColors.tertiary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.messagePeer(d.peerID) }) { Text(s.sendMessage) }
            OutlinedButton(onClick = { vm.toggleFavorite(d.peerID) }) {
                Text(if (d.isFavorite) "★ ${s.unfavorite}" else "☆ ${s.favorite}")
            }
            if (d.session != SessionState.ESTABLISHED && d.connected) {
                OutlinedButton(onClick = { vm.startSession(d.peerID) }) { Text(s.startSession) }
            }
        }
        HorizontalDivider()
        FingerprintBlock(s.yourFingerprint, d.myFingerprint, s)
        FingerprintBlock(s.theirFingerprint, d.theirFingerprint, s)
        Text(s.verifyInstructions, style = MaterialTheme.typography.bodySmall, color = SkeinColors.muted)
        if (d.theirFingerprint != null) {
            if (d.isVerified) {
                OutlinedButton(onClick = { vm.setVerified(d.peerID, false) }) { Text(s.removeVerification) }
            } else {
                Button(onClick = { vm.setVerified(d.peerID, true) }) { Text("✓ ${s.markVerified}") }
            }
        }
    }
}

@Composable
private fun FingerprintBlock(label: String, fingerprint: String?, s: UiStrings) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = SkeinColors.green)
        if (fingerprint == null) {
            Text(s.fingerprintUnknown, color = SkeinColors.orange, style = MaterialTheme.typography.bodyMedium)
        } else {
            SelectionContainer {
                Text(
                    formatFingerprint(fingerprint),
                    Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                    style = MaterialTheme.typography.bodyLarge.technical(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsPane(state: SkeinUiState, vm: SkeinViewModel, ports: SkeinPorts) {
    val s = state.strings
    val settings = state.settings
    var nick by remember(state.nickname) { mutableStateOf(state.nickname) }
    var nickError by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.tabSettings, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::closePane) { Text(s.close) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                nick,
                { nick = it.take(MAX_NICKNAME_CHARS); nickError = false },
                label = { Text(s.nickname) },
                singleLine = true,
                isError = nickError,
                supportingText = if (nickError) ({ Text(s.nicknameInvalid) }) else null,
            )
            Button(enabled = nick.trim() != state.nickname, onClick = { nickError = !vm.setNickname(nick) }) { Text(s.save) }
        }
        LabeledRow(s.language) {
            AppLanguage.entries.forEach { lang ->
                FilterChip(selected = settings.language == lang, onClick = { vm.setLanguage(lang) }, label = { Text(lang.label) })
            }
        }
        LabeledRow(s.broadcastPower) {
            BroadcastPower.entries.forEach { p ->
                FilterChip(selected = settings.broadcastPower == p, onClick = { vm.setBroadcastPower(p) }, label = { Text(p.label(settings.language)) })
            }
        }
        if (ports.platform.isMac) SwitchRow(s.showInDock, settings.showInDock, vm::setShowInDock)
        SwitchRow(s.startAtLogin, settings.startAtLogin, vm::setStartAtLogin)
        SwitchRow(s.crashReporting, settings.crashReporting, vm::setCrashReports)
        if (state.historyAvailable) {
            SwitchRow(s.saveHistory, settings.saveHistory, vm::setSaveHistory, hint = s.saveHistoryHint)
        } else {
            SwitchRow(s.saveHistory, false, {}, hint = s.comingSoon, enabled = false)
        }
        HorizontalDivider()
        LabeledRow(s.linkedDevices) { OutlinedButton(onClick = { vm.openPane(MainPane.LinkedDevices) }) { Text(s.open) } }
        LabeledRow(s.backups) { OutlinedButton(onClick = { vm.openPane(MainPane.Backups) }) { Text(s.open) } }
        HorizontalDivider()
        Text(s.about, style = MaterialTheme.typography.titleMedium, color = SkeinColors.green)
        Text(s.aboutText, style = MaterialTheme.typography.bodyMedium)
        Text("${s.version}: ${ports.appInfo.version}", style = MaterialTheme.typography.bodySmall, color = SkeinColors.muted)
        SelectionContainer {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${s.peerId}:", style = MaterialTheme.typography.bodySmall, color = SkeinColors.muted)
                Text(state.myPeerID, style = MaterialTheme.typography.bodySmall.technical(), color = SkeinColors.muted)
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = SkeinColors.muted)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

// ---------------------------------------------------------------------------------------------
// Linked devices / backups slots
// ---------------------------------------------------------------------------------------------

@Composable
fun PanelFrame(title: String, s: UiStrings, onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text(s.close) }
        }
        content()
    }
}

/** Shown until the linking/backup builders plug their composables into [SkeinPorts.panels]. */
object PlaceholderFeaturePanels : FeaturePanels {
    @Composable
    override fun LinkedDevices(strings: UiStrings) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.pairWithCode, style = MaterialTheme.typography.bodyLarge)
            Text(strings.comingSoon, color = SkeinColors.muted, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    override fun Backups(strings: UiStrings) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = false, onClick = {}) { Text(strings.exportBackup) }
            OutlinedButton(enabled = false, onClick = {}) { Text(strings.importBackup) }
            Text(strings.comingSoon, color = SkeinColors.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun MainArea(state: SkeinUiState, vm: SkeinViewModel, ports: SkeinPorts, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight()) {
        when (state.pane) {
            MainPane.Conversation -> ChatPane(state, vm)
            is MainPane.PeerDetail -> PeerDetailPane(state, vm)
            MainPane.Settings -> SettingsPane(state, vm, ports)
            MainPane.LinkedDevices -> PanelFrame(state.strings.linkedDevices, state.strings, vm::closePane) {
                ports.panels.LinkedDevices(state.strings)
            }
            MainPane.Backups -> PanelFrame(state.strings.backups, state.strings, vm::closePane) {
                ports.panels.Backups(state.strings)
            }
        }
    }
}
