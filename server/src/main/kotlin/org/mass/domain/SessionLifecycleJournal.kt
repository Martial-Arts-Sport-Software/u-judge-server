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

interface SessionLifecycleEventJournal {
    fun apply(command: DomainCommand, eventId: EventId): SessionLifecycleResult

    fun events(): List<SequencedDomainEvent>

    fun projection(): SessionProjection
}

class SessionLifecycleJournal(
    private val ownership: BracketOwnership,
    private val sessionId: SessionId,
    private val sequence: PeerEventSequence = PeerEventSequence(ownership.ownerPeerId),
    private val now: () -> Instant = Instant::now,
) : SessionLifecycleEventJournal {
    private val eventsById = linkedMapOf<EventId, SequencedDomainEvent>()
    private val projectionsByEventId = mutableMapOf<EventId, SessionProjection>()
    private var currentProjection = SessionProjection.initial(sessionId)

    override fun apply(command: DomainCommand, eventId: EventId): SessionLifecycleResult {
        val event = command.toEvent(eventId, now())
        eventsById[eventId]?.let { existing ->
            return if (sameCommand(existing.event, event)) {
                SessionLifecycleResult.Applied(existing, projectionsByEventId.getValue(eventId))
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
        projectionsByEventId[eventId] = nextProjection
        currentProjection = nextProjection
        return SessionLifecycleResult.Applied(sequencedEvent, currentProjection)
    }

    override fun events(): List<SequencedDomainEvent> = eventsById.values.toList()

    override fun projection(): SessionProjection = currentProjection

    private fun rejected(reason: String, event: DomainEvent) =
        SessionLifecycleResult.Rejected(reason, event.diagnosticContext())

    private fun sameCommand(existing: DomainEvent, candidate: DomainEvent): Boolean =
        existing.copy(occurredAt = candidate.occurredAt) == candidate

    companion object {
        fun rebuild(
            ownership: BracketOwnership,
            sessionId: SessionId,
            events: Collection<SequencedDomainEvent>,
        ): SessionProjection {
            val eventsById = linkedMapOf<EventId, SequencedDomainEvent>()
            events.forEach { candidate ->
                val existing = eventsById.putIfAbsent(candidate.event.eventId, candidate)
                require(existing == null || existing == candidate) {
                    "Event ID is already assigned to a different event"
                }
            }
            val orderedEvents = DomainEventOrder.order(eventsById.values)
            require(ownership.state == BracketState.IN_PROGRESS) {
                "Only the in-progress bracket owner can change its session"
            }
            require(orderedEvents.all { event ->
                event.event.bracketId == ownership.bracketId &&
                    event.event.sessionId == sessionId &&
                    event.ownerPeerId == ownership.ownerPeerId
            }) { "Event does not belong to this owned session" }
            return SessionProjection.rebuild(sessionId, orderedEvents.map { event ->
                stateFor(event.event.type) ?: error("Unsupported session lifecycle event")
            })
        }

        private fun stateFor(type: String): SessionState? = when (type) {
            "session_started", "session_resumed" -> SessionState.RUNNING
            "session_paused" -> SessionState.PAUSED
            "session_completed" -> SessionState.COMPLETED
            "session_cancelled" -> SessionState.CANCELLED
            else -> null
        }
    }
}

class PeerEventSequence(private val ownerPeerId: PeerId) {
    private var latest = 0L

    @Synchronized
    fun next(requestingPeerId: PeerId): Long {
        require(requestingPeerId == ownerPeerId) { "Only the sequence owner can allocate an event sequence" }
        return ++latest
    }
}
