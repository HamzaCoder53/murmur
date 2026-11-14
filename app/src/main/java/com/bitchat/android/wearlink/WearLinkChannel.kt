package com.bitchat.android.wearlink

import kotlinx.coroutines.flow.StateFlow

/**
 * Byte pipe between this device and its paired counterpart (phone to watch, or watch to phone).
 * The production implementation is [GmsWearLinkChannel]; tests substitute an in-memory pair.
 */
interface WearLinkChannel {
    /** Counterpart nodes that are reachable right now and are running their mesh service. */
    val reachableNodes: StateFlow<Set<String>>

    /** Invoked for every tunnel message received. May be called on any thread. */
    var onMessage: ((nodeId: String, data: ByteArray) -> Unit)?

    /** Begin advertising this side and tracking the other. Idempotent. */
    fun start()

    /** Stop advertising and tracking. Idempotent. */
    fun stop()

    /**
     * Queue [data] for [nodeId]. Returns false when the message could not be handed off at all;
     * true means accepted for delivery, not delivered.
     */
    fun send(nodeId: String, data: ByteArray): Boolean

    /** Send a control message on a [WearLinkContract.CONTROL_PREFIX] path. */
    fun sendControl(nodeId: String, path: String, data: ByteArray): Boolean = false

    /** Receives control messages; may be called on any thread. */
    fun setControlListener(listener: ((nodeId: String, path: String, data: ByteArray) -> Unit)?) = Unit
}
