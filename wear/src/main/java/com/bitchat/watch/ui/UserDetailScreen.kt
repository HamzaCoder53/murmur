package com.bitchat.watch.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bitchat.watch.R
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette
import com.bitchat.watch.ui.theme.colorForPeer

@Composable
fun UserDetailScreen(
    peerID: String,
    onOpenVerification: () -> Unit
) {
    val mesh = WearMeshService.peek()
    val revision by WearPeerIdentityState.revision.collectAsState()
    val identity = androidx.compose.runtime.remember(peerID, revision) {
        WearPeerIdentityState.snapshot(peerID, mesh)
    }
    val nickname = mesh?.getPeerNickname(peerID) ?: peerID.take(8)
    val listState = rememberScalingLazyListState()
    val palette = LocalBitchatPalette.current
    val favoriteLit = identity.isFavorite || identity.theyFavoritedUs

    ScreenScaffold(scrollState = listState) { scaffoldPadding ->
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = scaffoldPadding
        ) {
            item {
                ListHeader {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = nickname,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colorForPeer(nickname + peerID, palette),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = peerID.take(8),
                            style = ChatVisualTokens.TimestampStyle,
                            color = palette.textTertiary
                        )
                    }
                }
            }

            item {
                FilledTonalButton(
                    onClick = {
                        mesh?.let {
                            WearPeerIdentityState.setFavorite(
                                peerID = peerID,
                                isFavorite = !identity.isFavorite,
                                mesh = it
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Icon(
                            painter = painterResource(
                                if (identity.isFavorite) {
                                    R.drawable.ic_spec_star_filled
                                } else {
                                    R.drawable.ic_spec_star
                                }
                            ),
                            contentDescription = null,
                            tint = if (favoriteLit) palette.accentOrange else palette.textTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    secondaryLabel = favoriteSubtitle(identity)?.let { subtitle ->
                        {
                            Text(
                                text = subtitle,
                                style = ChatVisualTokens.SystemActionStyle,
                                color = palette.accentOrange,
                                maxLines = 1
                            )
                        }
                    }
                ) {
                    Text(
                        text = stringResource(if (identity.isFavorite) R.string.user_favorited else R.string.user_favorite),
                        maxLines = 1
                    )
                }
            }

            item {
                FilledTonalButton(
                    onClick = onOpenVerification,
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Icon(
                            imageVector = if (identity.isVerified) {
                                Icons.Filled.Verified
                            } else {
                                Icons.Filled.Shield
                            },
                            contentDescription = null,
                            tint = if (identity.isVerified) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                palette.textTertiary
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    secondaryLabel = if (identity.isVerified) null else {
                        {
                            Text(
                                text = stringResource(R.string.user_compare_codes),
                                style = ChatVisualTokens.SystemActionStyle,
                                color = palette.textTertiary,
                                maxLines = 1
                            )
                        }
                    }
                ) {
                    Text(
                        text = stringResource(if (identity.isVerified) R.string.user_verified else R.string.user_verify),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun favoriteSubtitle(identity: WearPeerIdentitySnapshot): String? = when {
    identity.isFavorite && identity.theyFavoritedUs -> stringResource(R.string.user_mutual)
    identity.theyFavoritedUs -> stringResource(R.string.user_favors_you)
    else -> null
}
