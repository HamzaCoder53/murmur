package com.bitchat.watch.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** How the watch currently reaches the mesh. */
enum class LinkMode {
    /** Own Bluetooth radio: advertise continuously, scan in rendezvous windows. */
    STANDALONE,

    /** Through the paired phone over the Data Layer; own mesh radio off. */
    TETHERED
}

/**
 * Chooses between [LinkMode.STANDALONE] and [LinkMode.TETHERED] from phone reachability.
 *
 * Both directions are debounced. A phone at the edge of range flaps, and every switch tears a
 * radio down and brings another up, so a change has to hold before it is acted on. Leaving the
 * phone waits longer than joining it: a brief dropout should not start the watch's own radio.
 */
class LinkModeController(
    private val scope: CoroutineScope,
    private val phoneReachable: Flow<Boolean>,
    private val onModeChanged: (LinkMode) -> Unit,
    private val tetherDelayMs: Long = DEFAULT_TETHER_DELAY_MS,
    private val untetherDelayMs: Long = DEFAULT_UNTETHER_DELAY_MS
) {
    companion object {
        const val DEFAULT_TETHER_DELAY_MS = 2_000L
        const val DEFAULT_UNTETHER_DELAY_MS = 10_000L
    }

    private val _mode = MutableStateFlow(LinkMode.STANDALONE)
    val mode: StateFlow<LinkMode> = _mode.asStateFlow()

    private var job: Job? = null

    /** Starts in [LinkMode.STANDALONE]; the caller is expected to have its own radio up. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            phoneReachable.distinctUntilChanged().collectLatest { reachable ->
                val target = if (reachable) LinkMode.TETHERED else LinkMode.STANDALONE
                if (target == _mode.value) return@collectLatest
                // collectLatest cancels this wait if reachability changes again first.
                delay(if (target == LinkMode.TETHERED) tetherDelayMs else untetherDelayMs)
                _mode.value = target
                onModeChanged(target)
            }
        }
    }

    /** Stops tracking and reports [LinkMode.STANDALONE] without invoking the callback. */
    fun stop() {
        job?.cancel()
        job = null
        _mode.value = LinkMode.STANDALONE
    }
}
