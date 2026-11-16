package com.bitchat.android.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import com.bitchat.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Runs backup, restore and phone-move jobs outside any composable, so closing a dialog does not
 * cancel a half-written file. One job at a time.
 */
object BackupController {
    private const val TAG = "BackupController"

    sealed interface State {
        data object Idle : State
        data class Working(@StringRes val label: Int, val done: Long = 0, val total: Long = 0) : State
        /** Old phone: waiting for the new phone to scan [qr]. */
        data class WaitingForNewPhone(
            val qr: String,
            val code: String,
            val expiresAt: Long,
            /** 6-digit code to type on the new phone instead of scanning; null once spent. */
            val passkey: String? = null
        ) : State
        data class Done(
            @StringRes val message: Int,
            val added: Int = 0,
            val skipped: Int = 0,
            /** Old phone after a successful move: offer to wipe it. */
            val offerWipe: Boolean = false,
            /** New identity stored: the app restarts when the user confirms. */
            val restartRequired: Boolean = false
        ) : State
        data class Failed(@StringRes val message: Int) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    fun reset() {
        if (!isBusy) _state.value = State.Idle
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    private fun launch(block: suspend () -> Unit) {
        if (isBusy) return
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Backup job failed: ${e.javaClass.simpleName}")
                _state.value = State.Failed(errorMessage(e))
            }
        }
    }

    @StringRes
    fun errorMessage(e: Exception): Int = when {
        e is SkeinFormatException && e.wrongKey -> R.string.backup_error_wrong_passphrase
        e is TransferAuthException -> R.string.transfer_error_auth
        e is SkeinFormatException -> R.string.backup_error_damaged
        else -> R.string.backup_error_generic
    }

    private fun progress(@StringRes label: Int) = ProgressListener { done, total ->
        _state.value = State.Working(label, done, total)
    }

    // --- Export / import ---

    fun export(context: Context, uri: Uri, includeIdentity: Boolean, passphrase: CharArray) = launch {
        val app = context.applicationContext
        try {
            _state.value = State.Working(R.string.backup_progress_collecting)
            val content = DeviceBackup.buildContent(app, includeIdentity, SkeinArchive.Kind.EXPORT)
            if (content.conversations.isEmpty() && content.identity == null) {
                _state.value = State.Failed(R.string.backup_error_nothing_to_export)
                return@launch
            }
            DeviceBackup.exportToUri(
                app, uri, content, SkeinCrypto.KeySource.Passphrase(passphrase), progress(R.string.backup_progress_writing)
            )
            _state.value = State.Done(R.string.backup_export_done, added = content.conversations.size)
        } finally {
            passphrase.fill(' ')
        }
    }

    fun import(context: Context, uri: Uri, passphrase: CharArray, mode: ImportMode) = launch {
        val app = context.applicationContext
        val archive = try {
            DeviceBackup.readFromUri(
                app, uri, SkeinCrypto.KeySource.Passphrase(passphrase), progress(R.string.backup_progress_reading)
            )
        } finally {
            passphrase.fill(' ')
        }
        applyArchive(app, archive, mode)
    }

    private suspend fun applyArchive(app: Context, archive: ImportedArchive, mode: ImportMode) {
        _state.value = State.Working(R.string.backup_progress_restoring)
        val result = BackupImporter.apply(archive, mode, DeviceBackupTarget(app), DeviceBackup.stagingDir(app))
        _state.value = State.Done(
            message = if (result.identityRestored) R.string.backup_import_done_identity else R.string.backup_import_done,
            added = result.added,
            skipped = result.skipped,
            restartRequired = result.identityRestored
        )
    }

    fun restartNow(context: Context) = DeviceBackup.restartApp(context)

    // --- Phone move: old phone ---

