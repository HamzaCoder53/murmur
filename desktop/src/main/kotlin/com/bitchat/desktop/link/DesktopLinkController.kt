package com.bitchat.desktop.link

import com.bitchat.android.linked.LinkCommand
import com.bitchat.android.linked.LinkCoordinator
import com.bitchat.android.linked.LinkQr
import com.bitchat.android.linked.LinkRole
import com.bitchat.android.linked.LinkSocketEngine
import com.bitchat.android.linked.LinkStore
import com.bitchat.android.linked.LinkedAppBridge
import com.bitchat.android.linked.PasskeyPairing
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.AppStateStore
import com.bitchat.desktop.core.DesktopMeshNode
import com.bitchat.desktop.platform.LocalDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.Socket
import java.util.Date
import java.util.UUID

/**
 * Desktop owner of linked devices: the counterpart of the phone's LinkController + LinkTransport.
 * Runs the shared [LinkCoordinator] over the shared [LinkSocketEngine] (TCP, pairing, redial) and
 * announces/finds `_skeinlink._tcp` through [discovery] (JmDNS in the app).
 */
class DesktopLinkController(
    private val scope: CoroutineScope,
    private val store: LinkStore,
    bridge: LinkedAppBridge,
    private val discovery: LocalDiscovery,
    newSocket: () -> Socket = { Socket() },
) {
    val coordinator = LinkCoordinator(scope, store, bridge)
    private val engine = LinkSocketEngine(scope, store, coordinator, newSocket)

    private var advert: AutoCloseable? = null
    private var browser: AutoCloseable? = null

    val pairing get() = coordinator.pairing
    val devices get() = coordinator.devices
    val connected get() = coordinator.connected
    val role get() = coordinator.role
    val listeningPort: Int? get() = engine.listeningPort

    /** Starts only when there is something to do (links, pending wipes, an offer). */
    @Synchronized
    fun startIfNeeded() {
        if (coordinator.hasWork()) ensureRunning()
    }

    @Synchronized
    private fun ensureRunning(): Boolean {
        coordinator.start()
        if (engine.isRunning()) return true
        val port = engine.start() ?: return false
        try {
            advert = discovery.advertise(LinkSocketEngine.SERVICE_TYPE, engine.serviceName, port)
        } catch (_: Exception) {
            // No LAN: QR codes still carry the address, so pairing by QR keeps working.
        }
        try {
            browser = discovery.browse(
                LinkSocketEngine.SERVICE_TYPE,
                onFound = { s -> engine.onServiceResolved(s.name, s.host, s.port) },
                onLost = { name -> engine.onServiceLost(name) },
            )
        } catch (_: Exception) {
        }
        return true
    }

    @Synchronized
    fun stop() {
        try { advert?.close() } catch (_: Exception) { }
        try { browser?.close() } catch (_: Exception) { }
        advert = null
        browser = null
        engine.stop()
        coordinator.stop()
    }

    /** Start offering a link and return the QR payload text to show (null if it cannot listen). */
    fun startPairingQr(): String? {
        if (!ensureRunning()) return null
        val secret = coordinator.startPairingOffer()
        return LinkQr.encode(
            deviceId = coordinator.myDeviceId,
            secret = secret,
            host = engine.localHostHint(),
            port = engine.listeningPort,
        )
    }

    fun offeredCode(): String? = coordinator.offeredCode()

    /** Join an existing device from its QR payload text (pasted or scanned). */
    fun joinWithQr(rawQr: String): Boolean {
        val payload = LinkQr.parse(rawQr) ?: return false
        if (payload.deviceId == coordinator.myDeviceId) return false
        if (!ensureRunning()) return false
        engine.connectForPairing(payload)
        return true
    }

    /** Join an existing device on the same network by typing its 6-digit code. */
    fun joinWithCode(code: String): Boolean {
        if (!PasskeyPairing.isCode(code)) return false
        if (!ensureRunning()) return false
        engine.connectWithCode(code)
        return true
    }

    fun respondToPairing(approve: Boolean) = coordinator.respondToPairing(approve)

    fun cancelPairing() = coordinator.cancelPairing()

    fun unlink(deviceId: String) {
        scope.launch { coordinator.unlinkDevice(deviceId) }
    }

    fun leaveGroup() {
        scope.launch { coordinator.leaveGroup() }
    }

    /** True when the shared identity is driven by another device's radios. */
    fun isFollowing(): Boolean = coordinator.role.value is LinkRole.Follower

    /** While following, hands the message to the leading device; otherwise sends it on [node]. */
    fun sendPublic(node: DesktopMeshNode, text: String) {
        val id = UUID.randomUUID().toString()
        if (coordinator.forward(LinkCommand.SendPublic(id, text, emptyList(), null))) {
            AppStateStore.addPublicMessage(localMessage(node, id, text, private = false, recipient = null))
        } else {
            node.sendPublic(text, id)
        }
    }

    fun sendPrivate(node: DesktopMeshNode, text: String, peerID: String, recipientNickname: String) {
        val id = UUID.randomUUID().toString()
        if (coordinator.forward(LinkCommand.SendPrivate(id, text, peerID, recipientNickname))) {
            AppStateStore.addPrivateMessage(
                peerID,
                localMessage(node, id, text, private = true, recipient = recipientNickname),
                forceRead = true,
            )
        } else {
            node.sendPrivate(text, peerID, recipientNickname, id)
        }
    }

    private fun localMessage(node: DesktopMeshNode, id: String, text: String, private: Boolean, recipient: String?) =
        BitchatMessage(
            id = id,
            sender = node.identity.nickname,
            content = text,
            timestamp = Date(),
            isPrivate = private,
            recipientNickname = recipient,
            senderPeerID = node.peerID,
            deliveryStatus = DeliveryStatus.Sending,
        )
}
