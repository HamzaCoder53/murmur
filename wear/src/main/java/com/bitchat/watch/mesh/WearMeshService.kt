package com.bitchat.watch.mesh

import android.app.AlarmManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.mesh.BluetoothConnectionManager
import com.bitchat.android.mesh.BluetoothConnectionManagerDelegate
import com.bitchat.android.mesh.DirectLinkAnnouncementPolicy
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.mesh.PowerManager
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.util.AppConstants
import com.bitchat.android.wearlink.GmsWearLinkChannel
import com.bitchat.android.wearlink.WearLinkChannel
import com.bitchat.android.wearlink.WearLinkContract
import com.bitchat.android.wearlink.WearLinkTransport
import com.bitchat.watch.link.LinkMode
import com.bitchat.watch.link.LinkModeController
import com.bitchat.watch.link.LinkedPhoneClient
import com.bitchat.watch.link.NoTransport
import com.bitchat.watch.internet.WatchNostrClient
import com.bitchat.watch.link.SwitchingTransport
import com.bitchat.watch.rendezvous.AndroidRendezvousPlatform
import com.bitchat.watch.rendezvous.MeshClock
import com.bitchat.watch.rendezvous.RendezvousAlarmReceiver
import com.bitchat.watch.rendezvous.RendezvousEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Watch mesh service: composes the shared mesh coordinator (MeshCore) with two ways of reaching
 * the mesh and switches between them.
 *
 *  - Standalone: the watch's own BLE transport (BluetoothConnectionManager), scanning in
 *    rendezvous windows while the app is in the background.
 *  - Tethered: a tunnel through the paired phone over the Wearable Data Layer, with the watch's
 *    own mesh radio switched off.
 *
 * Either way the watch keeps its own identity and keys, and declares no internet permission.
 */
