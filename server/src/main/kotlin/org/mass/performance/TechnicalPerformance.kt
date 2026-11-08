package org.mass.performance

import kotlinx.serialization.json.Json
import org.mass.bout.BoutCancelledPayload
import org.mass.bout.BoutSeat
import org.mass.domain.AggregationFormula
import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.CourtId
import org.mass.domain.EventId
import org.mass.domain.JudgeVector
import org.mass.domain.PeerId
import org.mass.domain.SequencedDomainEvent
import org.mass.domain.SessionId
import org.mass.domain.SessionProjection
import org.mass.domain.SessionState
import org.mass.domain.TechnicalDiscipline
import org.mass.domain.TechnicalResult
import org.mass.domain.TechnicalScoring
import java.time.Instant

/** One accepted Send of a seat. */
data class TechnicalSend(
    val eventId: EventId,
    val seat: Int,
    val vector: JudgeVector,
    val totalTenths: Int,
    val receivedAt: Instant,
    val sequence: Long,
)

/** An arbiter adjustment; a withdrawn one stays visible in the history. */
data class TechnicalAdjustment(
    val eventId: EventId,
    val kind: TechnicalAdjustmentKind,
    val amountTenths: Int,
    val reason: String,
    val author: String,
    val at: Instant,
    val withdrawn: Boolean,
)

/**
 * One technical performance (one session) rebuilt from its events in journal order (ADR-011 §5-§9): the start with the
 * entry, criteria, roster and formula, the operator's transitions, the judges' final vectors, the arbiter's adjustments
 * and the completion. The result is a pure function of the raw inputs, so a restart gives the same total.
 */
class TechnicalPerformance private constructor(val events: List<SequencedDomainEvent>) {
    private val startEvent = events.first().also { require(it.event.type == TechnicalPerformanceEvents.PERFORMANCE_STARTED) }.event
    val started: TechnicalPerformanceStartedPayload = Json.decodeFromString(startEvent.payload)
    val discipline: TechnicalDiscipline = requireNotNull(TechnicalDiscipline.ofCode(started.discipline))
    val formula: AggregationFormula = requireNotNull(AggregationFormula.ofCode(started.formula))
    val sessionId: SessionId = startEvent.sessionId
    val competitionId: CompetitionId = startEvent.competitionId
    val peerId: PeerId = startEvent.peerId
    val courtId: CourtId = startEvent.courtId
    val bracketId: BracketId = startEvent.bracketId
    val startSequence: Long = events.first().sequence

    val state: SessionState
    val sends: Map<Int, TechnicalSend>
    val adjustments: List<TechnicalAdjustment>
    val completion: TechnicalCompletedPayload?
    val cancelReason: String?

    init {
        var projection = SessionProjection.initial(sessionId)
        val sent = linkedMapOf<Int, TechnicalSend>()
        val applied = mutableListOf<TechnicalAdjustment>()
        val withdrawn = mutableSetOf<EventId>()
        var completed: TechnicalCompletedPayload? = null
        var cancelled: String? = null
        events.drop(1).forEach { sequenced ->
            val event = sequenced.event
            require(event.sessionId == sessionId) { "Event does not belong to this performance" }
            when (event.type) {
                TechnicalPerformanceEvents.SESSION_STARTED -> projection = projection.transitionTo(SessionState.RUNNING)
                TechnicalPerformanceEvents.SESSION_COMPLETED -> {
                    projection = projection.transitionTo(SessionState.COMPLETED)
                    completed = Json.decodeFromString<TechnicalCompletedPayload>(event.payload)
                }
                TechnicalPerformanceEvents.SESSION_CANCELLED -> {
                    projection = projection.transitionTo(SessionState.CANCELLED)
                    cancelled = Json.decodeFromString<BoutCancelledPayload>(event.payload).reason
                }
                TechnicalPerformanceEvents.SCORE_SENT -> {
                    val payload = Json.decodeFromString<TechnicalScoreSentPayload>(event.payload)
                    val seat = requireNotNull(seatOfJudge(event.judgeId.value)).number
                    sent[seat] = TechnicalSend(
                        event.eventId, seat, JudgeVector(payload.techniqueTenths, payload.presentationTenths, payload.deductionTenths),
                        payload.totalTenths, event.occurredAt, sequenced.sequence,
                    )
                }
                TechnicalPerformanceEvents.ADJUSTMENT_APPLIED -> {
                    val payload = Json.decodeFromString<TechnicalAdjustmentPayload>(event.payload)
                    applied += TechnicalAdjustment(event.eventId, payload.kind, payload.amountTenths, payload.reason, event.author, event.occurredAt, false)
                }
                TechnicalPerformanceEvents.ADJUSTMENT_WITHDRAWN ->
                    withdrawn += EventId(Json.decodeFromString<TechnicalAdjustmentWithdrawnPayload>(event.payload).targetEventId)
                else -> error("Unsupported technical performance event ${event.type}")
            }
        }
        state = projection.state
        sends = sent
        adjustments = applied.map { it.copy(withdrawn = it.eventId in withdrawn) }
        completion = completed
        cancelReason = cancelled
    }

    val closed: Boolean
        get() = state == SessionState.COMPLETED || state == SessionState.CANCELLED

    val seats: List<BoutSeat>
        get() = started.seats

    fun with(event: SequencedDomainEvent): TechnicalPerformance = TechnicalPerformance(events + event)

    fun seatOf(deviceId: String): BoutSeat? = started.seats.firstOrNull { it.deviceId == deviceId }

    fun seatOfJudge(judgeId: String): BoutSeat? = started.seats.firstOrNull { it.judgeId == judgeId }

    /** Seats that sent, in seat order (`TEC-014`). */
    fun submittedSeats(): List<Int> = sends.keys.sorted()

    fun missingSeats(): List<Int> = started.seats.map(BoutSeat::number).filter { it !in sends }

    fun effectiveAdjustments(): List<TechnicalAdjustment> = adjustments.filterNot(TechnicalAdjustment::withdrawn)

    /** The score from the sent vectors, or null while fewer than the formula needs have sent. */
    fun currentResult(): TechnicalResult? = sends.values.sortedBy(TechnicalSend::seat).map(TechnicalSend::vector)
        .takeIf { it.size >= formula.minimumScores }
        ?.let { TechnicalScoring.result(discipline, it, formula, effectiveAdjustments().map(TechnicalAdjustment::amountTenths)) }

    /** The fixed result once the performance is completed. */
    fun result(): TechnicalResult? = if (state == SessionState.COMPLETED) currentResult() else null

    companion object {
        fun start(event: SequencedDomainEvent): TechnicalPerformance = TechnicalPerformance(listOf(event))
    }
}
