package com.bitchat.desktop.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bitchat.android.backup.ImportMode
import com.bitchat.android.backup.SkeinCrypto
import com.bitchat.android.backup.SkeinFormatException
import com.bitchat.android.linked.LinkCoordinator
import com.bitchat.desktop.link.DesktopBackupService
import com.bitchat.desktop.link.DesktopLinkController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.File

/** Renders [text] as a black-on-white QR code [px] pixels square (ZXing). */
fun qrImage(text: String, px: Int = 280): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        px,
        px,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2),
    )
    val img = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            img.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
        }
    }
    return img.toComposeImageBitmap()
}

@Composable
fun LinkedDevicesScreen(link: DesktopLinkController?, strings: UiStrings, ls: LinkBackupStrings) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(strings.tabLinked, style = MaterialTheme.typography.headlineSmall)
        if (link == null) {
            Text(ls.unavailable)
            return@Column
        }
        val pairing by link.pairing.collectAsState()
        val devices by link.devices.collectAsState()
        val connected by link.connected.collectAsState()
        var qrText by remember { mutableStateOf<String?>(null) }
        var code by remember { mutableStateOf("") }
        var pasted by remember { mutableStateOf("") }
        var note by remember { mutableStateOf<String?>(null) }

        when (val p = pairing) {
            is LinkCoordinator.Pairing.Offering -> {
                val text = qrText
                if (text != null) {
                    Text(ls.showThisQr)
                    Image(remember(text) { qrImage(text) }, contentDescription = ls.showThisQr, Modifier.size(280.dp))
                    SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
                p.code?.let {
                    Text(ls.orTypeCode)
                    Text(it, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace)
                }
                OutlinedButton(onClick = { link.cancelPairing(); qrText = null }) { Text(ls.cancel) }
            }
            is LinkCoordinator.Pairing.AwaitingApproval -> {
                Text("\"${p.deviceName}\" ${ls.approvePrompt}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { link.respondToPairing(true) }) { Text(ls.approve) }
                    OutlinedButton(onClick = { link.respondToPairing(false) }) { Text(ls.deny) }
                }
            }
            LinkCoordinator.Pairing.WaitingForOtherDevice -> Text(ls.waiting)
            LinkCoordinator.Pairing.Transferring -> Text(ls.transferring)
            LinkCoordinator.Pairing.Restarting -> Text(ls.restarting)
            is LinkCoordinator.Pairing.Done -> Text("${ls.done} ${p.deviceName}")
            is LinkCoordinator.Pairing.Failed -> Text("${ls.failed}: ${p.reason}", color = MaterialTheme.colorScheme.error)
            LinkCoordinator.Pairing.Idle -> Unit
        }

        val busy = pairing !is LinkCoordinator.Pairing.Idle &&
            pairing !is LinkCoordinator.Pairing.Done && pairing !is LinkCoordinator.Pairing.Failed
        if (!busy) {
            Button(onClick = { qrText = link.startPairingQr(); if (qrText == null) note = ls.unavailable }) {
                Text(ls.linkNewDevice)
            }
            HorizontalDivider()
            Text(ls.joinTitle, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text(strings.pairWithCode) })
                Button(enabled = code.length == 6, onClick = {
                    note = if (link.joinWithCode(code)) null else ls.unavailable
                    code = ""
                }) { Text(ls.join) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pasted, { pasted = it.take(512) }, Modifier.weight(1f), label = { Text(ls.pasteQrText) })
                Button(enabled = pasted.isNotBlank(), onClick = {
                    note = if (link.joinWithQr(pasted)) null else ls.invalidQr
                    pasted = ""
                }) { Text(ls.joinWithQrText) }
            }
        }
        note?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (devices.isNotEmpty()) {
            HorizontalDivider()
            Text(ls.linkedDevices, style = MaterialTheme.typography.titleMedium)
            devices.forEach { d ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${d.name} — ${if (d.deviceId in connected) ls.connected else ls.notConnected}")
                    OutlinedButton(onClick = { link.unlink(d.deviceId) }) { Text(ls.unlink) }
                }
            }
            OutlinedButton(onClick = { link.leaveGroup() }) { Text(ls.leaveGroup) }
        }
    }
}

