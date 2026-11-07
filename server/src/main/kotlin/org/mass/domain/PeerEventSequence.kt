package org.mass.domain

/** The in-memory per-peer sequence of journals that run without persistence. */
class PeerEventSequence(private val ownerPeerId: PeerId) {
    private var latest = 0L

    @Synchronized
    fun next(requestingPeerId: PeerId): Long {
        require(requestingPeerId == ownerPeerId) { "Only the sequence owner can allocate an event sequence" }
        return ++latest
    }
}
