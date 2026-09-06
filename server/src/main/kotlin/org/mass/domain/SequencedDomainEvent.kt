package org.mass.domain

data class SequencedDomainEvent(
    val event: DomainEvent,
    val sequence: Long,
) {
    init {
        require(sequence > 0) { "Event sequence must be positive" }
    }

    val ownerPeerId: PeerId
        get() = event.peerId
}

object DomainEventOrder {
    fun order(events: Collection<SequencedDomainEvent>): List<SequencedDomainEvent> {
        require(events.groupBy { it.ownerPeerId to it.sequence }.values.all { it.size == 1 }) {
            "Owner sequence must identify one event"
        }
        return events.sortedWith(compareBy({ it.ownerPeerId.value }, SequencedDomainEvent::sequence))
    }
}
