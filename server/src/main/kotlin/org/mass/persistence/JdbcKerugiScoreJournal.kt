package org.mass.persistence

import org.mass.domain.BracketOwnership
import org.mass.domain.DomainCommand
import org.mass.domain.DomainEvent
import org.mass.domain.EventId
import org.mass.domain.KERUGI_DISQUALIFICATION_EVENT
import org.mass.domain.KERUGI_OPERATOR_ACTION_EVENT
import org.mass.domain.KERUGI_SCORE_CANDIDATE_EVENT
import org.mass.domain.KERUGI_SCORE_CORRECTION_EVENT
import org.mass.domain.KerugiScoreEventJournal
import org.mass.domain.KerugiScoreJournal
import org.mass.domain.KerugiScoreResult
import org.mass.domain.KerugiScoringConfiguration
import org.mass.domain.KerugiScoringResult
import org.mass.domain.SessionId
import org.mass.domain.SequencedDomainEvent
import org.mass.domain.diagnosticContext
import java.time.Instant
import javax.sql.DataSource

class JdbcKerugiScoreJournal(
    private val store: JdbcDomainEventStore,
    private val ownership: BracketOwnership,
    private val sessionId: SessionId,
    private val configuration: KerugiScoringConfiguration,
    private val now: () -> Instant = Instant::now,
) : KerugiScoreEventJournal {
    constructor(
        dataSource: DataSource,
        ownership: BracketOwnership,
        sessionId: SessionId,
        configuration: KerugiScoringConfiguration,
        now: () -> Instant = Instant::now,
    ) : this(JdbcDomainEventStore(dataSource), ownership, sessionId, configuration, now)

    private var currentProjection: KerugiScoringResult =
        KerugiScoreJournal.rebuild(ownership, sessionId, configuration, events())

    @Synchronized
    override fun apply(command: DomainCommand, eventId: EventId): KerugiScoreResult {
        val event = command.toEvent(eventId, now())
        when (val existing = store.find(eventId, EVENT_TYPES)) {
            is JdbcDomainEventStore.EventLookup.Found -> return if (KerugiScoreJournal.sameCommand(existing.event.event, event)) {
                KerugiScoreResult.Applied(existing.event, projectionAt(existing.event), isNew = false)
            } else {
                rejected("Event ID is already assigned to a different command", event)
            }
            JdbcDomainEventStore.EventLookup.AssignedElsewhere ->
                return rejected("Event ID is already assigned to a different command", event)
            JdbcDomainEventStore.EventLookup.Missing -> Unit
        }
        try {
            KerugiScoreJournal.validate(
                event, configuration, ownership, sessionId, events().map(SequencedDomainEvent::event),
            )
        } catch (error: IllegalArgumentException) {
            return rejected(error.message ?: "Invalid Kerugi score candidate", event)
        }
        val sequencedEvent = store.append(event)
        currentProjection = KerugiScoreJournal.rebuild(ownership, sessionId, configuration, events())
        return KerugiScoreResult.Applied(sequencedEvent, currentProjection)
    }

    override fun events(): List<SequencedDomainEvent> = store.sessionEvents(ownership.bracketId, sessionId, EVENT_TYPES)

    override fun projection(): KerugiScoringResult = currentProjection

    private fun projectionAt(event: SequencedDomainEvent): KerugiScoringResult = KerugiScoreJournal.rebuild(
        ownership, sessionId, configuration, events().filter { it.sequence <= event.sequence },
    )

    private fun rejected(reason: String, event: DomainEvent) = KerugiScoreResult.Rejected(reason, event.diagnosticContext())

    private companion object {
        val EVENT_TYPES = setOf(
            KERUGI_SCORE_CANDIDATE_EVENT,
            KERUGI_OPERATOR_ACTION_EVENT,
            KERUGI_SCORE_CORRECTION_EVENT,
            KERUGI_DISQUALIFICATION_EVENT,
        )
    }
}
