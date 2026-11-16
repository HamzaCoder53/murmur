package com.bitchat.android.linked

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide owner of the linked-devices coordinator and local transport.
 *
 * Follows the same controller pattern as [com.bitchat.android.lan.LanController] and
 * [com.bitchat.android.wearlink.WearLinkController].
 */
object LinkController {
    private const val TAG = "LinkController"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var store: PrefsLinkStore? = null

    @Volatile
    private var bridge: AndroidLinkedAppBridge? = null

    @Volatile
    private var _coordinator: LinkCoordinator? = null
    val coordinator: LinkCoordinator? get() = _coordinator

    @Volatile
    private var transport: LinkTransport? = null

    private val _mirrorPeers = MutableStateFlow<List<LinkPeer>?>(null)
    val mirrorPeers: StateFlow<List<LinkPeer>?> = _mirrorPeers.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (_coordinator != null) return
        val app = context.applicationContext
        try {
            val s = PrefsLinkStore(app)
            val b = AndroidLinkedAppBridge(app) { peers -> _mirrorPeers.value = peers }
            val c = LinkCoordinator(scope, s, b)
            val t = LinkTransport(app, scope, s, c)
            store = s
            bridge = b
            _coordinator = c
            transport = t
            if (c.hasWork()) {
                c.start()
                t.start()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Linked devices unavailable: ${t.message}")
        }
    }

    @Synchronized
    fun startIfNeeded(context: Context) {
        initialize(context)
        val c = _coordinator ?: return
        val t = transport ?: return
        if (c.hasWork()) {
            c.start()
            if (!t.isRunning()) t.start()
        }
    }

    @Synchronized
    fun stop() {
        transport?.stop()
        _coordinator?.stop()
        _mirrorPeers.value = null
    }

    fun isFollowing(): Boolean = _coordinator?.role?.value is LinkRole.Follower

    fun forward(command: LinkCommand, media: ByteArray? = null): Boolean =
        _coordinator?.forward(command, media) == true

    /** Start a pairing offer and return the QR payload string to display. */
    fun startPairingQr(context: Context): String? {
        initialize(context)
        val c = _coordinator ?: return null
        val t = transport ?: return null
        c.start()
        if (!t.isRunning()) t.start()
        val secret = c.startPairingOffer()
        return LinkQr.encode(
            deviceId = c.myDeviceId,
            secret = secret,
            host = t.localHostHint(),
            port = t.listeningPort
        )
    }

    /** Scan a QR payload from an existing device and begin pairing with it. */
    fun joinWithQr(context: Context, rawQr: String): Boolean {
        val payload = LinkQr.parse(rawQr) ?: return false
        initialize(context)
        val c = _coordinator ?: return false
        val t = transport ?: return false
        if (payload.deviceId == c.myDeviceId) return false
        c.start()
        if (!t.isRunning()) t.start()
        t.connectForPairing(payload)
        return true
    }

    /** The 6-digit code shown with the current pairing QR (null when not offering). */
    fun offeredCode(): String? = _coordinator?.offeredCode()

    /** Pair by typing the 6-digit code shown on the existing device (same Wi-Fi). */
    fun joinWithCode(context: Context, code: String): Boolean {
        if (!PasskeyPairing.isCode(code)) return false
        initialize(context)
        val c = _coordinator ?: return false
        val t = transport ?: return false
        c.start()
        if (!t.isRunning()) t.start()
        t.connectWithCode(code)
        return true
    }

    suspend fun panicWipeAll() {
        _coordinator?.panicWipeAll()
    }
}
