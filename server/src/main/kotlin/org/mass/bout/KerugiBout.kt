package org.mass.bout

import kotlinx.serialization.json.Json
import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.CourtId
import org.mass.domain.EventId
import org.mass.domain.JudgeId
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiDisqualification
import org.mass.domain.KerugiDisqualificationWarning
import org.mass.domain.KerugiOperatorAction
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.KerugiScoreCandidate
import org.mass.domain.KerugiScoreCorrection
import org.mass.domain.KerugiScoringConfiguration
import org.mass.domain.KerugiScoringEngine
import org.mass.domain.KerugiScoringResult
import org.mass.domain.KerugiWindowDecision
import org.mass.domain.PeerId
import org.mass.domain.SequencedDomainEvent
import org.mass.domain.SessionId
import org.mass.domain.SessionProjection
import org.mass.domain.SessionState
import java.time.Duration
import java.time.Instant

/** The authoritative score of a bout: what the snapshot and `kerugi_score_updated` carry (ADR-009 §3). */
data class BoutScore(
    val blue: Int,
    val red: Int,
    /** Effective Gamjeom against each side. */
    val gamjeomBlue: Int,
    val gamjeomRed: Int,
    /** Sides with 10 effective Gamjeom waiting for the operator's confirmation (`KER-015`). */
    val warnings: List<KerugiDisqualificationWarning>,
    val disqualified: KerugiCompetitor?,
)

data class BoutResult(val winner: KerugiCompetitor, val reason: String)

data class BoutAttention(val eventId: EventId, val seat: Int?, val clientTimestamp: Instant, val receivedAt: Instant)

/** Why an input did or did not count (`KER-009`); the operator sees it in the bout history. */
enum class BoutEntryStatus { COUNTED, NO_QUORUM, OUTSIDE_RUNNING_PERIOD, CORRECTED, RESET, APPLIED, RECORDED }

/** One journaled input of the bout in journal order. */
data class BoutHistoryEntry(
    val eventId: EventId,
    val type: String,
    val receivedAt: Instant,
    val author: String,
    val seat: Int?,
    val competitor: KerugiCompetitor?,
    val detail: String,
    val status: BoutEntryStatus,
) {
    /** A candidate or an operator action whose effect a correction can still remove (`KER-008`). */
    val correctable: Boolean
        get() = status in setOf(BoutEntryStatus.COUNTED, BoutEntryStatus.NO_QUORUM, BoutEntryStatus.OUTSIDE_RUNNING_PERIOD, BoutEntryStatus.APPLIED)
}

/**
 * One Kerugi bout (one session) rebuilt from its events in journal order (ADR-009): the start with roster and settings,
 * operator transitions, judge candidates, operator actions, corrections, the reset, the disqualification and the result.
 * The projection is a pure function of the events, so a restart gives the same score.
 */
class KerugiBout private constructor(val events: List<SequencedDomainEvent>) {
    private val startEvent = events.first().also { require(it.event.type == KerugiBoutEvents.BOUT_STARTED) }.event
    val started: KerugiBoutStartedPayload = Json.decodeFromString(startEvent.payload)
    val sessionId: SessionId = startEvent.sessionId
    val competitionId: CompetitionId = startEvent.competitionId
    val peerId: PeerId = startEvent.peerId
    val courtId: CourtId = startEvent.courtId
    val bracketId: BracketId = startEvent.bracketId
    val lastSequence: Long = events.last().sequence

    val configuration = KerugiScoringConfiguration(
        judges = started.seats.map { JudgeId(it.judgeId) }.toSet(),
        quorum = started.quorum,
        coincidenceWindow = Duration.ofMillis(started.coincidenceWindowMillis),
    )

    val state: SessionState
    val result: BoutResult?
    val attention: List<BoutAttention>

    /** The clock of the bout (ADR-010 §2). */
    val clock: KerugiClock

    /**
     * The latest time saved with the bout: an event's `occurredAt` or a candidate's `clientTimestamp`, which may run a
     * little ahead of the server clock. A restart stops the clock there (ADR-010 §3).
     */
    val lastEventAt: Instant
    private val candidates = mutableListOf<KerugiScoreCandidate>()
    private val actions = mutableListOf<KerugiOperatorAction>()
    private val corrections = mutableListOf<KerugiScoreCorrection>()
    private val resetTargets = mutableSetOf<EventId>()
    private val disqualification: KerugiDisqualification?
    val scoring: KerugiScoringResult
    private val outsideRunningPeriod: Set<EventId>

