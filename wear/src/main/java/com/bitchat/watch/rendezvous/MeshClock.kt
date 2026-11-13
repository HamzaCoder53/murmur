package com.bitchat.watch.rendezvous

/**
 * Software clock the watch uses to place its rendezvous windows.
 *
 * `now() = wall clock + offsetMs`. The offset is scheduling state only: it is never written into
 * packets, so a wrong offset can cost a missed window but cannot make this watch's traffic look
 * stale or forged to other clients.
 *
 * Offset estimation, in order of trust:
 *  - A watch that was tethered to its phone recently has an OS-disciplined wall clock and uses
 *    offset 0 ([selfRank] == [RANK_DISCIPLINED]).
 *  - Samples from a peer known to be better disciplined are adopted in full.
 *  - Samples from peers of equal or unknown standing are adopted halfway. Two free-running watches
 *    that each move halfway toward the other's wall clock land on the same midpoint, and stay
 *    there when the measurement repeats.
 *
 * A sample is the peer's wall clock minus ours at the same instant. Round-trip samples (Cristian's
 * algorithm) correct for link latency; passive samples read the sender timestamp every packet
 * header already carries and accept the latency as error, which the ten-second guard band absorbs.
 */
class MeshClock(
    private val wallClock: () -> Long = System::currentTimeMillis,
    initialOffsetMs: Long = 0L,
    private val onOffsetChanged: (Long) -> Unit = {}
) {
    companion object {
        const val RANK_DISCIPLINED = 0
        const val RANK_FREE_RUNNING = 1

        /** Samples older than this no longer describe a peer that is in range. */
        const val SAMPLE_MAX_AGE_MS = 10 * 60_000L

        /**
         * Reject wildly wrong clocks. Peers already drop announcements more than three minutes
         * stale, so a peer this far off is not one we can mesh with anyway.
         */
        const val MAX_PLAUSIBLE_SKEW_MS = 90_000L

        /** A slow exchange says more about the link than about the clocks. */
        const val MAX_ROUND_TRIP_MS = 4_000L

        /** Ignore jitter-sized corrections so the schedule does not twitch. */
        const val DEADBAND_MS = 250L

        /**
         * Cristian's algorithm: the peer read [peerWallMs] somewhere between our send ([t0]) and
         * receive ([t1]); assume the midpoint.
         */
        fun roundTripSkewMs(t0: Long, peerWallMs: Long, t1: Long): Long =
            peerWallMs + (t1 - t0) / 2 - t1
    }

    private data class Sample(val skewMs: Long, val rank: Int, val takenAtMs: Long)

    private val lock = Any()
    private val samples = HashMap<String, Sample>()

    @Volatile
    var offsetMs: Long = initialOffsetMs
        private set

    @Volatile
    var selfRank: Int = RANK_FREE_RUNNING
        private set

    fun now(): Long = wallClock() + offsetMs

    /** Convert a mesh-time instant to the wall-clock instant an alarm should fire at. */
    fun toWallClock(meshTimeMs: Long): Long = meshTimeMs - offsetMs

    fun setSelfRank(rank: Int) {
        synchronized(lock) {
            selfRank = rank
            recompute()
        }
    }

    /** One-way sample from the sender timestamp of a packet received over a direct link. */
    fun onPassiveSample(peerID: String, peerWallMs: Long, receivedAtWallMs: Long = wallClock()) {
        record(peerID, peerWallMs - receivedAtWallMs, RANK_FREE_RUNNING, receivedAtWallMs)
    }

    /** Round-trip sample; returns false when the exchange was too slow to trust. */
    fun onRoundTripSample(peerID: String, t0: Long, peerWallMs: Long, t1: Long, peerRank: Int): Boolean {
        val roundTrip = t1 - t0
        if (roundTrip < 0 || roundTrip > MAX_ROUND_TRIP_MS) return false
        record(peerID, roundTripSkewMs(t0, peerWallMs, t1), peerRank, t1)
        return true
    }

    fun forgetPeer(peerID: String) {
        synchronized(lock) {
            if (samples.remove(peerID) != null) recompute()
        }
    }

    private fun record(peerID: String, skewMs: Long, rank: Int, takenAtMs: Long) {
        if (skewMs > MAX_PLAUSIBLE_SKEW_MS || skewMs < -MAX_PLAUSIBLE_SKEW_MS) return
        synchronized(lock) {
            samples[peerID] = Sample(skewMs, rank, takenAtMs)
            recompute()
        }
    }

    private fun recompute() {
        val nowWall = wallClock()
        samples.entries.removeAll { nowWall - it.value.takenAtMs > SAMPLE_MAX_AGE_MS }

        val target = when {
            selfRank == RANK_DISCIPLINED -> 0L
            samples.isEmpty() -> offsetMs
            else -> {
                val better = samples.values.filter { it.rank < selfRank }
                val estimate = if (better.isNotEmpty()) {
                    median(better.map { it.skewMs })
                } else {
                    median(samples.values.filter { it.rank == selfRank }.map { it.skewMs })
                        ?.let { it / 2 }
                }
                estimate ?: offsetMs
            }
        }

        val delta = target - offsetMs
        val mustApply = selfRank == RANK_DISCIPLINED && offsetMs != 0L
        if (!mustApply && delta < DEADBAND_MS && delta > -DEADBAND_MS) return
        offsetMs = target
        onOffsetChanged(target)
    }

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
