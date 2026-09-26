package org.mass.persistence

import org.mass.domain.*
import java.time.Instant
import javax.sql.DataSource

class JdbcKerugiResultJournal(
    private val store: JdbcDomainEventStore,
    private val ownership: BracketOwnership,
    private val sessionId: SessionId,
    private val now: () -> Instant = Instant::now,
) : KerugiResultEventJournal {
    constructor(dataSource: DataSource, ownership: BracketOwnership, sessionId: SessionId, now: () -> Instant = Instant::now) :
        this(JdbcDomainEventStore(dataSource), ownership, sessionId, now)

    private var currentProjection: KerugiResultProjection = KerugiResultJournal.rebuild(ownership, sessionId, events())

    @Synchronized
    override fun apply(command: DomainCommand, eventId: EventId): KerugiResultJournalResult {
        val event = command.toEvent(eventId, now())
        when (val existing = store.find(eventId, EVENT_TYPES)) {
            is JdbcDomainEventStore.EventLookup.Found -> return if (KerugiResultJournal.sameCommand(existing.event.event, event)) {
                KerugiResultJournalResult.Applied(existing.event, projectionAt(existing.event), false)
            } else rejected("Event ID is already assigned to a different command", event)
            JdbcDomainEventStore.EventLookup.AssignedElsewhere -> return rejected("Event ID is already assigned to a different command", event)
            JdbcDomainEventStore.EventLookup.Missing -> Unit
        }
        val next = try { KerugiResultJournal.applyEvent(currentProjection, event, ownership, sessionId) } catch (error: IllegalArgumentException) {
            return rejected(error.message ?: "Invalid Kerugi result command", event)
        }
        val sequenced = store.append(event)
        currentProjection = next
        return KerugiResultJournalResult.Applied(sequenced, next)
    }

    override fun events(): List<SequencedDomainEvent> = store.sessionEvents(ownership.bracketId, sessionId, EVENT_TYPES)

    override fun projection(): KerugiResultProjection = currentProjection

    private fun projectionAt(event: SequencedDomainEvent) = KerugiResultJournal.rebuild(ownership, sessionId, events().filter { it.sequence <= event.sequence })

    private fun rejected(reason: String, event: DomainEvent) = KerugiResultJournalResult.Rejected(reason, event.diagnosticContext())

    private companion object {
        val EVENT_TYPES = setOf(KerugiResultJournal.KERUGI_RESULT_EVENT)
    }
}
