package org.mass.bout

import org.mass.domain.DomainEvent
import org.mass.domain.PeerEventSequence
import org.mass.domain.SequencedDomainEvent
import org.mass.performance.TechnicalPerformanceEvents
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

/**
 * Session events of the court in `domain_events` (bouts and technical performances, ADR-011 §2), sharing the per-peer
 * sequence with every other event type (ADR-001, I1); each service projects only its own sessions.
 */
class JdbcBoutEventJournal(private val store: JdbcDomainEventStore) : BoutEventJournal {
    override fun events(): List<SequencedDomainEvent> = store.sessionEvents(KerugiBoutEvents.ALL + TechnicalPerformanceEvents.ALL)

    override fun append(event: DomainEvent): SequencedDomainEvent = store.append(event)
}
