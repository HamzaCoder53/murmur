package com.bitchat.watch.ui

import com.bitchat.watch.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.ui.theme.BitchatFontFamily
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette

@Composable
fun VerificationCodeScreen(peerID: String) {
    val mesh = WearMeshService.peek()
    val revision by WearPeerIdentityState.revision.collectAsState()
    val identity = androidx.compose.runtime.remember(peerID, revision) {
        WearPeerIdentityState.snapshot(peerID, mesh)
    }
    val myFingerprint = WearPeerIdentityState.myFingerprint(mesh)
    val listState = rememberScalingLazyListState()
    val palette = LocalBitchatPalette.current
    // Icon and title share one state color, so the header reads as a single status.
    val statusColor =
        if (identity.isVerified) MaterialTheme.colorScheme.primary else palette.accentOrange

    // The decision is the screen's edge button: it grows out of the bottom edge once both
    // codes have been scrolled past, the Wear pattern for a list's primary action.
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            FormEdgeButton(
                label = stringResource(if (identity.isVerified) R.string.verify_unverify else R.string.user_verify),
                enabled = identity.fingerprint != null,
                onClick = {
                    WearPeerIdentityState.setVerified(
                        peerID = peerID,
                        verified = !identity.isVerified,
                        mesh = mesh
                    )
                }
            )
        }
    ) { scaffoldPadding ->
        val layoutDirection = LocalLayoutDirection.current
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = scaffoldPadding
                .withAdditionalPadding(
                    layoutDirection = layoutDirection,
                    horizontal = 10.dp
                )
        ) {
            item {
                ListHeader {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = if (identity.isVerified) {
                                Icons.Filled.Verified
                            } else {
                                Icons.Outlined.Warning
                            },
                            contentDescription = null,
                            tint = statusColor
                        )
                        Text(
                            text = stringResource(if (identity.isVerified) R.string.user_verified else R.string.verify_unverified),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.verify_match_in_person),
                    style = ChatVisualTokens.SystemActionStyle,
                    color = palette.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                )
            }

            item {
                FingerprintCard(
                    title = stringResource(R.string.verify_theirs),
                    fingerprint = identity.fingerprint
                )
            }

            item {
                FingerprintCard(
                    title = stringResource(R.string.verify_yours),
                    fingerprint = myFingerprint
                )
            }
        }
    }
}

@Composable
private fun FingerprintCard(
    title: String,
    fingerprint: String?
) {
    val palette = LocalBitchatPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = ChatVisualTokens.SystemActionStyle,
            color = palette.textTertiary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = fingerprint?.let(::formatVerificationCode) ?: stringResource(R.string.verify_pending),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                lineHeight = 16.sp
            ),
            color = if (fingerprint == null) {
                palette.accentOrange
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

fun formatVerificationCode(fingerprint: String): String {
    return fingerprint
        .uppercase()
        .chunked(4)
        .chunked(2)
        .joinToString("\n") { line -> line.joinToString(" ") }
}
