package com.bitchat.watch.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.internet.WatchLocationChannels
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.ui.theme.LocalBitchatPalette
import com.bitchat.watch.ui.theme.colorForPeer

/** "Block", "City"… for a geohash, from its length. */
internal fun areaLabel(geohash: String): String =
    GeohashChannelLevel.entries.firstOrNull { it.precision == geohash.length && it != GeohashChannelLevel.BUILDING }
        ?.displayName?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Area"

/** Pick an area around you: block, neighborhood, city, province or region. */
@Composable
fun LocationChannelsScreen(onOpenChannel: (String) -> Unit) {
    val channels = WearMeshService.peek()?.locationChannels ?: return
    val places by channels.places.collectAsState()
    val palette = LocalBitchatPalette.current
    val listState = rememberScalingLazyListState()
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        channels.refreshPlaces()
    }
    LaunchedEffect(Unit) { channels.refreshPlaces() }

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                ListHeader {
                    Text("Location channels", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            when (val p = places) {
                WatchLocationChannels.Places.NoPermission -> {
                    item {
                        Text(
                            "Public chat with people in your area. Only the approximate area is shared, never your exact location.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    item {
                        FilledTonalButton(
                            onClick = {
                                askPermission.launch(
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            icon = { Icon(Icons.Filled.MyLocation, contentDescription = null) }
                        ) { Text("Allow location", maxLines = 1) }
                    }
                }
                WatchLocationChannels.Places.Unknown, WatchLocationChannels.Places.Locating -> item {
                    Text(
                        "Finding your area…",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    )
                }
                is WatchLocationChannels.Places.Failed -> {
                    item {
                        Text(
                            p.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(8.dp)
                        )
                    }
                    item {
                        FilledTonalButton(onClick = { channels.refreshPlaces() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Try again", maxLines = 1)
                        }
                    }
                }
                is WatchLocationChannels.Places.Found -> items(p.channels) { channel ->
                    FilledTonalButton(
                        onClick = { onOpenChannel(channel.geohash) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                        secondaryLabel = { Text("#${channel.geohash}", color = palette.textTertiary, maxLines = 1) }
                    ) { Text(areaLabel(channel.geohash), maxLines = 1) }
                }
            }
        }
    }
}

/** Live public chat for one area. */
@Composable
fun LocationChatScreen(geohash: String, onCompose: () -> Unit) {
    val channels = WearMeshService.peek()?.locationChannels ?: return
    val allChannels by AppStateStore.channelMessages.collectAsState()
    val relays by channels.liveRelays.collectAsState()
    val messages = allChannels[channels.messagesKey(geohash)].orEmpty()
    val palette = LocalBitchatPalette.current
    val listState = rememberScalingLazyListState()

    DisposableEffect(geohash) {
        channels.open(geohash)
        onDispose { channels.close() }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size + 1)
    }

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                ListHeader {
                    Column {
                        Text(areaLabel(geohash), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(
                            if (relays > 0) "#$geohash · live" else "#$geohash · connecting…",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textTertiary
                        )
                    }
                }
            }
            if (messages.isEmpty()) {
                item {
                    Text(
                        "No messages in the last hour",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(10.dp)
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
                    Text(
                        message.sender,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorForPeer(message.sender + (message.senderNostrPubkey ?: ""), palette),
                        maxLines = 1
                    )
                    Text(message.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    if (message.deliveryStatus is com.bitchat.android.model.DeliveryStatus.Failed) {
                        Text("Not sent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                FilledTonalButton(
                    onClick = onCompose,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    icon = { Icon(Icons.Filled.Keyboard, contentDescription = null) }
                ) { Text("Write", maxLines = 1) }
            }
        }
    }
}
