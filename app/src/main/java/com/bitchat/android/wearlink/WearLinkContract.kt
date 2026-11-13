package com.bitchat.android.wearlink

/**
 * Wire contract for the phone-watch tunnel. Compiled into both the phone and watch apps from this
 * one file, so the two ends cannot drift apart.
 *
 * The tunnel carries mesh packets unchanged: each Data Layer message on [PATH_PACKET] is exactly
 * one binary-encoded BitchatPacket, the same bytes that would cross a BLE link. Encryption and
 * signatures stay end to end; the phone relays what it cannot read.
 */
object WearLinkContract {
    /** Transport id of the tunnel in MeshCore and TransportBridgeService. */
    const val TRANSPORT_ID = "WEAR"

    /** Advertised by the phone only while its mesh service is running. */
    const val CAPABILITY_PHONE_RELAY = "murmur_phone_relay"

    /** Advertised by the watch only while its mesh service is running. */
    const val CAPABILITY_WATCH_NODE = "murmur_watch_node"

    const val PATH_PACKET = "/murmur/v1/pkt"

    private const val LINK_ADDRESS_PREFIX = "wear:"

    /** Relay address and ingress link id for packets that arrived over the tunnel from [nodeId]. */
    fun linkAddress(nodeId: String): String = LINK_ADDRESS_PREFIX + nodeId

    fun isLinkAddress(address: String?): Boolean = address?.startsWith(LINK_ADDRESS_PREFIX) == true

    fun nodeIdOf(linkAddress: String): String = linkAddress.removePrefix(LINK_ADDRESS_PREFIX)
}
