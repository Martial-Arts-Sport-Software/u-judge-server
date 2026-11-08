package org.mass.bout

import org.mass.domain.DomainEvent
import org.mass.domain.PeerEventSequence
import org.mass.domain.SequencedDomainEvent
import org.mass.persistence.JdbcDomainEventStore

/** The bout events of this peer in the single journal; every append gets the next per-peer sequence. */
interface BoutEventJournal {
    fun events(): List<SequencedDomainEvent>

    fun append(event: DomainEvent): SequencedDomainEvent

    class InMemory : BoutEventJournal {
        private val stored = mutableListOf<SequencedDomainEvent>()
        private var sequence: PeerEventSequence? = null

        @Synchronized
        override fun events(): List<SequencedDomainEvent> = stored.toList()

        @Synchronized
        override fun append(event: DomainEvent): SequencedDomainEvent {
            val owner = sequence ?: PeerEventSequence(event.peerId).also { sequence = it }
            return SequencedDomainEvent(event, owner.next(event.peerId)).also { stored += it }
        }
    }
}

/** Bout events in `domain_events`, sharing the per-peer sequence with every other event type (ADR-001, I1). */
class JdbcBoutEventJournal(private val store: JdbcDomainEventStore) : BoutEventJournal {
    override fun events(): List<SequencedDomainEvent> = store.sessionEvents(KerugiBoutEvents.ALL)

    override fun append(event: DomainEvent): SequencedDomainEvent = store.append(event)
}