    fun startSending(context: Context) = launch {
        val app = context.applicationContext
        val archiveFile = DeviceBackup.transferFile(app, "outgoing.skein")
        var server: ServerSocket? = null
        try {
            val addresses = TransferNetwork.localAddresses(app)
            if (addresses.isEmpty()) {
                _state.value = State.Failed(R.string.transfer_error_no_network)
                return@launch
            }
            val listener = withContext(Dispatchers.IO) { ServerSocket(0) }
            server = listener
            val port = listener.localPort
            val invitation = TransferProtocol.newInvitation(addresses.map { "${it.hostAddress}:$port" })
            _state.value = State.Working(R.string.backup_progress_collecting)
            val content = DeviceBackup.buildContent(app, includeIdentity = true, kind = SkeinArchive.Kind.TRANSFER)
            DeviceBackup.exportToFile(
                archiveFile, content,
                SkeinCrypto.KeySource.RawKey(TransferProtocol.archiveKey(invitation.key)),
                progress(R.string.backup_progress_writing)
            )
            var passkey: String? = com.bitchat.android.linked.PasskeyPairing.newCode()
            _state.value = State.WaitingForNewPhone(
                invitation.encode(), invitation.code, System.currentTimeMillis() + TransferProtocol.LISTEN_WINDOW_MS,
                passkey
            )
            val stopAdvertising = TransferCode.advertise(app, port)
            val ok = try {
                TransferNetwork.serve(
                    invitation, listener, addresses.toSet<InetAddress>(), archiveFile,
                    onConnected = { _state.value = State.Working(R.string.transfer_progress_sending) },
                    progress = progress(R.string.transfer_progress_sending),
                    passkeyCode = { passkey },
                    onCodeFailed = {
                        // One try per code: a wrong one is spent; the QR code still works.
                        passkey = null
                        (_state.value as? State.WaitingForNewPhone)?.let { _state.value = it.copy(passkey = null) }
                    }
                )
            } finally {
                stopAdvertising()
            }
            _state.value = if (ok) {
                State.Done(R.string.transfer_send_done, offerWipe = true)
            } else {
                State.Failed(R.string.transfer_error_not_completed)
            }
        } finally {
            try { server?.close() } catch (_: IOException) { }
            archiveFile.delete()
        }
    }

    // --- Phone move: new phone ---

    /** New phone, typing the 6-digit code shown on the old phone (same Wi-Fi). */
    fun receiveWithCode(context: Context, code: String) = launch {
        val app = context.applicationContext
        _state.value = State.Working(R.string.transfer_progress_connecting)
        val found = TransferCode.find(app, code)
        if (found == null) {
            _state.value = State.Failed(R.string.transfer_error_code)
            return@launch
        }
        val (invitation, socket) = found
        receiveOver(app, invitation, socket)
    }

    fun receive(context: Context, scanned: String) = launch {
        val app = context.applicationContext
        val invitation = TransferProtocol.parseInvitation(scanned)
        if (invitation == null) {
            _state.value = State.Failed(R.string.transfer_error_bad_qr)
            return@launch
        }
        _state.value = State.Working(R.string.transfer_progress_connecting)
        val socket = TransferNetwork.connect(app, invitation)
        receiveOver(app, invitation, socket)
    }

    private suspend fun receiveOver(app: Context, invitation: TransferProtocol.Invitation, socket: java.net.Socket) {
        val file = DeviceBackup.transferFile(app, "incoming.skein")
        try {
            val archive = withContext(Dispatchers.IO) {
                socket.use { s ->
                    TransferProtocol.receive(
                        s.getInputStream(), s.getOutputStream(), invitation, file,
                        progress(R.string.transfer_progress_receiving)
                    ) { received ->
                        _state.value = State.Working(R.string.backup_progress_reading)
                        received.inputStream().use {
                            SkeinArchive.read(
                                it,
                                SkeinCrypto.KeySource.RawKey(TransferProtocol.archiveKey(invitation.key)),
                                DeviceBackup.stagingDir(app)
                            )
                        }
                    }
                }
            }
            applyArchive(app, archive, ImportMode.REPLACE)
        } finally {
            file.delete()
        }
    }
}
