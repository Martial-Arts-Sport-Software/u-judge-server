package org.mass.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

const val KERUGI_RESULT_DECLARED_EVENT = "kerugi_result_declared"

@Serializable
enum class KerugiVictoryReason { FINAL_SCORE, GOLDEN_ROUND }

@Serializable
data class KerugiResultPayload(
    val winner: KerugiCompetitor,
    val reason: KerugiVictoryReason,
)

data class KerugiResultProjection(
    val sessionId: SessionId,
    val winner: KerugiCompetitor,
    val reason: KerugiVictoryReason,
)

sealed interface KerugiResultJournalResult {
    class Applied(
        val event: SequencedDomainEvent,
        val projection: KerugiResultProjection,
        val isNew: Boolean = true,
    ) : KerugiResultJournalResult

    data class Rejected(val reason: String, val diagnosticContext: DiagnosticContext) : KerugiResultJournalResult
}

interface KerugiResultEventJournal {
    fun apply(command: DomainCommand, eventId: EventId): KerugiResultJournalResult
    fun events(): List<SequencedDomainEvent>
    fun projection(): KerugiResultProjection?
}

class KerugiResultJournal(
    private val ownership: BracketOwnership,
    private val sessionId: SessionId,
    private val sequence: PeerEventSequence = PeerEventSequence(ownership.ownerPeerId),
    private val now: () -> Instant = Instant::now,
) : KerugiResultEventJournal {
    private val eventsById = linkedMapOf<EventId, SequencedDomainEvent>()
    private val projectionsByEventId = mutableMapOf<EventId, KerugiResultProjection>()
    private var currentProjection: KerugiResultProjection? = null

    override fun apply(command: DomainCommand, eventId: EventId): KerugiResultJournalResult {
        val event = command.toEvent(eventId, now())
        eventsById[eventId]?.let { existing ->
            return if (sameCommand(existing.event, event)) {
                KerugiResultJournalResult.Applied(existing, projectionsByEventId.getValue(eventId), false)
            } else {
                rejected("Event ID is already assigned to a different command", event)
            }
        }
        val nextProjection = try {
            validate(event, ownership, sessionId, eventsById.values.map(SequencedDomainEvent::event))
            projectionFor(event)
        } catch (error: IllegalArgumentException) {
            return rejected(error.message ?: "Invalid Kerugi result command", event)
        }
        val sequenced = SequencedDomainEvent(event, sequence.next(event.peerId))
        eventsById[eventId] = sequenced
        projectionsByEventId[eventId] = nextProjection
        currentProjection = nextProjection
        return KerugiResultJournalResult.Applied(sequenced, nextProjection)
    }

    override fun events(): List<SequencedDomainEvent> = eventsById.values.toList()
    override fun projection(): KerugiResultProjection? = currentProjection

    private fun rejected(reason: String, event: DomainEvent) = KerugiResultJournalResult.Rejected(reason, event.diagnosticContext())

    companion object {
        fun rebuild(
            ownership: BracketOwnership,
            sessionId: SessionId,
            events: Collection<SequencedDomainEvent>,
        ): KerugiResultProjection? {
            val eventsById = linkedMapOf<EventId, SequencedDomainEvent>()
            events.forEach { event ->
                require(eventsById.putIfAbsent(event.event.eventId, event) in setOf(null, event)) {
                    "Event ID is already assigned to a different event"
                }
            }
            val accepted = mutableListOf<DomainEvent>()
            return DomainEventOrder.order(eventsById.values).fold(null as KerugiResultProjection?) { _, event ->
                validate(event.event, ownership, sessionId, accepted)
                accepted += event.event
                projectionFor(event.event)
            }
        }

        fun projectionFor(event: DomainEvent): KerugiResultProjection {
            require(event.type == KERUGI_RESULT_DECLARED_EVENT) { "Unsupported Kerugi result command" }
            val payload = Json.decodeFromString<KerugiResultPayload>(event.payload)
            return KerugiResultProjection(event.sessionId, payload.winner, payload.reason)
        }

        fun validate(
            event: DomainEvent,
            ownership: BracketOwnership,
            sessionId: SessionId,
            acceptedEvents: Iterable<DomainEvent> = emptyList(),
        ) {
            require(event.bracketId == ownership.bracketId && event.sessionId == sessionId) {
                "Command does not target this session"
            }
            require(event.peerId == ownership.ownerPeerId && ownership.state == BracketState.IN_PROGRESS) {
                "Only the in-progress bracket owner can declare its result"
            }
            require(event.source.value == "operator") { "Kerugi result command must have an operator source" }
            projectionFor(event)
            require(acceptedEvents.none { it.type == KERUGI_RESULT_DECLARED_EVENT }) {
                "Kerugi session result is already declared"
            }
        }

        fun sameCommand(existing: DomainEvent, candidate: DomainEvent): Boolean =
            existing.copy(occurredAt = candidate.occurredAt) == candidate
    }
}
