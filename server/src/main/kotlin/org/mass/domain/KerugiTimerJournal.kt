package org.mass.domain

import java.time.Instant

enum class KerugiTimerState { PREPARED, RUNNING, PAUSED, STOPPED }

data class KerugiTimerProjection(val sessionId: SessionId, val state: KerugiTimerState) {
    fun transitionTo(next: KerugiTimerState): KerugiTimerProjection {
        require(next in allowedTransitions.getValue(state)) { "Invalid Kerugi timer transition from $state to $next" }
        return copy(state = next)
    }

    companion object {
        fun initial(sessionId: SessionId) = KerugiTimerProjection(sessionId, KerugiTimerState.PREPARED)

        private val allowedTransitions = mapOf(
            KerugiTimerState.PREPARED to setOf(KerugiTimerState.RUNNING),
            KerugiTimerState.RUNNING to setOf(KerugiTimerState.PAUSED, KerugiTimerState.STOPPED),
            KerugiTimerState.PAUSED to setOf(KerugiTimerState.RUNNING, KerugiTimerState.STOPPED),
            KerugiTimerState.STOPPED to emptySet(),
        )
    }
}

sealed interface KerugiTimerResult {
    class Applied(val event: SequencedDomainEvent, val projection: KerugiTimerProjection, val isNew: Boolean = true) : KerugiTimerResult
    data class Rejected(val reason: String, val diagnosticContext: DiagnosticContext) : KerugiTimerResult
}

interface KerugiTimerEventJournal {
    fun apply(command: DomainCommand, eventId: EventId): KerugiTimerResult
    fun events(): List<SequencedDomainEvent>
    fun projection(): KerugiTimerProjection
}

class KerugiTimerJournal(
    private val ownership: BracketOwnership,
    private val sessionId: SessionId,
    private val sequence: PeerEventSequence = PeerEventSequence(ownership.ownerPeerId),
    private val now: () -> Instant = Instant::now,
) : KerugiTimerEventJournal {
    private val eventsById = linkedMapOf<EventId, SequencedDomainEvent>()
    private val projectionsByEventId = mutableMapOf<EventId, KerugiTimerProjection>()
    private var currentProjection = KerugiTimerProjection.initial(sessionId)

    override fun apply(command: DomainCommand, eventId: EventId): KerugiTimerResult {
        val event = command.toEvent(eventId, now())
        eventsById[eventId]?.let { existing ->
            return if (sameCommand(existing.event, event)) KerugiTimerResult.Applied(existing, projectionsByEventId.getValue(eventId), false)
            else rejected("Event ID is already assigned to a different command", event)
        }
        val nextState = try { validate(event, ownership, sessionId) } catch (error: IllegalArgumentException) {
            return rejected(error.message ?: "Invalid Kerugi timer command", event)
        }
        val nextProjection = try { currentProjection.transitionTo(nextState) } catch (error: IllegalArgumentException) {
            return rejected(error.message ?: "Invalid Kerugi timer transition", event)
        }
        val sequenced = SequencedDomainEvent(event, sequence.next(event.peerId))
        eventsById[eventId] = sequenced
        projectionsByEventId[eventId] = nextProjection
        currentProjection = nextProjection
        return KerugiTimerResult.Applied(sequenced, nextProjection)
    }

    override fun events(): List<SequencedDomainEvent> = eventsById.values.toList()
    override fun projection(): KerugiTimerProjection = currentProjection

    private fun rejected(reason: String, event: DomainEvent) = KerugiTimerResult.Rejected(reason, event.diagnosticContext())

    companion object {
        fun rebuild(ownership: BracketOwnership, sessionId: SessionId, events: Collection<SequencedDomainEvent>): KerugiTimerProjection {
            val byId = linkedMapOf<EventId, SequencedDomainEvent>()
            events.forEach { event -> require(byId.putIfAbsent(event.event.eventId, event) in setOf(null, event)) { "Event ID is already assigned to a different event" } }
            return DomainEventOrder.order(byId.values).fold(KerugiTimerProjection.initial(sessionId)) { projection, event ->
                projection.transitionTo(validate(event.event, ownership, sessionId))
            }
        }

        fun stateFor(type: String): KerugiTimerState? = when (type) {
            "kerugi_timer_started", "kerugi_timer_resumed" -> KerugiTimerState.RUNNING
            "kerugi_timer_paused" -> KerugiTimerState.PAUSED
            "kerugi_timer_stopped" -> KerugiTimerState.STOPPED
            else -> null
        }

        fun validate(event: DomainEvent, ownership: BracketOwnership, sessionId: SessionId): KerugiTimerState {
            require(event.bracketId == ownership.bracketId && event.sessionId == sessionId) { "Command does not target this session" }
            require(event.peerId == ownership.ownerPeerId && ownership.state == BracketState.IN_PROGRESS) {
                "Only the in-progress bracket owner can change its timer"
            }
            require(event.source.value == "operator") { "Kerugi timer command must have an operator source" }
            return requireNotNull(stateFor(event.type)) { "Unsupported Kerugi timer command" }
        }

        fun sameCommand(existing: DomainEvent, candidate: DomainEvent): Boolean = existing.copy(occurredAt = candidate.occurredAt) == candidate
    }
}