    init {
        var projection = SessionProjection.initial(sessionId)
        var completed: BoutResult? = null
        var disqualified: KerugiDisqualification? = null
        var time = KerugiClock(started.timing)
        val signals = mutableListOf<BoutAttention>()
        // The score at a period end decides whether the golden round follows (ADR-010 §2).
        val tied = {
            val score = scoreWith(time, disqualified)
            disqualified == null && score.blueScore == score.redScore
        }
        events.drop(1).forEach { sequenced ->
            val event = sequenced.event
            require(event.sessionId == sessionId) { "Event does not belong to this bout" }
            val at = event.occurredAt
            when (event.type) {
                KerugiBoutEvents.SESSION_STARTED -> {
                    projection = projection.transitionTo(SessionState.RUNNING)
                    time = time.start(at)
                }
                KerugiBoutEvents.SESSION_RESUMED -> {
                    projection = projection.transitionTo(SessionState.RUNNING)
                    time = time.resume(at)
                }
                KerugiBoutEvents.SESSION_PAUSED -> {
                    projection = projection.transitionTo(SessionState.PAUSED)
                    time = time.stop(at, tied)
                }
                KerugiBoutEvents.PERIOD_ENDED -> {
                    if (projection.state == SessionState.RUNNING) projection = projection.transitionTo(SessionState.PAUSED)
                    time = time.endPeriod(at, tied)
                }
                KerugiBoutEvents.CLOCK_STOPPED -> {
                    val stoppedAt = Instant.parse(Json.decodeFromString<BoutClockStoppedPayload>(event.payload).stoppedAt)
                    if (projection.state == SessionState.RUNNING) projection = projection.transitionTo(SessionState.PAUSED)
                    // A restored round stands at the saved time; a restored break ends (ADR-010 §3).
                    time = if (time.period == BoutPeriod.BREAK) time.endPeriod(stoppedAt, tied) else time.stop(stoppedAt, tied)
                }
                KerugiBoutEvents.CLOCK_ADJUSTED ->
                    time = time.adjust(Json.decodeFromString<BoutClockAdjustedPayload>(event.payload).remainingMillis)
                KerugiBoutEvents.CLOCK_SAVED -> Unit
                KerugiBoutEvents.SESSION_COMPLETED, KerugiBoutEvents.SESSION_CANCELLED -> {
                    projection = projection.transitionTo(
                        if (event.type == KerugiBoutEvents.SESSION_COMPLETED) SessionState.COMPLETED else SessionState.CANCELLED,
                    )
                    time = time.halt(at)
                    if (event.type == KerugiBoutEvents.SESSION_COMPLETED) {
                        val payload = Json.decodeFromString<BoutCompletedPayload>(event.payload)
                        completed = BoutResult(payload.winner, payload.reason)
                    }
                }
                KerugiBoutEvents.SCORE_CANDIDATE -> {
                    val payload = Json.decodeFromString<BoutScoreCandidatePayload>(event.payload)
                    candidates += KerugiScoreCandidate(
                        event.eventId, event.judgeId, payload.competitor, payload.area, Instant.parse(payload.clientTimestamp),
                    )
                }
                KerugiBoutEvents.OPERATOR_ACTION -> {
                    val payload = Json.decodeFromString<BoutOperatorActionPayload>(event.payload)
                    actions += KerugiOperatorAction(event.eventId, payload.type, payload.competitor, payload.points)
                }
                KerugiBoutEvents.SCORE_CORRECTION -> corrections += KerugiScoreCorrection(
                    event.eventId, EventId(Json.decodeFromString<BoutCorrectionPayload>(event.payload).targetEventId),
                )
                KerugiBoutEvents.BOUT_RESET -> Json.decodeFromString<BoutResetPayload>(event.payload).targetEventIds.forEach {
                    resetTargets += EventId(it)
                }
                KerugiBoutEvents.DISQUALIFICATION_CONFIRMED -> disqualified = KerugiDisqualification(
                    event.eventId.value, Json.decodeFromString<BoutDisqualificationPayload>(event.payload).competitor,
                )
                KerugiBoutEvents.JUDGE_ATTENTION -> signals += BoutAttention(
                    event.eventId,
                    seatOfJudge(event.judgeId.value)?.number,
                    Instant.parse(Json.decodeFromString<BoutAttentionPayload>(event.payload).clientTimestamp),
                    event.occurredAt,
                )
                else -> error("Unsupported Kerugi bout event ${event.type}")
            }
        }
        state = projection.state
        result = completed
        attention = signals
        clock = time
        lastEventAt = (events.map { it.event.occurredAt } + candidates.map { it.occurredAt }).max()
        disqualification = disqualified
        outsideRunningPeriod = candidates.filterNot { time.counts(it.occurredAt) }.map { it.eventId }.toSet()
        scoring = scoreWith(time, disqualified)
    }

