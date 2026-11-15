package com.bitchat.watch.ui

import com.bitchat.watch.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.bitchat.android.features.voice.LiveVoicePreferences
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette

private const val MENU_PAGE = 0
private const val CHAT_PAGE = 1

/**
 * Home of the app: the public mesh chat, with an app menu one page to its left. Swiping the
 * chat to the right reveals the menu (the Wear pattern used by Fitbit, Health and similar
 * apps); swiping right again from the menu's left edge falls through to the system
 * swipe-to-dismiss, so leaving the app still works as everywhere else on the watch.
 *
 * [swipeEnabled] is false while the chat owns a horizontal gesture (push-to-talk drag,
 * full-screen image), so the pager never steals the pointer mid-gesture.
 */
@Composable
fun ChatHomePager(
    swipeEnabled: Boolean,
    menu: @Composable () -> Unit,
    chat: @Composable () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = CHAT_PAGE) { 2 }
    // No page indicator: the menu is a peek-behind page, not a peer of the chat, and the
    // dots would crowd the chat's edge action bar.
    HorizontalPagerScaffold(pagerState = pagerState, pageIndicator = null) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = swipeEnabled,
            // The crown scrolls chat history, never pages.
            rotaryScrollableBehavior = null,
        ) { page ->
            when (page) {
                MENU_PAGE -> menu()
                CHAT_PAGE -> chat()
            }
        }
    }
}

/**
 * The app's settings live here, beside the chat, rather than inside the People list: mesh
 * status, the live push-to-talk switch, and quitting.
 */
@Composable
fun ChatMenuScreen(onQuit: () -> Unit, onOpenHelp: () -> Unit = {}, onOpenLocations: () -> Unit = {}) {
    val context = LocalContext.current
    val palette = LocalBitchatPalette.current
    val peers by AppStateStore.peers.collectAsState()
    val listState = rememberScalingLazyListState()
    var confirmQuit by rememberSaveable { mutableStateOf(false) }
    var liveVoiceEnabled by remember { mutableStateOf(LiveVoicePreferences.isEnabled(context)) }
    val saveHistory by com.bitchat.android.services.MessageHistory.enabled.collectAsState()
    val linkClient = com.bitchat.watch.mesh.WearMeshService.peek()?.linkedClient
    val linkState by (linkClient?.state ?: kotlinx.coroutines.flow.MutableStateFlow(
        com.bitchat.watch.link.LinkedPhoneClient.State.NOT_LINKED
    )).collectAsState()
    var confirmLink by rememberSaveable { mutableStateOf(false) }
    var confirmUnlink by rememberSaveable { mutableStateOf(false) }
    var confirmEraseHistory by rememberSaveable { mutableStateOf(false) }

    ScreenScaffold(scrollState = listState) { scaffoldPadding ->
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = scaffoldPadding,
        ) {
            item {
                ListHeader {
                    Text(
                        text = stringResource(R.string.app_name),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            item {
                Text(
                    text = meshStatusText(peers.size),
                    style = ChatVisualTokens.SystemActionStyle,
                    color = palette.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
            item {
                SwitchButton(
                    checked = liveVoiceEnabled,
                    onCheckedChange = { enabled ->
                        liveVoiceEnabled = enabled
                        LiveVoicePreferences.setEnabled(context, enabled)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    secondaryLabel = {
                        Text(stringResource(R.string.menu_live_talk_detail), maxLines = 1)
                    },
                ) {
                    Text(stringResource(R.string.menu_live_talk), maxLines = 1)
                }
            }
            item {
                SwitchButton(
                    checked = saveHistory,
                    onCheckedChange = { enable ->
                        if (enable) {
                            com.bitchat.android.services.MessageHistory.setEnabled(context, true)
                        } else {
                            confirmEraseHistory = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    secondaryLabel = {
                        Text(stringResource(R.string.menu_save_history_detail), maxLines = 1)
                    },
                ) {
                    Text(stringResource(R.string.menu_save_history), maxLines = 1)
                }
            }
            item {
                FilledTonalButton(
                    onClick = onOpenLocations,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(imageVector = Icons.Filled.LocationOn, contentDescription = null) },
                    secondaryLabel = { Text(stringResource(R.string.menu_location_chat_detail), color = palette.textTertiary, maxLines = 1) },
                ) {
                    Text(stringResource(R.string.menu_location_chat), maxLines = 1)
                }
            }
            item {
                FilledTonalButton(
                    onClick = onOpenHelp,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(imageVector = Icons.Filled.HelpOutline, contentDescription = null) },
                    secondaryLabel = { Text(stringResource(R.string.menu_help_detail), color = palette.textTertiary, maxLines = 1) },
                ) {
                    Text(stringResource(R.string.menu_help), maxLines = 1)
                }
            }
            val internet = com.bitchat.watch.mesh.WearMeshService.peek()?.internet
            if (internet != null) {
                item { InternetModeButton(internet) }
                item { InternetCheckButton(internet) }
            }
            if (linkClient != null) {
                item {
                    LinkPhoneButton(
                        state = linkState,
                        onLink = { confirmLink = true },
                        onUnlink = { confirmUnlink = true },
                    )
                }
            }
            item {
                FilledTonalButton(
                    onClick = { confirmQuit = true },
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.PowerSettingsNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = stringResource(R.string.menu_quit_detail),
                            color = palette.textTertiary,
                            maxLines = 1,
                        )
                    },
                ) {
                    Text(stringResource(R.string.menu_quit), maxLines = 1)
                }
            }
        }
    }

    AlertDialog(
        visible = confirmLink,
        onDismissRequest = { confirmLink = false },
        title = { Text(stringResource(R.string.dialog_link_title)) },
        text = {
            Text(stringResource(R.string.dialog_link_text))
        },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                confirmLink = false
                linkClient?.requestLink()
            })
        },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmLink = false }) },
    )

    AlertDialog(
        visible = confirmUnlink,
        onDismissRequest = { confirmUnlink = false },
        title = { Text(stringResource(R.string.dialog_unlink_title)) },
        text = { Text(stringResource(R.string.dialog_unlink_text)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmUnlink = false
                    linkClient?.unlink()
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            )
        },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmUnlink = false }) },
    )

    AlertDialog(
        visible = confirmEraseHistory,
        onDismissRequest = { confirmEraseHistory = false },
        title = { Text(stringResource(R.string.dialog_erase_title)) },
        text = { Text(stringResource(R.string.dialog_erase_text)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmEraseHistory = false
                    com.bitchat.android.services.MessageHistory.setEnabled(context, false)
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { confirmEraseHistory = false })
        },
    )

    AlertDialog(
        visible = confirmQuit,
        onDismissRequest = { confirmQuit = false },
        icon = {
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp),
            )
        },
        title = { Text(stringResource(R.string.dialog_quit_title)) },
        confirmButton = {
            // Quitting tears the mesh down, so confirm carries the error color, not primary.
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmQuit = false
                    onQuit()
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { confirmQuit = false })
        },
    )
}

