package com.bitchat.watch.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bitchat.watch.R
import com.bitchat.watch.notification.AlertSettings
import com.bitchat.watch.ui.theme.LocalBitchatPalette

/** Pick how a new private message announces itself; each choice plays as a preview. */
@Composable
fun NotificationSettingsScreen() {
    val context = LocalContext.current
    val palette = LocalBitchatPalette.current
    val listState = rememberScalingLazyListState()
    LaunchedEffect(Unit) { AlertSettings.current(context) }
    val selected by AlertSettings.style.collectAsState()

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                ListHeader {
                    Text(stringResource(R.string.alerts_title), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            item {
                Text(
                    stringResource(R.string.alerts_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            items(AlertSettings.Style.entries) { style ->
                FilledTonalButton(
                    onClick = {
                        AlertSettings.set(context, style)
                        AlertSettings.preview(context, style)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = if (style == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.alerts_selected)) }
                    } else {
                        null
                    }
                ) { Text(stringResource(style.labelRes), maxLines = 1) }
            }
        }
    }
}
