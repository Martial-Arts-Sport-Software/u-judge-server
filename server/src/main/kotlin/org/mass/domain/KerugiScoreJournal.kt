package org.mass.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

const val KERUGI_SCORE_CANDIDATE_EVENT = "kerugi_score_candidate"
const val KERUGI_OPERATOR_ACTION_EVENT = "kerugi_operator_action"
const val KERUGI_SCORE_CORRECTION_EVENT = "kerugi_score_correction"
const val KERUGI_DISQUALIFICATION_EVENT = "kerugi_disqualification_confirmed"

@Serializable
data class KerugiScoreCandidatePayload(
    val competitor: KerugiCompetitor,
    val area: KerugiScoringArea,
    val occurredAt: String,
)

@Serializable
data class KerugiOperatorActionPayload(
    val type: KerugiOperatorActionType,
    val competitor: KerugiCompetitor,
    val points: Int,
)

@Serializable
data class KerugiScoreCorrectionPayload(val targetEventId: String)

@Serializable
data class KerugiDisqualificationPayload(val competitor: KerugiCompetitor)

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
        try {
            validate(event, configuration, ownership, sessionId, eventsById.values.map(SequencedDomainEvent::event))
        } catch (error: IllegalArgumentException) {
            return rejected(error.message ?: "Invalid Kerugi score candidate", event)
        }
        val sequencedEvent = SequencedDomainEvent(event, sequence.next(event.peerId))
        val nextProjection = score(eventsById.values.map(SequencedDomainEvent::event) + event, configuration)
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
            val accepted = mutableListOf<DomainEvent>()
            ordered.forEach {
                validate(it.event, configuration, ownership, sessionId, accepted)
                accepted += it.event
            }
            return score(ordered.map(SequencedDomainEvent::event), configuration)
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

        fun operatorActionFor(event: DomainEvent): KerugiOperatorAction {
            val payload = Json.decodeFromString<KerugiOperatorActionPayload>(event.payload)
            return KerugiOperatorAction(event.eventId, payload.type, payload.competitor, payload.points)
        }

        fun correctionFor(event: DomainEvent): KerugiScoreCorrection = KerugiScoreCorrection(
            event.eventId,
            EventId(Json.decodeFromString<KerugiScoreCorrectionPayload>(event.payload).targetEventId),
        )

        fun disqualificationFor(event: DomainEvent): KerugiDisqualification = KerugiDisqualification(
            event.eventId.value,
            Json.decodeFromString<KerugiDisqualificationPayload>(event.payload).competitor,
        )

        private fun score(events: Iterable<DomainEvent>, configuration: KerugiScoringConfiguration): KerugiScoringResult {
            val eventList = events.toList()
            return KerugiScoringEngine(configuration).score(
                eventList.filter { it.type == KERUGI_SCORE_CANDIDATE_EVENT }.map(::candidateFor),
                eventList.filter { it.type == KERUGI_OPERATOR_ACTION_EVENT }.map(::operatorActionFor),
                eventList.filter { it.type == KERUGI_SCORE_CORRECTION_EVENT }.map(::correctionFor),
                eventList.filter { it.type == KERUGI_DISQUALIFICATION_EVENT }.map(::disqualificationFor),
            )
        }

        fun validate(
            event: DomainEvent,
            configuration: KerugiScoringConfiguration,
            ownership: BracketOwnership,
            sessionId: SessionId,
            acceptedEvents: Iterable<DomainEvent> = emptyList(),
        ) {
            require(event.bracketId == ownership.bracketId && event.sessionId == sessionId) {
                "Command does not target this session"
            }
            require(event.peerId == ownership.ownerPeerId && ownership.state == BracketState.IN_PROGRESS) {
                "Only the in-progress bracket owner can score its session"
            }
            when (event.type) {
                KERUGI_SCORE_CANDIDATE_EVENT -> {
                    val candidate = candidateFor(event)
                    require(candidate.judgeId in configuration.judges) { "Score candidate judge is not configured for this session" }
                }
                KERUGI_OPERATOR_ACTION_EVENT -> {
                    require(event.source.value == "operator") { "Kerugi operator action must have an operator source" }
                    operatorActionFor(event)
                }
                KERUGI_SCORE_CORRECTION_EVENT -> {
                    require(event.source.value == "operator") { "Kerugi score correction must have an operator source" }
                    val correction = correctionFor(event)
                    val target = acceptedEvents.firstOrNull { it.eventId == correction.targetEventId }
                    require(target?.type in setOf(KERUGI_SCORE_CANDIDATE_EVENT, KERUGI_OPERATOR_ACTION_EVENT)) {
                        "Kerugi score correction must target an accepted score event"
                    }
                    require(acceptedEvents.none {
                        it.type == KERUGI_SCORE_CORRECTION_EVENT && correctionFor(it).targetEventId == correction.targetEventId
                    }) { "Kerugi score event is already corrected" }
                }
                KERUGI_DISQUALIFICATION_EVENT -> {
                    require(event.source.value == "operator") { "Kerugi disqualification must have an operator source" }
                    val disqualification = disqualificationFor(event)
                    require(acceptedEvents.none { it.type == KERUGI_DISQUALIFICATION_EVENT }) {
                        "Kerugi disqualification is already confirmed"
                    }
                    require(
                        score(acceptedEvents, configuration).disqualificationWarnings.any {
                            it.competitor == disqualification.competitor
                        },
                    ) { "Kerugi disqualification requires ten effective Gamjeom" }
                }
                else -> throw IllegalArgumentException("Unsupported Kerugi score command")
            }
        }

        fun sameCommand(existing: DomainEvent, candidate: DomainEvent): Boolean =
            existing.copy(occurredAt = candidate.occurredAt) == candidate
    }
}
