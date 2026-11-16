package com.bitchat.android.backup

import androidx.compose.ui.Alignment
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.lan.LanController
import com.bitchat.android.ui.ScannerView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

/** Asks for Android 17's local network permission (if needed) before [action]. */
@Composable
private fun rememberWithLocalNetwork(action: () -> Unit): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            denied = false
            action()
        } else {
            denied = true
        }
    }
    return denied to {
        if (LanController.hasPermissions(context)) action()
        else launcher.launch(LanController.requiredPermissions().toTypedArray())
    }
}

/** Old phone: Settings → Move to a new phone. */
@Composable
internal fun SendToNewPhoneDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val (denied, start) = rememberWithLocalNetwork {
        BackupController.startSending(context)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transfer_send_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.transfer_send_explanation))
                Text(stringResource(R.string.transfer_network_note), fontSize = 12.sp)
                Text(stringResource(R.string.backup_identity_warning), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                if (denied) {
                    Text(stringResource(R.string.lan_mesh_permission_denied), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !BackupController.isBusy, onClick = start) {
                Text(stringResource(R.string.transfer_send_start))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** New phone: Settings → Transfer from old phone. Confirm, then scan the old phone's code. */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
internal fun ReceiveFromOldPhoneDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var typedCode by remember { mutableStateOf("") }
    val camera = rememberPermissionState(Manifest.permission.CAMERA)
    val (_, beginTyping) = rememberWithLocalNetwork { typing = true }
    val (denied, beginScan) = rememberWithLocalNetwork {
        scanning = true
        if (!camera.status.isGranted) camera.launchPermissionRequest()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transfer_receive_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (typing) {
                    Text(stringResource(R.string.transfer_code_entry_hint))
                    androidx.compose.material3.OutlinedTextField(
                        value = typedCode,
                        onValueChange = { typedCode = it.filter(Char::isDigit).take(6) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (!scanning) {
                    Text(stringResource(R.string.transfer_scan_instructions))
                    Text(stringResource(R.string.transfer_network_note), fontSize = 12.sp)
                    Text(stringResource(R.string.transfer_receive_warning), color = MaterialTheme.colorScheme.error)
                    if (denied) {
                        Text(stringResource(R.string.lan_mesh_permission_denied), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                } else if (camera.status.isGranted) {
                    Box(modifier = Modifier.fillMaxWidth().height(280.dp).clipToBounds()) {
                        ScannerView { text ->
                            if (TransferProtocol.parseInvitation(text) != null) {
                                scanning = false
                                BackupController.receive(context, text)
                                onDismiss()
                            }
                        }
                    }
                } else {
                    Text(stringResource(R.string.transfer_camera_permission))
                    TextButton(onClick = { camera.launchPermissionRequest() }) {
                        Text(stringResource(R.string.transfer_grant))
                    }
                }
            }
        },
        confirmButton = {
            if (typing) {
                TextButton(
                    enabled = !BackupController.isBusy && typedCode.length == 6,
                    onClick = {
                        BackupController.receiveWithCode(context, typedCode)
                        onDismiss()
                    }
                ) { Text(stringResource(R.string.transfer_code_start), color = MaterialTheme.colorScheme.error) }
            } else if (!scanning) {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(enabled = !BackupController.isBusy, onClick = beginScan) {
                        Text(stringResource(R.string.transfer_scan_button), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(enabled = !BackupController.isBusy, onClick = beginTyping) {
                        Text(stringResource(R.string.transfer_enter_code))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** First-launch entry point: lets a user transfer chats and identity from an old phone. */
@Composable
fun TransferFromOldPhoneOnboardingButton() {
    var showDialog by remember { mutableStateOf(false) }
    TextButton(
        onClick = { showDialog = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.transfer_receive_title))
    }
    if (showDialog) {
        ReceiveFromOldPhoneDialog(onDismiss = { showDialog = false })
    }
    BackupStatusDialog(onWipeThisPhone = null)
}
