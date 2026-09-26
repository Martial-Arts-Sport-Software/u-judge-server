package org.mass.persistence

import org.mass.domain.*
import java.time.Instant
import javax.sql.DataSource

class JdbcKerugiTimerJournal(
    private val store: JdbcDomainEventStore,
    private val ownership: BracketOwnership,
    private val sessionId: SessionId,
    private val now: () -> Instant = Instant::now,
) : KerugiTimerEventJournal {
    constructor(dataSource: DataSource, ownership: BracketOwnership, sessionId: SessionId, now: () -> Instant = Instant::now) :
        this(JdbcDomainEventStore(dataSource), ownership, sessionId, now)

    private var currentProjection: KerugiTimerProjection = KerugiTimerJournal.rebuild(ownership, sessionId, events())

    @Synchronized
    override fun apply(command: DomainCommand, eventId: EventId): KerugiTimerResult {
        val event = command.toEvent(eventId, now())
        when (val existing = store.find(eventId, EVENT_TYPES)) {
            is JdbcDomainEventStore.EventLookup.Found -> return if (KerugiTimerJournal.sameCommand(existing.event.event, event)) {
                KerugiTimerResult.Applied(existing.event, projectionAt(existing.event), false)
            } else rejected("Event ID is already assigned to a different command", event)
            JdbcDomainEventStore.EventLookup.AssignedElsewhere -> return rejected("Event ID is already assigned to a different command", event)
            JdbcDomainEventStore.EventLookup.Missing -> Unit
        }
        val next = try {
            KerugiTimerJournal.transition(currentProjection, event.type, KerugiTimerJournal.validate(event, ownership, sessionId))
        } catch (error: IllegalArgumentException) {
            return rejected(error.message ?: "Invalid Kerugi timer command", event)
        }
        val sequenced = store.append(event)
        currentProjection = next
        return KerugiTimerResult.Applied(sequenced, next)
    }

    override fun events(): List<SequencedDomainEvent> = store.sessionEvents(ownership.bracketId, sessionId, EVENT_TYPES)

    override fun projection(): KerugiTimerProjection = currentProjection

    private fun projectionAt(event: SequencedDomainEvent) = KerugiTimerJournal.rebuild(ownership, sessionId, events().filter { it.sequence <= event.sequence })

    private fun rejected(reason: String, event: DomainEvent) = KerugiTimerResult.Rejected(reason, event.diagnosticContext())

    private companion object {
        val EVENT_TYPES = setOf(
            "kerugi_timer_started", "kerugi_timer_paused", "kerugi_timer_resumed",
            "kerugi_round_break_started", "kerugi_round_break_ended", "kerugi_timer_stopped",
        )
    }
}