    /** The scoring of the inputs so far, with candidates outside fighting time of [time] left out (`KER-009`). */
    private fun scoreWith(time: KerugiClock, disqualified: KerugiDisqualification?): KerugiScoringResult {
        val outside = candidates.filterNot { time.counts(it.occurredAt) }.map { it.eventId }.toSet()
        return KerugiScoringEngine(configuration).score(
            candidates.filter { it.eventId !in outside && it.eventId !in resetTargets },
            actions.filter { it.eventId !in resetTargets },
            corrections,
            listOfNotNull(disqualified),
        )
    }

    val closed: Boolean
        get() = state == SessionState.COMPLETED || state == SessionState.CANCELLED

    fun with(event: SequencedDomainEvent): KerugiBout = KerugiBout(events + event)

    fun seatOf(deviceId: String): BoutSeat? = started.seats.firstOrNull { it.deviceId == deviceId }

    fun seatOfJudge(judgeId: String): BoutSeat? = started.seats.firstOrNull { it.judgeId == judgeId }

    fun score(): BoutScore {
        val gamjeom = scoring.operatorActions.filter { it.type == KerugiOperatorActionType.GAMJEOM }
        return BoutScore(
            blue = scoring.blueScore,
            red = scoring.redScore,
            gamjeomBlue = gamjeom.filter { it.competitor == KerugiCompetitor.BLUE }.sumOf { it.points },
            gamjeomRed = gamjeom.filter { it.competitor == KerugiCompetitor.RED }.sumOf { it.points },
            warnings = if (disqualification == null) scoring.disqualificationWarnings else emptyList(),
            disqualified = disqualification?.competitor,
        )
    }

    /** The IDs of every candidate and operator action that still affects the projection; a reset compensates them. */
    fun effectiveScoreEventIds(): List<EventId> {
        val corrected = corrections.map { it.targetEventId }.toSet()
        return events.map { it.event }
            .filter { it.type == KerugiBoutEvents.SCORE_CANDIDATE || it.type == KerugiBoutEvents.OPERATOR_ACTION }
            .map { it.eventId }
            .filter { it !in corrected && it !in resetTargets }
    }

    fun history(): List<BoutHistoryEntry> {
        val corrected = corrections.map { it.targetEventId }.toSet()
        val windows = scoring.audit.flatMap { audit -> audit.candidateEventIds.map { it to audit.decision } }.toMap()
        return events.map(SequencedDomainEvent::event).map { event ->
            val seat = seatOfJudge(event.judgeId.value)?.number
            val (competitor, detail) = when (event.type) {
                KerugiBoutEvents.SCORE_CANDIDATE -> Json.decodeFromString<BoutScoreCandidatePayload>(event.payload)
                    .let { it.competitor to it.area.name }
                KerugiBoutEvents.OPERATOR_ACTION -> Json.decodeFromString<BoutOperatorActionPayload>(event.payload)
                    .let { it.competitor to "${it.type.name}:${it.points}" }
                KerugiBoutEvents.SCORE_CORRECTION -> null to Json.decodeFromString<BoutCorrectionPayload>(event.payload).targetEventId
                KerugiBoutEvents.BOUT_RESET -> null to Json.decodeFromString<BoutResetPayload>(event.payload).reason
                KerugiBoutEvents.SESSION_CANCELLED -> null to Json.decodeFromString<BoutCancelledPayload>(event.payload).reason
                KerugiBoutEvents.SESSION_COMPLETED -> Json.decodeFromString<BoutCompletedPayload>(event.payload)
                    .let { it.winner to it.reason }
                KerugiBoutEvents.DISQUALIFICATION_CONFIRMED ->
                    Json.decodeFromString<BoutDisqualificationPayload>(event.payload).competitor to ""
                else -> null to ""
            }
            val status = when {
                event.eventId in resetTargets -> BoutEntryStatus.RESET
                event.eventId in corrected -> BoutEntryStatus.CORRECTED
                event.type == KerugiBoutEvents.SCORE_CANDIDATE -> when {
                    event.eventId in outsideRunningPeriod -> BoutEntryStatus.OUTSIDE_RUNNING_PERIOD
                    windows[event.eventId] == KerugiWindowDecision.SCORE_AWARDED -> BoutEntryStatus.COUNTED
                    else -> BoutEntryStatus.NO_QUORUM
                }
                event.type == KerugiBoutEvents.OPERATOR_ACTION -> BoutEntryStatus.APPLIED
                else -> BoutEntryStatus.RECORDED
            }
            BoutHistoryEntry(event.eventId, event.type, event.occurredAt, event.author, seat, competitor, detail, status)
        }
    }

    companion object {
        fun start(event: SequencedDomainEvent): KerugiBout = KerugiBout(listOf(event))
    }
}
