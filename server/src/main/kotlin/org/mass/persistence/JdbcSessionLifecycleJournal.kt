package org.mass.persistence

import org.mass.domain.BracketOwnership
import org.mass.domain.BracketState
import org.mass.domain.DomainCommand
import org.mass.domain.DomainEvent
import org.mass.domain.EventId
import org.mass.domain.SessionId
import org.mass.domain.SessionLifecycleEventJournal
import org.mass.domain.SessionLifecycleJournal
import org.mass.domain.SessionLifecycleResult
import org.mass.domain.SessionProjection
import org.mass.domain.SessionState
import org.mass.domain.SequencedDomainEvent
import org.mass.domain.diagnosticContext
import java.time.Instant
import javax.sql.DataSource

class JdbcSessionLifecycleJournal(
    private val store: JdbcDomainEventStore,
    private val ownership: BracketOwnership,
    private val sessionId: SessionId,
    private val now: () -> Instant = Instant::now,
) : SessionLifecycleEventJournal {
    constructor(
        dataSource: DataSource,
        ownership: BracketOwnership,
        sessionId: SessionId,
        now: () -> Instant = Instant::now,
    ) : this(JdbcDomainEventStore(dataSource), ownership, sessionId, now)

    private var currentProjection: SessionProjection = SessionLifecycleJournal.rebuild(ownership, sessionId, events())

    @Synchronized
    override fun apply(command: DomainCommand, eventId: EventId): SessionLifecycleResult {
        val event = command.toEvent(eventId, now())
        when (val existing = store.find(eventId, EVENT_TYPES)) {
            is JdbcDomainEventStore.EventLookup.Found -> return if (sameCommand(existing.event.event, event)) {
                SessionLifecycleResult.Applied(existing.event, projectionAt(existing.event), isNew = false)
            } else {
                rejected("Event ID is already assigned to a different command", event)
            }
            JdbcDomainEventStore.EventLookup.AssignedElsewhere ->
                return rejected("Event ID is already assigned to a different command", event)
            JdbcDomainEventStore.EventLookup.Missing -> Unit
        }
        val nextState = stateFor(event.type) ?: return rejected("Unsupported session lifecycle command", event)
        if (event.bracketId != ownership.bracketId || event.sessionId != sessionId) {
            return rejected("Command does not target this session", event)
        }
        if (event.peerId != ownership.ownerPeerId || ownership.state != BracketState.IN_PROGRESS) {
            return rejected("Only the in-progress bracket owner can change its session", event)
        }
        val nextProjection = try {
            currentProjection.transitionTo(nextState)
        } catch (error: IllegalStateException) {
            return rejected(error.message ?: "Invalid session transition", event)
        }
        val sequencedEvent = store.append(event)
        currentProjection = nextProjection
        return SessionLifecycleResult.Applied(sequencedEvent, currentProjection)
    }

    override fun events(): List<SequencedDomainEvent> = store.sessionEvents(ownership.bracketId, sessionId, EVENT_TYPES)

    override fun projection(): SessionProjection = currentProjection

    private fun projectionAt(event: SequencedDomainEvent): SessionProjection = SessionLifecycleJournal.rebuild(
        ownership,
        sessionId,
        events().filter { it.sequence <= event.sequence },
    )

    private fun rejected(reason: String, event: DomainEvent) =
        SessionLifecycleResult.Rejected(reason, event.diagnosticContext())

    private fun sameCommand(existing: DomainEvent, candidate: DomainEvent): Boolean =
        existing.copy(occurredAt = candidate.occurredAt) == candidate

    private fun stateFor(type: String): SessionState? = when (type) {
        "session_started", "session_resumed" -> SessionState.RUNNING
        "session_paused" -> SessionState.PAUSED
        "session_completed" -> SessionState.COMPLETED
        "session_cancelled" -> SessionState.CANCELLED
        else -> null
    }

    private companion object {
        val EVENT_TYPES = setOf(
            "session_started",
            "session_resumed",
            "session_paused",
            "session_completed",
            "session_cancelled",
        )
    }
}
