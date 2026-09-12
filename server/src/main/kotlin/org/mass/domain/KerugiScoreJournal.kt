package org.mass.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

const val KERUGI_SCORE_CANDIDATE_EVENT = "kerugi_score_candidate"

@Serializable
data class KerugiScoreCandidatePayload(
    val competitor: KerugiCompetitor,
    val area: KerugiScoringArea,
    val occurredAt: String,
)

sealed interface KerugiScoreResult {
    class Applied(
        val event: SequencedDomainEvent,
        val projection: KerugiScoringResult,
        val isNew: Boolean = true,
    ) : KerugiScoreResult

    data class Rejected(val reason: String, val diagnosticContext: DiagnosticContext) : KerugiScoreResult
}

interface KerugiScoreEventJournal {
    fun apply(command: DomainCommand, eventId: EventId): KerugiScoreResult

    fun events(): List<SequencedDomainEvent>

    fun projection(): KerugiScoringResult
}

class KerugiScoreJournal(
    private val ownership: BracketOwnership,
    private val sessionId: SessionId,
    private val configuration: KerugiScoringConfiguration,
    private val sequence: PeerEventSequence = PeerEventSequence(ownership.ownerPeerId),
    private val now: () -> Instant = Instant::now,
) : KerugiScoreEventJournal {
    private val eventsById = linkedMapOf<EventId, SequencedDomainEvent>()
    private val projectionsByEventId = mutableMapOf<EventId, KerugiScoringResult>()
    private var currentProjection = KerugiScoringResult(emptyList(), emptyList())

    override fun apply(command: DomainCommand, eventId: EventId): KerugiScoreResult {
        val event = command.toEvent(eventId, now())
        eventsById[eventId]?.let { existing ->
            return if (sameCommand(existing.event, event)) {
                KerugiScoreResult.Applied(existing, projectionsByEventId.getValue(eventId), isNew = false)
            } else {
                rejected("Event ID is already assigned to a different command", event)
            }
        }
        val candidate = try {
            validate(event, configuration, ownership, sessionId)
        } catch (error: IllegalArgumentException) {
            return rejected(error.message ?: "Invalid Kerugi score candidate", event)
        }
        val sequencedEvent = SequencedDomainEvent(event, sequence.next(event.peerId))
        val nextProjection = KerugiScoringEngine(configuration).score(
            eventsById.values.map { candidateFor(it.event) } + candidate,
        )
        eventsById[eventId] = sequencedEvent
        projectionsByEventId[eventId] = nextProjection
        currentProjection = nextProjection
        return KerugiScoreResult.Applied(sequencedEvent, currentProjection)
    }

    override fun events(): List<SequencedDomainEvent> = eventsById.values.toList()

    override fun projection(): KerugiScoringResult = currentProjection

    private fun rejected(reason: String, event: DomainEvent) = KerugiScoreResult.Rejected(reason, event.diagnosticContext())

    companion object {
        fun rebuild(
            ownership: BracketOwnership,
            sessionId: SessionId,
            configuration: KerugiScoringConfiguration,
            events: Collection<SequencedDomainEvent>,
        ): KerugiScoringResult {
            val eventsById = linkedMapOf<EventId, SequencedDomainEvent>()
            events.forEach { candidate ->
                val existing = eventsById.putIfAbsent(candidate.event.eventId, candidate)
                require(existing == null || existing == candidate) { "Event ID is already assigned to a different event" }
            }
            val ordered = DomainEventOrder.order(eventsById.values)
            return KerugiScoringEngine(configuration).score(ordered.map { event ->
                validate(event.event, configuration, ownership, sessionId)
            })
        }

        fun candidateFor(event: DomainEvent): KerugiScoreCandidate {
            val payload = Json.decodeFromString<KerugiScoreCandidatePayload>(event.payload)
            return KerugiScoreCandidate(
                eventId = event.eventId,
                judgeId = event.judgeId,
                competitor = payload.competitor,
                area = payload.area,
                occurredAt = Instant.parse(payload.occurredAt),
            )
        }

        fun validate(
            event: DomainEvent,
            configuration: KerugiScoringConfiguration,
            ownership: BracketOwnership,
            sessionId: SessionId,
        ): KerugiScoreCandidate {
            require(event.type == KERUGI_SCORE_CANDIDATE_EVENT) { "Unsupported Kerugi score command" }
            require(event.bracketId == ownership.bracketId && event.sessionId == sessionId) {
                "Command does not target this session"
            }
            require(event.peerId == ownership.ownerPeerId && ownership.state == BracketState.IN_PROGRESS) {
                "Only the in-progress bracket owner can score its session"
            }
            val candidate = candidateFor(event)
            require(candidate.judgeId in configuration.judges) { "Score candidate judge is not configured for this session" }
            return candidate
        }

        fun sameCommand(existing: DomainEvent, candidate: DomainEvent): Boolean =
            existing.copy(occurredAt = candidate.occurredAt) == candidate
    }
}
