package com.bitchat.android.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.ui.AboutCardShape
import com.bitchat.android.ui.AboutHorizontalPadding
import com.bitchat.android.ui.AboutSectionLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class BackupDialog { NONE, EXPORT, IMPORT, SEND, RECEIVE }

/**
 * "Backup & transfer" card for the settings sheet: export, import, move to a new phone, and
 * receive from an old phone. [onWipeThisPhone] runs the panic wipe after a successful move.
 */
@Composable
fun BackupSettingsSection(onWipeThisPhone: (() -> Unit)?) {
    var dialog by rememberSaveable { mutableStateOf(BackupDialog.NONE) }
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(top = 12.dp)) {
        AboutSectionLabel(text = stringResource(R.string.backup_section_title))
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AboutHorizontalPadding),
            color = colorScheme.surface,
            shape = AboutCardShape
        ) {
            Column {
                BackupRow(Icons.Filled.Save, stringResource(R.string.backup_export_title), stringResource(R.string.backup_export_desc)) {
                    dialog = BackupDialog.EXPORT
                }
                RowDivider()
                BackupRow(Icons.Filled.CloudDownload, stringResource(R.string.backup_import_title), stringResource(R.string.backup_import_desc)) {
                    dialog = BackupDialog.IMPORT
                }
                RowDivider()
                BackupRow(Icons.Filled.PhoneAndroid, stringResource(R.string.transfer_send_title), stringResource(R.string.transfer_send_desc)) {
                    dialog = BackupDialog.SEND
                }
                RowDivider()
                BackupRow(Icons.Filled.QrCodeScanner, stringResource(R.string.transfer_receive_title), stringResource(R.string.transfer_receive_desc)) {
                    dialog = BackupDialog.RECEIVE
                }
            }
        }
    }
    val close = { dialog = BackupDialog.NONE }
    when (dialog) {
        BackupDialog.EXPORT -> ExportDialog(onDismiss = close)
        BackupDialog.IMPORT -> ImportDialog(onDismiss = close)
        BackupDialog.SEND -> SendToNewPhoneDialog(onDismiss = close)
        BackupDialog.RECEIVE -> ReceiveFromOldPhoneDialog(onDismiss = close)
        BackupDialog.NONE -> Unit
    }
    BackupStatusDialog(onWipeThisPhone = onWipeThisPhone)
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 54.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun BackupRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, color = colorScheme.onSurface)
            Text(subtitle, fontSize = 12.sp, color = colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ChoiceRow(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title)
            subtitle?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun PassphraseField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
}

internal fun defaultBackupFileName(now: Date = Date()): String =
    "skein-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now) + ".skein"

@Composable
internal fun rememberCreateDocument(onUri: (android.net.Uri) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(onUri)
    }

@Composable
internal fun rememberOpenDocument(onUri: (android.net.Uri) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onUri)
    }

@Composable
internal fun rememberPassphrase() = remember { mutableStateOf("") }
