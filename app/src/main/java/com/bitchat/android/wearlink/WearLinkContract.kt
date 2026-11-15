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

    /** Control messages between a phone and a watch that shares its identity (linked). */
    const val CONTROL_PREFIX = "/murmur/v1/ctl/"
    /** Watch asks to take the phone's identity. Payload: watch display name. */
    const val CTL_LINK_REQUEST = CONTROL_PREFIX + "link-request"
    /** Phone approved: payload is an IdentityBundle. */
    const val CTL_LINK_IDENTITY = CONTROL_PREFIX + "link-identity"
    /** Phone declined the request. */
    const val CTL_LINK_DENIED = CONTROL_PREFIX + "link-denied"
    /** Watch dropped the shared identity. */
    const val CTL_UNLINK = CONTROL_PREFIX + "unlink"
    /** Phone ran panic: the watch must erase the shared identity and chats. */
    const val CTL_WIPE = CONTROL_PREFIX + "wipe"
    /** Linked watch is next to the phone: send it chat state. Payload: MirrorProtocol backlog. */
    const val CTL_MIRROR_HELLO = CONTROL_PREFIX + "mirror-hello"
    /** Phone to watch: chat state snapshot (MirrorProtocol). */
    const val CTL_MIRROR_STATE = CONTROL_PREFIX + "mirror-state"
    /** Watch to phone: send a message on the watch's behalf (MirrorProtocol). */
    const val CTL_MIRROR_COMMAND = CONTROL_PREFIX + "mirror-cmd"
    /** Phone to any nearby watch: the phone app's language (BCP 47 tag, empty = follow system). */
    const val CTL_LANGUAGE = CONTROL_PREFIX + "language"

    /** File transfers (photos, voice notes) between a linked phone and watch. */
    const val FILE_PREFIX = "/murmur/v1/file/"
    /** Phone to watch: the media file of message <id>. */
    const val FILE_MEDIA = FILE_PREFIX + "media/"
    /** Watch to phone: a voice note recorded on the watch, message <id>. */
    const val FILE_UPLOAD = FILE_PREFIX + "upload/"

    private const val LINK_ADDRESS_PREFIX = "wear:"

    /** Relay address and ingress link id for packets that arrived over the tunnel from [nodeId]. */
    fun linkAddress(nodeId: String): String = LINK_ADDRESS_PREFIX + nodeId

    fun isLinkAddress(address: String?): Boolean = address?.startsWith(LINK_ADDRESS_PREFIX) == true

    fun nodeIdOf(linkAddress: String): String = linkAddress.removePrefix(LINK_ADDRESS_PREFIX)
}
