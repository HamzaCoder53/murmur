package com.bitchat.android.backup

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.bitchat.android.R
import com.bitchat.android.services.MessageHistory
import com.bitchat.android.ui.QRCodeImage
import kotlinx.coroutines.delay

/** Settings → Export chats. */
@Composable
internal fun ExportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var withIdentity by remember { mutableStateOf(false) }
    var passphrase by rememberPassphrase()
    var repeat by rememberPassphrase()
    val historyOn by MessageHistory.enabled.collectAsState()
    val tooShort = passphrase.length < SkeinCrypto.MIN_PASSPHRASE_LENGTH
    val mismatch = passphrase != repeat
    val launcher = rememberCreateDocument { uri: Uri ->
        BackupController.export(context, uri, withIdentity, passphrase.toCharArray())
        passphrase = ""
        repeat = ""
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_export_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChoiceRow(!withIdentity, stringResource(R.string.backup_export_chats_only), null) { withIdentity = false }
                ChoiceRow(withIdentity, stringResource(R.string.backup_export_with_identity), null) { withIdentity = true }
                if (withIdentity) {
                    Text(
                        stringResource(R.string.backup_identity_warning),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (!historyOn) {
                    Text(stringResource(R.string.backup_history_off_note), fontSize = 12.sp)
                }
                PassphraseField(passphrase, stringResource(R.string.backup_passphrase)) { passphrase = it }
                PassphraseField(repeat, stringResource(R.string.backup_passphrase_repeat)) { repeat = it }
                when {
                    passphrase.isNotEmpty() && tooShort ->
                        Text(stringResource(R.string.backup_passphrase_too_short), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    repeat.isNotEmpty() && mismatch ->
                        Text(stringResource(R.string.backup_passphrase_mismatch), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !tooShort && !mismatch && !BackupController.isBusy,
                onClick = { launcher.launch(defaultBackupFileName()) }
            ) { Text(stringResource(R.string.backup_export_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Settings → Import chats. */
@Composable
internal fun ImportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var uri by remember { mutableStateOf<Uri?>(null) }
    var passphrase by rememberPassphrase()
    var mode by remember { mutableStateOf(ImportMode.MERGE) }
    var confirmReplace by remember { mutableStateOf(false) }
    val launcher = rememberOpenDocument { uri = it }
    val start = {
        uri?.let { BackupController.import(context, it, passphrase.toCharArray(), mode) }
        passphrase = ""
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_import_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { launcher.launch(arrayOf("*/*")) }) {
                    Text(
                        if (uri == null) stringResource(R.string.backup_choose_file)
                        else stringResource(R.string.backup_file_chosen)
                    )
                }
                PassphraseField(passphrase, stringResource(R.string.backup_passphrase)) { passphrase = it }
                ChoiceRow(
                    mode == ImportMode.MERGE,
                    stringResource(R.string.backup_import_merge),
                    stringResource(R.string.backup_import_merge_desc)
                ) { mode = ImportMode.MERGE }
                ChoiceRow(
                    mode == ImportMode.REPLACE,
                    stringResource(R.string.backup_import_replace),
                    stringResource(R.string.backup_import_replace_desc)
                ) { mode = ImportMode.REPLACE }
            }
        },
        confirmButton = {
            TextButton(
                enabled = uri != null && passphrase.isNotEmpty() && !BackupController.isBusy,
                onClick = { if (mode == ImportMode.REPLACE) confirmReplace = true else start() }
            ) { Text(stringResource(R.string.backup_import_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(stringResource(R.string.backup_replace_confirm_title)) },
            text = { Text(stringResource(R.string.backup_replace_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { confirmReplace = false; start() }) {
                    Text(stringResource(R.string.backup_replace_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

/** Progress, QR code while waiting, and results of whatever [BackupController] is doing. */
@Composable
internal fun BackupStatusDialog(onWipeThisPhone: (() -> Unit)?) {
    val context = LocalContext.current
    val state by BackupController.state.collectAsState()
    var confirmWipe by remember { mutableStateOf(false) }
    when (val s = state) {
        BackupController.State.Idle -> Unit
        is BackupController.State.Working -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(s.label)) },
            text = {
                if (s.total > 0) {
                    LinearProgressIndicator(
                        progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = BackupController::cancel) { Text(stringResource(R.string.cancel)) } }
        )
        is BackupController.State.WaitingForNewPhone -> {
            var secondsLeft by remember(s.expiresAt) { mutableStateOf(((s.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)) }
            LaunchedEffect(s.expiresAt) {
                while (secondsLeft > 0) {
                    delay(1000)
                    secondsLeft = ((s.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                }
            }
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnClickOutside = false),
                title = { Text(stringResource(R.string.transfer_send_title)) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QRCodeImage(data = s.qr, size = 240.dp)
                        Text(stringResource(R.string.transfer_waiting_code, s.code), fontWeight = FontWeight.Bold)
                        s.passkey?.let { passkey ->
                            Text(stringResource(R.string.transfer_passkey_hint), fontSize = 13.sp)
                            Text(
                                passkey.chunked(3).joinToString(" "),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                        Text(
                            stringResource(R.string.transfer_waiting_note, (secondsLeft / 60).toInt(), (secondsLeft % 60).toInt()),
                            fontSize = 13.sp
                        )
                        Text(stringResource(R.string.transfer_network_note), fontSize = 12.sp)
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = BackupController::cancel) { Text(stringResource(R.string.cancel)) } }
            )
        }
        is BackupController.State.Done -> AlertDialog(
            onDismissRequest = { if (!s.restartRequired) BackupController.reset() },
            properties = DialogProperties(dismissOnClickOutside = !s.restartRequired, dismissOnBackPress = !s.restartRequired),
            text = {
                Text(
                    when (s.message) {
                        R.string.backup_import_done -> stringResource(s.message, s.added, s.skipped)
                        R.string.backup_import_done_identity -> stringResource(s.message, s.added)
                        else -> stringResource(s.message)
                    }
                )
            },
            confirmButton = {
                if (s.restartRequired) {
                    TextButton(onClick = { BackupController.restartNow(context) }) { Text(stringResource(R.string.backup_restart_now)) }
                } else {
                    TextButton(onClick = BackupController::reset) { Text(stringResource(R.string.backup_close)) }
                }
            },
            dismissButton = if (s.offerWipe && onWipeThisPhone != null) {
                {
                    TextButton(onClick = { confirmWipe = true }) {
                        Text(stringResource(R.string.transfer_wipe_this_phone), color = MaterialTheme.colorScheme.error)
                    }
                }
            } else null
        )
        is BackupController.State.Failed -> AlertDialog(
            onDismissRequest = BackupController::reset,
            text = { Text(stringResource(s.message)) },
            confirmButton = { TextButton(onClick = BackupController::reset) { Text(stringResource(R.string.backup_close)) } }
        )
    }
    if (confirmWipe && onWipeThisPhone != null) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(stringResource(R.string.transfer_wipe_confirm_title)) },
            text = { Text(stringResource(R.string.transfer_wipe_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    BackupController.reset()
                    onWipeThisPhone()
                }) { Text(stringResource(R.string.transfer_wipe_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}