@Composable
fun BackupsScreen(
    context: Context,
    nickname: () -> String,
    strings: UiStrings,
    ls: LinkBackupStrings,
    onIdentityRestored: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var exportScope by remember { mutableStateOf(DesktopBackupService.Scope.CHATS_ONLY) }
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var importMode by remember { mutableStateOf(ImportMode.MERGE) }
    var importPass by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(strings.tabBackups, style = MaterialTheme.typography.headlineSmall)

        Text(ls.exportScope, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = exportScope == DesktopBackupService.Scope.CHATS_ONLY,
                onClick = { exportScope = DesktopBackupService.Scope.CHATS_ONLY },
                label = { Text(ls.chatsOnly) },
            )
            FilterChip(
                selected = exportScope == DesktopBackupService.Scope.CHATS_AND_IDENTITY,
                onClick = { exportScope = DesktopBackupService.Scope.CHATS_AND_IDENTITY },
                label = { Text(ls.chatsAndIdentity) },
            )
        }
        if (exportScope == DesktopBackupService.Scope.CHATS_AND_IDENTITY) {
            Text(ls.identityWarning, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(pass, { pass = it }, label = { Text(ls.passphrase) }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(confirm, { confirm = it }, label = { Text(ls.confirmPassphrase) }, visualTransformation = PasswordVisualTransformation())
        val exportProblem = when {
            pass.length < SkeinCrypto.MIN_PASSPHRASE_LENGTH -> ls.passphraseTooShort
            pass != confirm -> ls.passphraseMismatch
            else -> null
        }
        if (pass.isNotEmpty() && exportProblem != null) Text(exportProblem, color = MaterialTheme.colorScheme.error)
        Button(enabled = !working && exportProblem == null, onClick = {
            val file = chooseFile(strings.exportBackup, FileDialog.SAVE, "skein-backup.skein") ?: return@Button
            val passphrase = pass
            val chosenScope = exportScope
            working = true
            scope.launch {
                status = try {
                    withContext(Dispatchers.IO) {
                        DesktopBackupService.exportCurrent(context, nickname(), file, passphrase, chosenScope)
                    }
                    pass = ""
                    confirm = ""
                    ls.exported
                } catch (e: Exception) {
                    "${ls.failed}: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    working = false
                }
            }
        }) { Text(strings.exportBackup) }

        HorizontalDivider()
        Text(ls.importMode, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = importMode == ImportMode.MERGE, onClick = { importMode = ImportMode.MERGE }, label = { Text(ls.merge) })
            FilterChip(selected = importMode == ImportMode.REPLACE, onClick = { importMode = ImportMode.REPLACE }, label = { Text(ls.replace) })
        }
        if (importMode == ImportMode.REPLACE) Text(ls.replaceWarning, color = MaterialTheme.colorScheme.error)
        OutlinedTextField(importPass, { importPass = it }, label = { Text(ls.passphrase) }, visualTransformation = PasswordVisualTransformation())
        OutlinedButton(enabled = !working && importPass.isNotEmpty(), onClick = {
            val file = chooseFile(strings.importBackup, FileDialog.LOAD, null) ?: return@OutlinedButton
            val passphrase = importPass
            val mode = importMode
            working = true
            scope.launch {
                var restart = false
                status = try {
                    val result = withContext(Dispatchers.IO) {
                        DesktopBackupService.importArchive(
                            archiveFile = file,
                            passphrase = passphrase,
                            mode = mode,
                            target = DesktopBackupService.AppStateTarget(context),
                            stagingDir = File(context.cacheDir, "import_staging"),
                        )
                    }
                    importPass = ""
                    restart = result.identityRestored
                    if (restart) ls.restartNeeded else "${ls.imported}: ${result.added}"
                } catch (e: SkeinFormatException) {
                    ls.wrongPassphrase
                } catch (e: Exception) {
                    "${ls.failed}: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    working = false
                }
                if (restart) onIdentityRestored()
            }
        }) { Text(strings.importBackup) }

        status?.let { Text(it) }
    }
}

private fun chooseFile(title: String, mode: Int, suggestedName: String?): File? {
    val dialog = FileDialog(null as Frame?, title, mode)
    if (suggestedName != null) dialog.file = suggestedName
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(dir, name)
}
