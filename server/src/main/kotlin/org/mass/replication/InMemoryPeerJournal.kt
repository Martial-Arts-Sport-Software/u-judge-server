package org.mass.replication

import java.util.UUID

data class JournalEvent(
    val id: String,
    val ownerPeerId: String,
    val sequence: Long,
    val payload: String,
)

class InMemoryPeerJournal(
    private val peerId: String,
    initialEvents: Collection<JournalEvent> = emptyList(),
) {
    private val eventsById = linkedMapOf<String, JournalEvent>()

    init {
        require(peerId.isNotBlank())
        receive(initialEvents)
    }

    val events: List<JournalEvent>
        get() = eventsById.values.sortedWith(eventComparator)

    val eventIds: Set<String>
        get() = eventsById.keys.toSet()

    val missingSequences: Map<String, Set<Long>>
        get() = eventsByOwner()
            .mapValues { (_, events) -> missingSequences(events) }
            .filterValues { it.isNotEmpty() }

    val projectedPayloads: List<String>
        get() = eventsByOwner()
            .toSortedMap()
            .values
            .flatMap { contiguousEvents(it) }
            .map(JournalEvent::payload)

    fun append(payload: String): JournalEvent {
        val nextSequence = eventsById.values
            .asSequence()
            .filter { it.ownerPeerId == peerId }
            .maxOfOrNull(JournalEvent::sequence)
            ?.plus(1)
            ?: 1
        val event = JournalEvent(UUID.randomUUID().toString(), peerId, nextSequence, payload)
        receive(listOf(event))
        return event
    }

    fun receive(incomingEvents: Collection<JournalEvent>) {
        incomingEvents.forEach { event ->
            require(event.id.isNotBlank())
            require(event.ownerPeerId.isNotBlank())
            require(event.sequence > 0)

            val existing = eventsById[event.id]
            require(existing == null || existing == event) { "Event ID ${event.id} conflicts with an existing event" }
            val sequenceConflict = eventsById.values.any {
                it.ownerPeerId == event.ownerPeerId && it.sequence == event.sequence && it.id != event.id
            }
            require(!sequenceConflict) { "Sequence ${event.sequence} already exists for ${event.ownerPeerId}" }
            if (existing == null) {
                eventsById[event.id] = event
            }
        }
    }

    fun synchronizeWith(other: InMemoryPeerJournal) {
        receive(other.events)
        other.receive(events)
    }

    private fun eventsByOwner(): Map<String, List<JournalEvent>> =
        eventsById.values.groupBy(JournalEvent::ownerPeerId)

    private fun missingSequences(events: List<JournalEvent>): Set<Long> {
        val receivedSequences = events.mapTo(sortedSetOf(), JournalEvent::sequence)
        val highestSequence = receivedSequences.lastOrNull() ?: return emptySet()
        return (1..highestSequence).filterTo(sortedSetOf()) { it !in receivedSequences }
    }

    private fun contiguousEvents(events: List<JournalEvent>): List<JournalEvent> = buildList {
        events.sortedBy(JournalEvent::sequence).forEach { event ->
            if (event.sequence == size + 1L) {
                add(event)
            }
        }
    }

    private companion object {
        val eventComparator = compareBy<JournalEvent>(JournalEvent::ownerPeerId, JournalEvent::sequence, JournalEvent::id)
    }
}