@Composable
private fun meshStatusText(count: Int): String =
    if (count == 0) stringResource(R.string.mesh_status_scanning)
    else pluralStringResource(R.plurals.mesh_status_peers, count, count)

@Composable
private fun LinkPhoneButton(
    state: com.bitchat.watch.link.LinkedPhoneClient.State,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
) {
    val palette = LocalBitchatPalette.current
    val linked = state == com.bitchat.watch.link.LinkedPhoneClient.State.LINKED
    val busy = state == com.bitchat.watch.link.LinkedPhoneClient.State.REQUESTING ||
        state == com.bitchat.watch.link.LinkedPhoneClient.State.RESTARTING
    val detail = when (state) {
        com.bitchat.watch.link.LinkedPhoneClient.State.NOT_LINKED -> stringResource(R.string.link_state_not_linked)
        com.bitchat.watch.link.LinkedPhoneClient.State.NO_PHONE -> stringResource(R.string.link_state_no_phone)
        com.bitchat.watch.link.LinkedPhoneClient.State.REQUESTING -> stringResource(R.string.link_state_requesting)
        com.bitchat.watch.link.LinkedPhoneClient.State.DENIED -> stringResource(R.string.link_state_denied)
        com.bitchat.watch.link.LinkedPhoneClient.State.LINKED -> stringResource(R.string.link_state_linked)
        com.bitchat.watch.link.LinkedPhoneClient.State.RESTARTING -> stringResource(R.string.link_state_restarting)
    }
    FilledTonalButton(
        onClick = { if (linked) onUnlink() else onLink() },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
        icon = {
            Icon(
                imageVector = if (linked) Icons.Filled.LinkOff else Icons.Filled.Link,
                contentDescription = null,
            )
        },
        secondaryLabel = {
            Text(text = detail, color = palette.textTertiary, maxLines = 1)
        },
    ) {
        Text(stringResource(if (linked) R.string.link_button_unlink else R.string.link_button_link), maxLines = 1)
    }
}

@Composable
private fun InternetModeButton(client: com.bitchat.watch.internet.WatchNostrClient) {
    val palette = LocalBitchatPalette.current
    val status by client.status.collectAsState()
    val modes = com.bitchat.watch.internet.WatchNostrClient.Mode.entries
    FilledTonalButton(
        onClick = { client.setMode(modes[(modes.indexOf(status.mode) + 1) % modes.size]) },
        modifier = Modifier.fillMaxWidth(),
        icon = { Icon(imageVector = Icons.Filled.Public, contentDescription = null) },
        secondaryLabel = { Text(text = stringResource(status.mode.labelRes), color = palette.textTertiary, maxLines = 1) },
    ) {
        Text(stringResource(R.string.internet_dms), maxLines = 1)
    }
}

@Composable
private fun InternetCheckButton(client: com.bitchat.watch.internet.WatchNostrClient) {
    val palette = LocalBitchatPalette.current
    val status by client.status.collectAsState()
    val linkedNearby = com.bitchat.watch.mesh.WearMeshService.peek()?.isMirrored == true
    val detail = when {
        linkedNearby -> stringResource(R.string.internet_phone_checks)
        status.checking -> stringResource(R.string.internet_checking)
        status.lastErrorRes != null -> stringResource(status.lastErrorRes!!)
        status.mode == com.bitchat.watch.internet.WatchNostrClient.Mode.ALWAYS && status.connectedRelays > 0 ->
            pluralStringResource(R.plurals.internet_live_relays, status.connectedRelays, status.connectedRelays)
        status.lastCheckMs != null -> stringResource(
            R.string.internet_checked_at,
            java.text.SimpleDateFormat("HH:mm", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(java.util.Date(status.lastCheckMs!!))
        )
        else -> stringResource(R.string.internet_not_checked)
    }
    FilledTonalButton(
        onClick = { client.checkNow() },
        enabled = !linkedNearby && !status.checking,
        modifier = Modifier.fillMaxWidth(),
        icon = { Icon(imageVector = Icons.Filled.Refresh, contentDescription = null) },
        secondaryLabel = { Text(text = detail, color = palette.textTertiary, maxLines = 1) },
    ) {
        Text(stringResource(R.string.internet_check_now), maxLines = 1)
    }
}
