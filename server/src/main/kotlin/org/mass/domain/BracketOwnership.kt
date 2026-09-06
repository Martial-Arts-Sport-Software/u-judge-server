package org.mass.domain

enum class BracketState {
    PREPARED,
    IN_PROGRESS,
    COMPLETED,
}

class BracketOwnership private constructor(
    val bracketId: BracketId,
    val ownerPeerId: PeerId,
    val state: BracketState,
) {
    fun start(requestingPeerId: PeerId): BracketOwnership {
        require(requestingPeerId == ownerPeerId) { "Only the bracket owner can start it" }
        check(state == BracketState.PREPARED) { "Only a prepared bracket can start" }
        return BracketOwnership(bracketId, ownerPeerId, BracketState.IN_PROGRESS)
    }

    companion object {
        fun assign(bracketId: BracketId, ownerPeerId: PeerId): BracketOwnership =
            BracketOwnership(bracketId, ownerPeerId, BracketState.PREPARED)
    }
}
