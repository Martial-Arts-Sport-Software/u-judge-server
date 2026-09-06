package org.mass.domain

import java.time.Instant

sealed interface SessionLifecycleResult {
    data class Applied(
        val event: SequencedDomainEvent,
        val projection: SessionProjection,
    ) : SessionLifecycleResult

    data class Rejected(
        val reason: String,
        val diagnosticContext: DiagnosticContext,
    ) : SessionLifecycleResult
}

class SessionLifecycleJournal(
    private val ownership: BracketOwnership,
    private val sessionId: SessionId,
    private val sequence: PeerEventSequence = PeerEventSequence(ownership.ownerPeerId),
    private val now: () -> Instant = Instant::now,
) {
    private val eventsById = linkedMapOf<EventId, SequencedDomainEvent>()
    private var currentProjection = SessionProjection.initial(sessionId)

    fun apply(command: DomainCommand, eventId: EventId): SessionLifecycleResult {
        val event = command.toEvent(eventId, now())
        eventsById[eventId]?.let { existing ->
            return if (sameCommand(existing.event, event)) {
                SessionLifecycleResult.Applied(existing, currentProjection)
            } else {
                rejected("Event ID is already assigned to a different command", event)
            }
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
        val sequencedEvent = SequencedDomainEvent(event, sequence.next(event.peerId))
        eventsById[eventId] = sequencedEvent
        currentProjection = nextProjection
        return SessionLifecycleResult.Applied(sequencedEvent, currentProjection)
    }

    fun events(): List<SequencedDomainEvent> = eventsById.values.toList()

    fun projection(): SessionProjection = currentProjection

    private fun rejected(reason: String, event: DomainEvent) =
        SessionLifecycleResult.Rejected(reason, event.diagnosticContext())

    private fun stateFor(type: String): SessionState? = when (type) {
        "session_started", "session_resumed" -> SessionState.RUNNING
        "session_paused" -> SessionState.PAUSED
        "session_completed" -> SessionState.COMPLETED
        "session_cancelled" -> SessionState.CANCELLED
        else -> null
    }

    private fun sameCommand(existing: DomainEvent, candidate: DomainEvent): Boolean =
        existing.copy(occurredAt = candidate.occurredAt) == candidate
}

class PeerEventSequence(private val ownerPeerId: PeerId) {
    private var latest = 0L

    @Synchronized
    fun next(requestingPeerId: PeerId): Long {
        require(requestingPeerId == ownerPeerId) { "Only the sequence owner can allocate an event sequence" }
        return ++latest
    }
}
