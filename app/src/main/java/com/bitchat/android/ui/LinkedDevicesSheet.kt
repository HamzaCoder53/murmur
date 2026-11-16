package com.bitchat.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.linked.LinkController
import com.bitchat.android.linked.LinkCoordinator
import com.bitchat.android.linked.LinkRole
import com.bitchat.android.linked.LinkedDevice
import com.bitchat.android.ui.theme.BitchatFontFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedDevicesSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit
) {
    if (!isPresented) return

    val context = LocalContext.current
    LaunchedEffect(Unit) { LinkController.initialize(context) }

    val coordinator = LinkController.coordinator
    val devices by (coordinator?.devices?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptyList()) })
    val connected by (coordinator?.connected?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptySet()) })
    val role by (coordinator?.role?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<LinkRole>(LinkRole.Standalone) })
    val pairing by (coordinator?.pairing?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<LinkCoordinator.Pairing>(LinkCoordinator.Pairing.Idle) })

    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    var qrPayload by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var pendingUnlink by remember { mutableStateOf<LinkedDevice?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }

    val cameraPermissionText = androidx.compose.ui.res.stringResource(R.string.linked_devices_camera_permission)
    val invalidQrText = androidx.compose.ui.res.stringResource(R.string.linked_devices_invalid_qr)
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanning = true
            scanError = null
        } else {
            scanError = cameraPermissionText
        }
    }

    BitchatBottomSheet(onDismissRequest = {
        coordinator?.cancelPairing()
        onDismiss()
    }) {
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 64.dp, bottom = 32.dp, start = 20.dp, end = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.linked_devices_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.linked_devices_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (devices.isNotEmpty()) {
                    item(key = "role") {
                        val roleLabel = when (role) {
                            LinkRole.Leader -> stringResource(R.string.linked_devices_role_leader)
                            is LinkRole.Follower -> stringResource(R.string.linked_devices_role_follower)
                            LinkRole.Standalone -> stringResource(R.string.linked_devices_role_standalone)
                        }
                        Surface(
                            color = colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Devices,
                                    contentDescription = null,
                                    tint = colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = roleLabel,
                                    fontFamily = BitchatFontFamily,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                if (scanning) {
                    item(key = "scanner") {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.linked_devices_scan_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    color = colorScheme.onSurfaceVariant
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(260.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                ) {
                                    ScannerView { raw ->
                                        if (LinkController.joinWithQr(context, raw)) {
                                            scanning = false
                                            scanError = null
                                        } else {
                                            scanError = invalidQrText
                                        }
                                    }
                                }
                                TextButton(onClick = { scanning = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        }
                    }
                }

                if (scanError != null) {
                    item(key = "scan_error") {
                        Text(
                            text = scanError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.error
                        )
                    }
                }

                if (pairing !is LinkCoordinator.Pairing.Idle) {
                    item(key = "pairing") {
                        PairingCard(
                            state = pairing,
                            qrPayload = qrPayload,
                            onApprove = { coordinator?.respondToPairing(true) },
                            onDeny = { coordinator?.respondToPairing(false) },
                            onClose = {
                                qrPayload = null
                                coordinator?.cancelPairing()
                            }
                        )
                    }
                }

                if (devices.isEmpty()) {
                    item(key = "empty") {
                        Surface(
                            color = colorScheme.surface,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.linked_devices_subtitle_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(devices, key = { it.deviceId }) { device ->
                        val isOnline = device.deviceId in connected
                        LinkedDeviceRow(
                            device = device,
                            online = isOnline,
                            onUnlink = { pendingUnlink = device }
                        )
                    }
                }

                item(key = "actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                scanning = false
                                scanError = null
                                qrPayload = LinkController.startPairingQr(context)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.linked_devices_link_new))
                        }

                        OutlinedButton(
                            onClick = {
                                qrPayload = null
                                coordinator?.cancelPairing()
                                val hasCam = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasCam) {
                                    scanning = !scanning
                                    scanError = null
                                } else {
                                    cameraLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.linked_devices_link_other))
                        }

                        if (devices.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            OutlinedButton(
                                onClick = { confirmLeave = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.linked_devices_leave_button))
                            }
                        }
                    }
                }
            }

            CloseButton(
                onClick = {
                    coordinator?.cancelPairing()
                    onDismiss()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )
        }
    }

    pendingUnlink?.let { target ->
        val name = target.name.ifBlank { target.deviceId.take(8) }
        AlertDialog(
            onDismissRequest = { pendingUnlink = null },
            title = { Text(stringResource(R.string.linked_devices_unlink_confirm_title, name)) },
            text = { Text(stringResource(R.string.linked_devices_unlink_confirm_message, name)) },
            confirmButton = {
                Button(
                    onClick = {
                        val id = target.deviceId
                        pendingUnlink = null
                        scope.launch { coordinator?.unlinkDevice(id) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error)
                ) {
                    Text(stringResource(R.string.linked_devices_unlink))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnlink = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.linked_devices_leave_confirm_title)) },
            text = { Text(stringResource(R.string.linked_devices_leave_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmLeave = false
                        scope.launch { coordinator?.leaveGroup() }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error)
                ) {
                    Text(stringResource(R.string.linked_devices_leave_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun LinkedDeviceRow(
    device: LinkedDevice,
    online: Boolean,
    onUnlink: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val subtitle = if (online) {
        stringResource(R.string.linked_devices_connected)
    } else if (device.lastSeen > 0L) {
        val rel = DateUtils.getRelativeTimeSpanString(
            device.lastSeen,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
        stringResource(R.string.linked_devices_last_seen, rel)
    } else {
        device.deviceId.take(8)
    }

    Surface(
        color = colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = if (online) Color(0xFF2E7D32) else colorScheme.outline,
                        shape = CircleShape
                    )
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name.ifBlank { device.deviceId.take(8) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onUnlink) {
                Text(
                    text = stringResource(R.string.linked_devices_unlink),
                    color = colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun PairingCard(
    state: LinkCoordinator.Pairing,
    qrPayload: String?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onClose: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        color = colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (state) {
                is LinkCoordinator.Pairing.Offering -> {
                    if (!qrPayload.isNullOrBlank()) {
                        Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                            Box(modifier = Modifier.padding(12.dp)) {
                                QRCodeImage(data = qrPayload, size = 210.dp)
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.linked_devices_qr_hint),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                is LinkCoordinator.Pairing.AwaitingApproval -> {
                    Text(
                        text = stringResource(R.string.linked_devices_approve_title, state.deviceName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.linked_devices_approve_message, state.deviceName),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onDeny) {
                            Text(stringResource(R.string.linked_devices_deny))
                        }
                        Button(onClick = onApprove) {
                            Text(stringResource(R.string.linked_devices_allow))
                        }
                    }
                }
                LinkCoordinator.Pairing.WaitingForOtherDevice -> {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(
                        text = stringResource(R.string.linked_devices_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
                LinkCoordinator.Pairing.Transferring -> {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(
                        text = stringResource(R.string.linked_devices_transferring),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is LinkCoordinator.Pairing.Done -> {
                    Text(
                        text = stringResource(R.string.linked_devices_done, state.deviceName),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.primary
                    )
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.dismiss))
                    }
                }
                LinkCoordinator.Pairing.Restarting -> {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(
                        text = stringResource(R.string.linked_devices_restarting),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is LinkCoordinator.Pairing.Failed -> {
                    Text(
                        text = stringResource(R.string.linked_devices_failed, state.reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.dismiss))
                    }
                }
                LinkCoordinator.Pairing.Idle -> Unit
            }
        }
    }
}