class WearMeshService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WearMeshService"
        private val MAX_TTL: UByte = AppConstants.MESSAGE_TTL_HOPS
        private val PEER_DISCONNECT_GRACE_MS: Long = AppConstants.Mesh.PEER_DISCONNECT_GRACE_MS

        private const val PREF_CLOCK_OFFSET_MS = "mesh_clock_offset_ms"
        private const val PREF_LAST_TETHERED_MS = "last_tethered_wall_ms"
        private const val INTERNET_RETRY_FIRST_MS = 30_000L
        private const val INTERNET_RETRY_MAX_MS = 15 * 60_000L

        /**
         * How long after leaving the phone the watch still trusts its own wall clock. The OS syncs
         * time from the phone while tethered; watch crystals drift a second or two a day.
         */
        private const val CLOCK_TRUST_AFTER_TETHER_MS = 12 * 60 * 60_000L

        @Volatile
        private var instance: WearMeshService? = null

        fun getOrCreate(context: Context): WearMeshService {
            return instance ?: synchronized(this) {
                instance ?: WearMeshService(context.applicationContext).also { instance = it }
            }
        }

        fun peek(): WearMeshService? = instance
    }

    val encryptionService = EncryptionService(context)
    val myPeerID: String = encryptionService.getIdentityFingerprint().take(16)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bleTransport = BleTransport()
    private val linkChannel: WearLinkChannel = GmsWearLinkChannel(
        context,
        localCapability = WearLinkContract.CAPABILITY_WATCH_NODE,
        remoteCapability = WearLinkContract.CAPABILITY_PHONE_RELAY
    )
    private val phoneLink = WearLinkTransport(serviceScope, linkChannel, defaultRoute = true)

    /** Set when this watch uses its phone's identity (see [LinkedPhoneClient]). */
    val linkedClient = LinkedPhoneClient(context, serviceScope, linkChannel) { myPeerID }

    /** True while linked and next to the phone: the phone is on the mesh, the watch mirrors it. */
    val isMirrored: Boolean get() = linkedClient.mirroring

    /** Private messages over the internet (Nostr), when not mirroring a linked phone. */
    val internet = WatchNostrClient(
        context = context,
        scope = serviceScope,
        myPeerID = { myPeerID },
        myNickname = { nickname },
        onIncomingPrivate = { message -> handleMessageReceived(message) }
    )

    /** Public chat with people in the same area, over the internet (location channels). */
    val locationChannels = com.bitchat.watch.internet.WatchLocationChannels(
        context = context,
        scope = serviceScope,
        internet = internet,
        nickname = { nickname }
    )

    /** Message IDs already handed to the internet, so the outbox does not resend them there. */
    private val sentOverInternet = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** After a failed internet send: when to try again, and how many tries so far. */
    private val internetRetry = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()
    private val outboxLock = Any()
    private var lastLinkMode: LinkMode? = null
    private val linkModeController = LinkModeController(
        scope = serviceScope,
        phoneReachable = linkChannel.reachableNodes.map { it.isNotEmpty() },
        onModeChanged = ::onLinkModeChanged
    )
    // A linked watch never tunnels packets: next to the phone it is the phone, on the mesh.
    private val transport = SwitchingTransport(
        bluetooth = bleTransport,
        phoneLink = if (linkedClient.linked) NoTransport else phoneLink
    ) { linkModeController.mode.value }
    private val meshCore: MeshCore
    private var connectionManager: BluetoothConnectionManager

    /** Whether the watch is on its own radio or tunnelling through the phone. */
    val linkMode: StateFlow<LinkMode> get() = linkModeController.mode

    val meshClock = MeshClock(
        initialOffsetMs = prefs().getLong(PREF_CLOCK_OFFSET_MS, 0L),
        onOffsetChanged = { prefs().edit().putLong(PREF_CLOCK_OFFSET_MS, it).apply() }
    )

    private val rendezvous = RendezvousEngine(
        clock = meshClock,
        platform = AndroidRendezvousPlatform(context),
        setScanOverride = { PowerManager.getInstance(context).setScanWindowOverride(it) },
        hasLivePeers = { connectionManager.getConnectedDeviceCount() > 0 }
    )

    val rendezvousStatus: StateFlow<RendezvousEngine.Status> get() = rendezvous.status

    /** Private messages awaiting a delivery receipt; resent when the recipient is reachable. */
    private val dmOutbox = DmOutbox()
    private var outboxJob: kotlinx.coroutines.Job? = null

    /** Guards radio start/stop so a mode switch cannot interleave with service start or stop. */
    private val radioLock = Any()

    @Volatile
    private var bluetoothRunning = false

    private val foregroundObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) {
            rendezvous.onForegroundChanged()
        }
    }

    private val exactAlarmAccessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            rendezvous.onExactWakePermissionChanged()
        }
    }

    @Volatile
    var nickname: String = loadNickname()
        private set

    @Volatile
    private var isActive = false

    /** UI hook fired for every incoming private message (after storing). */
    var onPrivateMessage: ((com.bitchat.android.model.BitchatMessage) -> Unit)? = null

    init {
        meshCore = MeshCore(
            context = context.applicationContext,
            scope = serviceScope,
            transport = transport,
            encryptionService = encryptionService,
            myPeerID = myPeerID,
            maxTtl = MAX_TTL,
            sharedGossipManager = null,
            gossipConfigProvider = object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity(): Int = 500
                override fun gcsMaxBytes(): Int = 400
                override fun gcsTargetFpr(): Double = 0.01
            },
            hooks = MeshCore.Hooks(
                onMessageReceived = { message -> handleMessageReceived(message) },
                onAnnounceProcessed = { routed, _ ->
                    // Mirror the phone's BluetoothMeshService: learn the direct link
                    // address↔peerID mapping from direct-link announcements. The link is either a
                    // BLE connection or the tunnel to the paired phone.
                    DirectLinkAnnouncementPolicy.observationFor(routed, MAX_TTL)?.let { obs ->
                        val observed = if (WearLinkContract.isLinkAddress(obs.relayAddress)) {
                            phoneLink.bindPeer(obs.relayAddress, obs.peerID)
                        } else {
                            connectionManager.observePeerIfCurrent(
                                obs.relayAddress,
                                obs.ingressLinkID,
                                obs.peerID
                            )
                        }
                        if (observed) {
                            // A direct announce carries the neighbour's own wall clock, fresh.
                            refreshClockTrust()
                            meshClock.onPassiveSample(obs.peerID, routed.packet.timestamp.toLong())
                            meshCore.setDirectConnection(obs.peerID, true)
                            try {
                                meshCore.gossipSyncManager.scheduleInitialSyncToPeer(obs.peerID, 1_000)
                            } catch (_: Exception) { }
                        }
                    }
                    routed.peerID?.let { pid ->
                        maybeAutoHandshake(pid)
                        // A peer that reappears gets anything still waiting for it straight away.
                        serviceScope.launch { runOutboxPass(urgentPeers = setOf(pid)) }
                        try {
                            meshCore.gossipSyncManager.scheduleInitialSyncToPeer(pid, 1_000)
                        } catch (_: Exception) { }
                    }
                },
                announcementNicknameProvider = { nickname },
                leavePayloadProvider = { nickname.toByteArray(Charsets.UTF_8) }
            )
        )
        connectionManager = BluetoothConnectionManager(context, myPeerID, meshCore.fragmentManager)
        bleTransport.connectionManager = connectionManager
        wireBluetoothDelegate()

        phoneLink.attachFragmentManager(meshCore.fragmentManager)
        phoneLink.listener = object : WearLinkTransport.Listener {
            override fun onPacket(packet: BitchatPacket, peerID: String, linkAddress: String) {
                meshCore.processIncoming(packet, peerID, linkAddress, linkAddress)
            }

            override fun onPeerUnreachable(peerID: String) {
                meshCore.setDirectConnection(peerID, false)
                try { meshCore.refreshPeerList() } catch (_: Exception) { }
                // Same grace as a Bluetooth disconnect: remove the phone unless it comes back.
                serviceScope.launch {
                    delay(PEER_DISCONNECT_GRACE_MS)
                    val linkBack = phoneLink.getDeviceAddressForPeer(peerID) != null ||
                        connectionManager.addressPeerMap.containsValue(peerID)
                    if (!linkBack) {
                        try { meshCore.removePeer(peerID) } catch (_: Exception) { }
                    }
                }
            }
        }
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        }
        linkedClient.onNicknameFromPhone = { name -> applyNickname(name.trim().take(32)) }
        com.bitchat.watch.ui.WearChatState.onDmShown = { peerID -> markConversationRead(peerID) }
    }

    private inner class BleTransport : MeshTransport {
        lateinit var connectionManager: BluetoothConnectionManager

        override val id: String = "BLE"

        override fun broadcastPacket(routed: RoutedPacket): Boolean =
            connectionManager.broadcastPacket(routed)

        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean =
            connectionManager.sendPacketToPeer(peerID, packet)

        override fun sendPacketToLink(
            relayAddress: String,
            ingressLinkID: String,
            packet: BitchatPacket
        ): Boolean = connectionManager.sendPacketToLink(relayAddress, ingressLinkID, packet)

        override fun cancelTransfer(transferId: String): Boolean =
            connectionManager.cancelTransfer(transferId)

        override fun getDeviceAddressForPeer(peerID: String): String? =
            connectionManager.addressPeerMap.entries.firstOrNull { it.value == peerID }?.key

        override fun getDeviceAddressToPeerMapping(): Map<String, String> =
            connectionManager.addressPeerMap.toMap()

        override fun getTransportDebugInfo(): String = connectionManager.getDebugInfo()
    }

    private fun wireBluetoothDelegate() {
        connectionManager.delegate = object : BluetoothConnectionManagerDelegate {
            override fun onPacketReceived(
                packet: BitchatPacket,
                peerID: String,
                device: BluetoothDevice?,
                ingressLinkID: String
            ) {
                try {
                    com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().logIncoming(
                        packet = packet,
                        fromPeerID = peerID,
                        fromNickname = null,
                        fromDeviceAddress = device?.address,
                        myPeerID = myPeerID
                    )
                } catch (_: Exception) { }
                rendezvous.onPeerContact()
                meshCore.processIncoming(packet, peerID, device?.address, ingressLinkID)
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                Log.i(TAG, "Device connected: ${device.address}")
                rendezvous.onPeerContact()
                serviceScope.launch {
                    delay(200)
                    meshCore.sendBroadcastAnnounce()
                }
            }

            override fun onDeviceDisconnected(
                device: BluetoothDevice,
                linkID: String?,
                peerID: String?
            ) {
                Log.i(TAG, "Device disconnected: ${device.address} (peerID: $peerID)")
                try { meshCore.refreshPeerList() } catch (_: Exception) { }
                if (peerID != null) {
                    meshCore.setDirectConnection(peerID, false)
                    val deviceAddress = device.address
                    serviceScope.launch {
                        delay(PEER_DISCONNECT_GRACE_MS)
                        try {
                            val linkBack =
                                connectionManager.addressPeerMap.containsKey(deviceAddress) ||
                                    connectionManager.addressPeerMap.containsValue(peerID)
                            if (!linkBack) {
                                Log.i(TAG, "Peer $peerID did not return after disconnect; removing")
                                meshCore.removePeer(peerID)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Disconnect grace check failed for $peerID: ${e.message}")
                        }
                    }
                }
            }

            override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
                connectionManager.addressPeerMap[deviceAddress]?.let { peerID ->
                    meshCore.updatePeerRSSI(peerID, rssi)
                }
            }
        }
    }

    /**
     * Proactively establish a Noise session with peers we have no session for (throttled to
     * one attempt per peer per 60 s). Peers may hold a stale session after we restart — the
     * protocol has no decrypt-failure kick path, so our fresh handshake replaces it and
     * restores encrypted DM/file delivery.
     */
    private val handshakeAttempts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun maybeAutoHandshake(peerID: String) {
        if (peerID == myPeerID || hasEstablishedSession(peerID)) return
        val now = System.currentTimeMillis()
        val last = handshakeAttempts[peerID] ?: 0L
        if (now - last < 60_000) return
        handshakeAttempts[peerID] = now
        serviceScope.launch {
            delay(1_500)
            if (!hasEstablishedSession(peerID)) {
                try {
                    Log.d(TAG, "Auto-initiating Noise handshake with ${peerID.take(8)}")
                    initiateNoiseHandshake(peerID)
                } catch (_: Exception) { }
            }
        }
    }

    private fun handleMessageReceived(
        message: com.bitchat.android.model.BitchatMessage
    ): Boolean = try {
        when {
            message.isPrivate -> {
                val peer = message.senderPeerID ?: return false
                if (!AppStateStore.addPrivateMessage(peer, message)) return false
                try { onPrivateMessage?.invoke(message) } catch (_: Exception) { }
                // Arrived while that chat is on screen: it has been read.
                if (com.bitchat.watch.ui.WearChatState.isOpen(peer)) {
                    serviceScope.launch { markConversationRead(peer) }
                }
                true
            }
            !SpamFilter.isLocalNotice(message) &&
                SpamFilter.shared.shouldHide(message, SpamFilter.meshSenderKey(message)) -> false
            message.channel != null -> {
                AppStateStore.addChannelMessage(message.channel, message)
                true
            }
            else -> {
                AppStateStore.addPublicMessage(message)
                true
            }
        }
    } catch (_: Exception) {
        !message.isPrivate
    }

    fun startServices() {
        synchronized(radioLock) {
            if (isActive) {
                // A repeat start is how callers retry once permissions or Bluetooth come back.
                if (linkModeController.mode.value == LinkMode.STANDALONE && !bluetoothRunning) {
                    enterStandalone()
                } else {
                    Log.w(TAG, "Mesh already active, ignoring duplicate start")
                }
                return
            }
            isActive = true
            meshCore.startCore()
            RendezvousAlarmReceiver.engine = rendezvous
            ContextCompat.registerReceiver(
                context,
                exactAlarmAccessReceiver,
                IntentFilter(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            // Start on the watch's own radio; the controller moves to the phone if one answers.
            enterStandalone()
            linkedClient.start()
            if (linkedClient.linked) linkChannel.start() else phoneLink.start()
            linkModeController.start()
            internet.start()
            com.bitchat.watch.glance.GlanceUpdater.start(context, serviceScope, this)
            outboxJob = serviceScope.launch {
                while (true) {
                    delay(DmOutbox.FIRST_RETRY_MS)
                    runOutboxPass()
                }
            }
            serviceScope.launch {
                delay(500)
                meshCore.sendBroadcastAnnounce()
            }
            Log.i(TAG, "Mesh services started (peerID: $myPeerID)")
        }
    }

    fun stopServices() {
        synchronized(radioLock) {
            if (!isActive) return
            isActive = false
            internet.stop()
            com.bitchat.watch.glance.GlanceUpdater.stop()
            com.bitchat.watch.glance.GlanceUpdater.push(context)
            outboxJob?.cancel()
            outboxJob = null
            linkModeController.stop()
            rendezvous.stop()
            RendezvousAlarmReceiver.engine = null
            try { context.unregisterReceiver(exactAlarmAccessReceiver) } catch (_: Exception) { }
            linkedClient.stop()
            if (linkedClient.linked) linkChannel.stop() else phoneLink.stop()
            meshCore.stopCore()
            connectionManager.stopServices()
            bluetoothRunning = false
            Log.i(TAG, "Mesh services stopped")
        }
    }

    private fun onLinkModeChanged(mode: LinkMode) {
        synchronized(radioLock) {
            if (!isActive) return
            // Entering or leaving the phone: the clock was disciplined just now. A standalone
            // start that never saw the phone is not.
            if (mode == LinkMode.TETHERED || lastLinkMode == LinkMode.TETHERED) {
                prefs().edit().putLong(PREF_LAST_TETHERED_MS, System.currentTimeMillis()).apply()
            }
            lastLinkMode = mode
            when (mode) {
                LinkMode.TETHERED -> enterTethered()
                LinkMode.STANDALONE -> enterStandalone()
            }
            refreshClockTrust()
        }
        Log.i(TAG, "Link mode: $mode")
        // A linked phone fetches internet messages for both while the watch is next to it.
        internet.setSuspended(linkedClient.linked && mode == LinkMode.TETHERED)
        if (linkedClient.linked) {
            if (mode == LinkMode.TETHERED) {
                // Same identity as the phone: stay silent, mirror it, and hand over unsent DMs.
                linkedClient.onTethered()
                handOutboxToPhone()
                return
            }
            linkedClient.onStandalone()
        }
        serviceScope.launch {
            delay(300)
            meshCore.sendBroadcastAnnounce()
        }
        // Catch up on public messages either side sent while the link was down, rather than
        // waiting for the next periodic sync.
        try { meshCore.gossipSyncManager.scheduleInitialSync(1_500) } catch (_: Exception) { }
        // And retry private messages that went out while the link was down.
        serviceScope.launch {
            delay(3_000)
            runOutboxPass(urgentPeers = dmOutbox.pending().mapTo(HashSet()) { it.peerID })
        }
    }

    /** Linked and back with the phone: the phone delivers (or queues) what is still pending. */
    private fun handOutboxToPhone() {
        // Only what the phone was actually handed leaves the outbox; the rest is tried again.
        dmOutbox.pending().forEach { entry ->
            if (linkedClient.sendPrivate(entry.messageID, entry.peerID, entry.recipientNickname, entry.content)) {
                dmOutbox.remove(entry.messageID)
            }
        }
    }

    /** Bring up the watch's own BLE mesh and hand background scanning to the rendezvous engine. */
    private fun enterStandalone() {
        if (!connectionManager.isReusable()) {
            // A previous stopServices() cancelled the manager's coroutine scope; the shared
            // API marks such managers single-use, so build a fresh one instead of starting
            // a zombie mesh that reports active while scanning nothing.
            Log.i(TAG, "Recreating BluetoothConnectionManager after terminal stop")
            connectionManager = BluetoothConnectionManager(context, myPeerID, meshCore.fragmentManager)
            bleTransport.connectionManager = connectionManager
            wireBluetoothDelegate()
        }
        bluetoothRunning = connectionManager.startServices()
        if (bluetoothRunning) {
            rendezvous.start()
        } else {
            Log.e(TAG, "Failed to start Bluetooth services (permissions? BT off?)")
        }
    }

    /** The phone carries the mesh now: stop scanning and advertising on the watch itself. */
    private fun enterTethered() {
        rendezvous.stop()
        if (bluetoothRunning) {
            // disableTransport keeps the manager reusable for the next standalone period.
            connectionManager.disableTransport()
            bluetoothRunning = false
        }
    }

    /**
     * A private chat was shown on the watch: mark it read and tell the sender. Linked and next to
     * the phone, the phone does both; otherwise the receipt goes over Bluetooth if the sender is in
     * reach, or over the internet if that is how the message came (or the only way to them).
     */
    fun markConversationRead(peerID: String) {
        if (!isActive) return
        if (isMirrored) {
            linkedClient.markRead(peerID)
            return
        }
        val conversationID = com.bitchat.android.services.ContactDirectory.canonicalConversationId(peerID)
        val unread = AppStateStore.privateMessages.value.entries
            .filter {
                com.bitchat.android.services.ContactDirectory.canonicalConversationId(it.key)
                    .equals(conversationID, ignoreCase = true)
            }
            .flatMap { it.value }
            .filter { it.senderPeerID != myPeerID && it.sender != "system" && !AppStateStore.isPrivateMessageRead(it.id) }
        unread.forEach { message ->
            AppStateStore.markPrivateMessageRead(message.id)
            val sender = message.senderPeerID ?: peerID
            if (message.senderNostrPubkey == null && isPeerReachable(sender) && hasEstablishedSession(sender)) {
                meshCore.sendReadReceipt(message.id, sender, nickname)
            } else {
                serviceScope.launch { internet.sendReadReceipt(message.id, sender, message.senderNostrPubkey) }
            }
        }
    }

    /** Whether [peerID] is currently reachable over Bluetooth (directly or through the mesh). */
    fun isPeerReachable(peerID: String): Boolean = getPeerInfo(peerID)?.isConnected == true

    /** Send over the internet right away (the recipient is out of Bluetooth reach). */
    fun sendPrivateOverInternet(content: String, peerID: String, messageID: String) {
        sentOverInternet += messageID
        serviceScope.launch {
            if (internet.sendPrivate(content, peerID, messageID)) {
                AppStateStore.updatePrivateMessageStatus(messageID, com.bitchat.android.model.DeliveryStatus.Sent)
            } else {
                sentOverInternet -= messageID
            }
        }
    }

    /** Hold a private text message until the recipient acknowledges it. */
    fun trackOutgoingPrivate(messageID: String, peerID: String, recipientNickname: String, content: String) {
        dmOutbox.track(messageID, peerID, recipientNickname, content)
    }

    private fun runOutboxPass(urgentPeers: Set<String> = emptySet()) {
        synchronized(outboxLock) { runOutboxPassLocked(urgentPeers) }
    }

    private fun runOutboxPassLocked(urgentPeers: Set<String>) {
        if (!isActive || dmOutbox.size == 0 || isMirrored) return
        // Recipients out of Bluetooth reach go over the internet (mutual favorites only). A failed
        // try (often: no connection) backs off instead of waking the network every pass.
        val now = System.currentTimeMillis()
        dmOutbox.pending().forEach { entry ->
            val retry = internetRetry[entry.messageID]
            if (entry.messageID !in sentOverInternet &&
                (retry == null || now >= retry.first) &&
                !isPeerReachable(entry.peerID) &&
                internet.canSend(entry.peerID)
            ) {
                sentOverInternet += entry.messageID
                serviceScope.launch {
                    if (internet.sendPrivate(entry.content, entry.peerID, entry.messageID)) {
                        internetRetry.remove(entry.messageID)
                        AppStateStore.updatePrivateMessageStatus(
                            entry.messageID,
                            com.bitchat.android.model.DeliveryStatus.Sent
                        )
                    } else {
                        val tries = (internetRetry[entry.messageID]?.second ?: 0) + 1
                        val waitMs = (INTERNET_RETRY_FIRST_MS shl (tries - 1).coerceAtMost(5))
                            .coerceAtMost(INTERNET_RETRY_MAX_MS)
                        internetRetry[entry.messageID] = (System.currentTimeMillis() + waitMs) to tries
                        sentOverInternet -= entry.messageID
                    }
                }
            }
        }
        val actions = dmOutbox.due(
            statusOf = ::privateMessageStatus,
            isReachable = { peer -> getPeerInfo(peer)?.isConnected == true },
            hasSession = ::hasEstablishedSession,
            urgentPeers = urgentPeers
        )
        for (action in actions) {
            val entry = action.entry
            when (action) {
                is DmOutbox.Action.Resend -> {
                    meshCore.sendPrivateMessage(entry.content, entry.peerID, entry.recipientNickname, entry.messageID)
                    AppStateStore.updatePrivateMessageStatus(entry.messageID, com.bitchat.android.model.DeliveryStatus.Sent)
                }
                is DmOutbox.Action.Handshake -> maybeAutoHandshake(entry.peerID)
                is DmOutbox.Action.Expire -> AppStateStore.updatePrivateMessageStatus(
                    entry.messageID,
                    com.bitchat.android.model.DeliveryStatus.Failed("Not delivered")
                )
            }
            dmOutbox.attempted(action)
        }
    }

    private fun privateMessageStatus(messageID: String): com.bitchat.android.model.DeliveryStatus? =
        AppStateStore.privateMessages.value.values.asSequence()
            .flatten()
            .firstOrNull { it.id == messageID }
            ?.deliveryStatus

    /** The watch's wall clock is trusted while tethered and for a while after. */
    private fun refreshClockTrust() {
        val lastTethered = prefs().getLong(PREF_LAST_TETHERED_MS, 0L)
        val trusted = linkModeController.mode.value == LinkMode.TETHERED ||
            System.currentTimeMillis() - lastTethered < CLOCK_TRUST_AFTER_TETHER_MS
        meshClock.setSelfRank(
            if (trusted) MeshClock.RANK_DISCIPLINED else MeshClock.RANK_FREE_RUNNING
        )
    }

    fun isRunning(): Boolean = isActive

    fun setNickname(name: String) {
        val trimmed = name.trim().take(32)
        if (trimmed.isEmpty() || trimmed == nickname) return
        applyNickname(trimmed)
        // Linked: the phone shares this name, so it changes there too.
        linkedClient.onLocalNicknameChanged(trimmed)
    }

    /** Change the nickname without telling the phone (used when the phone changed it). */
    private fun applyNickname(trimmed: String) {
        if (trimmed == nickname) return
        nickname = trimmed
        saveNickname(trimmed)
        if (isActive) {
            serviceScope.launch { meshCore.sendBroadcastAnnounce() }
        }
    }

    fun sendMessage(content: String, mentions: List<String> = emptyList()) {
        meshCore.sendMessage(content, mentions, null)
    }

    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String) {
        meshCore.sendPrivateMessage(content, recipientPeerID, recipientNickname)
    }

    /**
     * Favorite controls travel as encrypted private control messages. If the profile is opened
     * while a handshake is still settling, wait briefly rather than silently losing the change.
     */
    fun sendFavoriteNotification(peerID: String, isFavorite: Boolean) {
        serviceScope.launch {
            val deadline = System.currentTimeMillis() + 15_000L
            if (!hasEstablishedSession(peerID)) {
                runCatching { initiateNoiseHandshake(peerID) }
            }
            while (!hasEstablishedSession(peerID) && System.currentTimeMillis() < deadline) {
                delay(250L)
            }
            if (!hasEstablishedSession(peerID)) {
                Log.w(TAG, "Favorite update could not be sent before the Noise timeout")
                return@launch
            }
            val recipientNickname = getPeerNickname(peerID) ?: peerID.take(8)
            meshCore.sendPrivateMessage(
                // Include this identity's internet key, so a mutual favorite can reach us online.
                content = FavoriteControlMessage.encode(
                    isFavorite,
                    npub = runCatching {
                        com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub
                    }.getOrNull()
                ),
                recipientPeerID = peerID,
                recipientNickname = recipientNickname
            )
        }
    }

    fun initiateNoiseHandshake(peerID: String) {
        if (!isMirrored) meshCore.initiateNoiseHandshake(peerID)
    }

    /** While mirroring, the phone routes private messages itself (mesh, internet or queue). */
    fun hasEstablishedSession(peerID: String): Boolean =
        if (isMirrored) true else meshCore.hasEstablishedSession(peerID)

    fun getSessionState(peerID: String) = meshCore.getSessionState(peerID)

    fun getPeerInfo(peerID: String) = meshCore.getPeerInfo(peerID)

    fun getIdentityFingerprint(): String = encryptionService.getIdentityFingerprint()

    fun getStaticNoisePublicKey(): ByteArray? = meshCore.getStaticNoisePublicKey()

    fun sendBroadcastAnnounce() = meshCore.sendBroadcastAnnounce()

    fun sendChannelMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null) {
        meshCore.sendMessage(content, mentions, channel)
    }

    fun sendPrivateMessageWithId(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String?
    ) {
        meshCore.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
    }

    fun getDeviceAddressForPeer(peerID: String): String? = meshCore.getDeviceAddressForPeer(peerID)

    fun getDeviceAddressToPeerMapping(): Map<String, String> = meshCore.getDeviceAddressToPeerMapping()

    fun connectToPeer(peerID: String): Boolean {
        val address = getDeviceAddressForPeer(peerID) ?: return false
        return connectionManager.connectToAddress(address)
    }

    fun sendFileBroadcast(file: com.bitchat.android.model.BitchatFilePacket) {
        meshCore.sendFileBroadcast(file)
    }

    fun sendVoiceFrame(recipientPeerID: String?, payload: ByteArray) {
        meshCore.sendVoiceFrame(recipientPeerID, payload)
    }

    /**
     * Noise-encrypted private file transfer with session/prep retry (mirrors the phone's
     * dispatchFileSend): ensures an established session, then retries transient
     * preparation states (AwaitingPeerState/NeedsHandshake) before giving up.
     */
    fun sendFilePrivateEncrypted(recipientPeerID: String, file: com.bitchat.android.model.BitchatFilePacket) {
        serviceScope.launch {
            val sessionDeadline = System.currentTimeMillis() + 15_000
            while (!hasEstablishedSession(recipientPeerID) && System.currentTimeMillis() < sessionDeadline) {
                try { initiateNoiseHandshake(recipientPeerID) } catch (_: Exception) { }
                delay(500)
            }
            val transferId = com.bitchat.android.mesh.MeshPacketUtils.sha256Hex(
                file.encode() ?: return@launch
            )
            val prepDeadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < prepDeadline) {
                when (val prep = meshCore.prepareFilePrivate(recipientPeerID, file, transferId, allowLegacyFallback = false)) {
                    is com.bitchat.android.mesh.PrivateMediaPreparation.Ready -> {
                        prep.transfer.commit()
                        return@launch
                    }
                    com.bitchat.android.mesh.PrivateMediaPreparation.AwaitingPeerState,
                    com.bitchat.android.mesh.PrivateMediaPreparation.NeedsHandshake -> {
                        if (prep == com.bitchat.android.mesh.PrivateMediaPreparation.NeedsHandshake) {
                            try { initiateNoiseHandshake(recipientPeerID) } catch (_: Exception) { }
                        }
                        delay(500)
                    }
                    else -> {
                        Log.w(TAG, "private voice note preparation failed: $prep")
                        return@launch
                    }
                }
            }
            Log.w(TAG, "private voice note preparation timed out")
        }
    }

    fun getPeerFingerprint(peerID: String): String? = meshCore.getPeerFingerprint(peerID)

    fun getPeerNicknames(): Map<String, String> =
        if (isMirrored) meshCore.getPeerNicknames() + linkedClient.peerNicknames() else meshCore.getPeerNicknames()

    fun getPeerRSSI(): Map<String, Int> = if (isMirrored) emptyMap() else meshCore.getPeerRSSI()

    fun getPeerNickname(peerID: String): String? =
        (if (isMirrored) linkedClient.peerNicknames()[peerID] else null) ?: meshCore.getPeerNickname(peerID)

    /** Mirroring: ask the phone to send a public message. Returns false if it could not be handed over. */
    fun sendPublicViaPhone(messageID: String, content: String): Boolean =
        linkedClient.sendPublic(messageID, content)

    /** Mirroring: ask the phone to send a private message. */
    fun sendPrivateViaPhone(messageID: String, peerID: String, recipientNickname: String, content: String): Boolean =
        linkedClient.sendPrivate(messageID, peerID, recipientNickname, content)

    fun getDebugStatus(): String = meshCore.getDebugStatus(
        transportInfo = transport.getTransportDebugInfo(),
        deviceMap = transport.getDeviceAddressToPeerMapping(),
        title = "Wear Mesh Debug Status"
    )

    private fun prefs() = context.getSharedPreferences("bitchat_watch_prefs", Context.MODE_PRIVATE)

    private fun loadNickname(): String =
        prefs().getString("nickname", null) ?: "watch-${myPeerID.take(4)}"

    private fun saveNickname(name: String) {
        prefs().edit().putString("nickname", name).apply()
    }
}
