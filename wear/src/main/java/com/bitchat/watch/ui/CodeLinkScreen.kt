package com.bitchat.watch.ui

import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bitchat.android.linked.LinkCoordinator
import com.bitchat.watch.R
import com.bitchat.watch.link.CodeLink
import com.bitchat.watch.ui.theme.LocalBitchatPalette
import kotlinx.coroutines.launch

/**
 * Link this watch to a phone it is not paired with through Wear OS: type the 6-digit code from
 * the phone's Settings → Linked devices → Link a new device. Both must be on the same Wi-Fi.
 */
@Composable
fun CodeLinkScreen() {
    val context = LocalContext.current
    val palette = LocalBitchatPalette.current
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val pairing by CodeLink.pairing.collectAsState()
    var code by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    val linked = remember(pairing) { CodeLink.isLinked(context) }
    // Android 17 asks before an app may use the local network.
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && code.length == 6) started = CodeLink.joinWithCode(context, code)
    }
    fun link() {
        if (code.length != 6) return
        val needsPermission = android.os.Build.VERSION.SDK_INT >= 37 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_LOCAL_NETWORK") !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsPermission) localNetwork.launch("android.permission.ACCESS_LOCAL_NETWORK")
        else started = CodeLink.joinWithCode(context, code)
    }

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                ListHeader {
                    Text(stringResource(R.string.code_link_title), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            if (linked && pairing !is LinkCoordinator.Pairing.Restarting) {
                item {
                    Text(
                        stringResource(if (CodeLink.isFollowing()) R.string.code_link_following else R.string.code_link_linked),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                item {
                    FilledTonalButton(
                        onClick = { scope.launch { CodeLink.unlink() } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.code_link_unlink), maxLines = 1) }
                }
                return@ScalingLazyColumn
            }
            item {
                Text(
                    stringResource(R.string.code_link_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            item {
                WatchEditText(
                    text = code,
                    onTextChange = { code = it.filter(Char::isDigit).take(6) },
                    hint = "000000",
                    autoFocus = false,
                    onSend = { link() },
                    imeAction = android.view.inputmethod.EditorInfo.IME_ACTION_DONE,
                    centered = true,
                    maxLength = 6,
                    numeric = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            item {
                FilledTonalButton(
                    onClick = { link() },
                    enabled = code.length == 6 && (pairing is LinkCoordinator.Pairing.Idle || pairing is LinkCoordinator.Pairing.Failed),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) { Text(stringResource(R.string.code_link_button), maxLines = 1) }
            }
            item {
                val status = when (val p = pairing) {
                    LinkCoordinator.Pairing.Idle -> if (started) stringResource(R.string.code_link_searching) else null
                    is LinkCoordinator.Pairing.Failed -> stringResource(R.string.code_link_failed)
                    LinkCoordinator.Pairing.WaitingForOtherDevice -> stringResource(R.string.code_link_confirm_on_phone)
                    LinkCoordinator.Pairing.Transferring -> stringResource(R.string.code_link_transferring)
                    LinkCoordinator.Pairing.Restarting, is LinkCoordinator.Pairing.Done -> stringResource(R.string.code_link_restarting)
                    else -> stringResource(R.string.code_link_searching)
                }
                if (status != null) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pairing is LinkCoordinator.Pairing.Failed) MaterialTheme.colorScheme.error else palette.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                    )
                }
            }
        }
    }
}
