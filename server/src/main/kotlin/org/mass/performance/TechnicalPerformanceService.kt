package org.mass.performance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mass.bout.BoutCancelledPayload
import org.mass.bout.BoutChange
import org.mass.bout.BoutCorner
import org.mass.bout.BoutCourt
import org.mass.bout.BoutEventJournal
import org.mass.bout.BoutOrigin
import org.mass.bout.BoutOutcome
import org.mass.bout.BoutRejection
import org.mass.bout.BoutSeat
import org.mass.bout.JudgeContext
import org.mass.bout.KerugiBoutEvents
import org.mass.bout.KerugiBoutService
import org.mass.bout.RosterJudge
import org.mass.domain.AggregationFormula
import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.DeviceId
import org.mass.domain.DomainEvent
import org.mass.domain.EventId
import org.mass.domain.EventSource
import org.mass.domain.JudgeId
import org.mass.domain.JudgeVector
import org.mass.domain.PeerId
import org.mass.domain.SequencedDomainEvent
import org.mass.domain.SessionId
import org.mass.domain.SessionState
import org.mass.domain.TechnicalDiscipline
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A performance of a confirmed performance order the operator starts, with the roster and formula fixed for it. */
data class PerformanceStartSpec(
    val competitionId: CompetitionId,
    val bracketId: BracketId,
    val categoryId: String,
    val categoryTitle: String,
    val performanceNumber: Int,
    val round: Int,
    val index: Int,
    val entry: BoutCorner,
    val discipline: TechnicalDiscipline,
    val techniqueCriteria: Int,
    val judges: List<RosterJudge>,
    val courtNumber: Int = 1,
    val formula: AggregationFormula = AggregationFormula.TRIMMED_MEAN,
)

sealed interface PerformanceCommand {
    val eventId: EventId

    /** The only judge command: the final vector of the seat (ADR-011 §7). */
    data class SendScore(
        override val eventId: EventId,
        val sequence: Long,
        val clientTimestamp: Instant,
        val context: JudgeContext,
        /** The discipline the client scored; another one than the session's is `discipline_mismatch`. */
        val discipline: String,
        val vector: JudgeVector,
        /** The client's local total, checked against the server's. */
        val declaredTotalTenths: Int,
    ) : PerformanceCommand

    /** An operator command; only the desktop sends these. */
    sealed interface FromOperator : PerformanceCommand

    data class Start(override val eventId: EventId, val spec: PerformanceStartSpec) : FromOperator

    /** «Старт»: judges may send from now on. */
    data class Begin(override val eventId: EventId, val sessionId: SessionId) : FromOperator

    data class Adjust(override val eventId: EventId, val sessionId: SessionId, val kind: TechnicalAdjustmentKind, val reason: String) :
        FromOperator

    data class WithdrawAdjustment(
        override val eventId: EventId,
        val sessionId: SessionId,
        val targetEventId: EventId,
        val reason: String,
    ) : FromOperator

    /** «Завершить»; [reason] is required only while a seat has not sent (`TEC-014`). */
    data class Complete(override val eventId: EventId, val sessionId: SessionId, val reason: String? = null) : FromOperator

    data class Cancel(override val eventId: EventId, val sessionId: SessionId, val reason: String) : FromOperator
}

/** Rejection codes of ADR-011 beyond the shared ones of ADR-009 §8. */
object PerformanceRejection {
    const val INVALID_SCORE_VECTOR = "invalid_score_vector"
    const val TOTAL_MISMATCH = "total_mismatch"
    const val SCORE_ALREADY_SENT = "score_already_sent"
    const val SCORES_MISSING = "scores_missing"
    const val NOT_ENOUGH_SCORES = "not_enough_scores"
    const val BONUS_NOT_ALLOWED = "bonus_not_allowed"
    const val INVALID_ADJUSTMENT_TARGET = "invalid_adjustment_target"
}

/**
 * Technical performances of one court over the single journal (ADR-011 §5-§11): validates every command against the
 * performance aggregate, appends one event before it takes effect and rebuilds every performance from the journal on start.
 * Judges reach it through the realtime dispatcher with their origin; a judge may only send its final vector.
 */
class TechnicalPerformanceService(
    private val journal: BoutEventJournal,
    val peerId: PeerId,
    val court: BoutCourt = BoutCourt.single(peerId),
    private val now: () -> Instant = Instant::now,
    private val clientTimestampTolerance: Duration = Duration.ofSeconds(5),
) {
    private val performances = linkedMapOf<SessionId, TechnicalPerformance>()
    private val eventsById = mutableMapOf<EventId, SequencedDomainEvent>()
    private var currentSessionId: SessionId? = null

    /** The journal sequence of the latest performance event. */
    var cursor: Long = 0
        private set

    private val mutableRevision = MutableStateFlow(0L)

    /** Changes with every new performance event, so the desktop re-reads the projection. */
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    init {
        journal.events().sortedBy(SequencedDomainEvent::sequence).forEach(::project)
    }

    @Synchronized
    fun current(): TechnicalPerformance? = currentSessionId?.let(performances::get)

    @Synchronized
    fun performance(sessionId: SessionId): TechnicalPerformance? = performances[sessionId]

    @Synchronized
    fun performances(): List<TechnicalPerformance> = performances.values.toList()

    @Synchronized
    fun knows(eventId: EventId): Boolean = eventId in eventsById

    /** A pre-check of «Начать» so the caller can reject before it locks the draw; null when the start would apply. */
    @Synchronized
    fun startRejection(spec: PerformanceStartSpec): String? = validateStart(spec)

    @Synchronized
    fun dispatch(origin: BoutOrigin, command: PerformanceCommand): BoutOutcome {
        val permitted = when (origin) {
            is BoutOrigin.Judge -> command is PerformanceCommand.SendScore
            is BoutOrigin.Operator -> command is PerformanceCommand.FromOperator
        }
        if (!permitted) return BoutOutcome.Rejected(BoutRejection.NOT_PERMITTED)
        eventsById[command.eventId]?.let { existing -> return retry(origin, command, existing) }
        val rejection = when (command) {
            is PerformanceCommand.SendScore -> validateSend(origin as BoutOrigin.Judge, command)
            is PerformanceCommand.Start -> validateStart(command.spec)
            is PerformanceCommand.FromOperator -> validateOperator(command)
        }
        if (rejection != null) return BoutOutcome.Rejected(rejection)
        val event = requireNotNull(eventFor(origin, command, now())) { "A validated command must build an event" }
        val before = performances[event.sessionId]
        val sequenced = try {
            journal.append(event)
        } catch (_: Exception) {
            return BoutOutcome.Rejected(BoutRejection.JOURNAL_UNAVAILABLE)
        }
        project(sequenced)
        mutableRevision.value++
        val after = performances.getValue(event.sessionId)
        val changes = buildSet {
            if (before == null) add(BoutChange.NEW_SESSION)
            if (before != null && before.state != after.state) add(BoutChange.STATE)
            if (before != null && scoreOf(before) != scoreOf(after)) add(BoutChange.SCORE)
        }
        return BoutOutcome.Applied(event.eventId, event.sessionId, sequenced.sequence, isNew = true, changes = changes)
    }

    /** What `technical_score_updated` carries (ADR-011 §10): the seats that sent and the fixed result. */
    private fun scoreOf(performance: TechnicalPerformance) = performance.submittedSeats() to performance.result()

    private fun retry(origin: BoutOrigin, command: PerformanceCommand, existing: SequencedDomainEvent): BoutOutcome {
        val rebuilt = eventFor(origin, command, existing.event.occurredAt)
        return if (rebuilt == existing.event) {
            BoutOutcome.Applied(command.eventId, existing.event.sessionId, existing.sequence, isNew = false, changes = emptySet())
        } else {
            BoutOutcome.Rejected(BoutRejection.EVENT_ID_CONFLICT)
        }
    }

    private fun validateSend(origin: BoutOrigin.Judge, command: PerformanceCommand.SendScore): String? {
        val context = command.context
        if (context.deviceId != origin.deviceId) return BoutRejection.JUDGE_NOT_ASSIGNED
        val performance = performances[context.sessionId] ?: return BoutRejection.SESSION_NOT_CURRENT
        if (command.discipline != performance.discipline.code) return BoutRejection.DISCIPLINE_MISMATCH
        if (performance.closed) return BoutRejection.SESSION_CLOSED
        if (performance.sessionId != currentSessionId || context.competitionId != performance.competitionId ||
            context.peerId != performance.peerId || context.courtId != performance.courtId || context.bracketId != performance.bracketId
        ) {
            return BoutRejection.SESSION_NOT_CURRENT
        }
        if (performance.state == SessionState.PREPARED) return BoutRejection.SESSION_NOT_STARTED
        val seat = performance.seatOf(origin.deviceId)
        if (seat?.judgeId != context.judgeId.value) return BoutRejection.JUDGE_NOT_ASSIGNED
        if (command.clientTimestamp.isAfter(now().plus(clientTimestampTolerance))) return BoutRejection.INVALID_CLIENT_TIMESTAMP
        if (!command.vector.fits(performance.started.techniqueCriteria)) return PerformanceRejection.INVALID_SCORE_VECTOR
        if (command.vector.totalTenths != command.declaredTotalTenths) return PerformanceRejection.TOTAL_MISMATCH
        if (seat.number in performance.sends) return PerformanceRejection.SCORE_ALREADY_SENT
        return null
    }

    private fun validateStart(spec: PerformanceStartSpec): String? {
        val current = current()
        if (current != null && !current.closed) return BoutRejection.BOUT_IN_PROGRESS
        val devices = spec.judges.map(RosterJudge::deviceId)
        if (devices.size !in 1..MAX_JUDGES || devices.toSet().size != devices.size || spec.judges.any { it.surname.isBlank() }) {
            return BoutRejection.INVALID_ROSTER
        }
        val criteria = setOf(spec.discipline.techniqueCriteria(younger = true), spec.discipline.techniqueCriteria(younger = false))
        if (devices.size < spec.formula.minimumScores || spec.techniqueCriteria !in criteria) return BoutRejection.INVALID_SETTINGS
        if (completed(spec.categoryId, spec.index)) return BoutRejection.BOUT_ALREADY_JUDGED
        return null
    }

    /** A completed performance of the order cannot be started again. */
    @Synchronized
    fun completed(categoryId: String, index: Int): Boolean = performances.values.any {
        it.started.categoryId == categoryId && it.started.index == index && it.state == SessionState.COMPLETED
    }

    private fun validateOperator(command: PerformanceCommand.FromOperator): String? {
        val sessionId = sessionOf(command)
        val performance = current()?.takeIf { it.sessionId == sessionId } ?: return BoutRejection.SESSION_NOT_CURRENT
        if (performance.closed) return BoutRejection.SESSION_CLOSED
        val running = performance.state == SessionState.RUNNING
        return when (command) {
            is PerformanceCommand.Begin -> BoutRejection.INVALID_TRANSITION.takeIf { performance.state != SessionState.PREPARED }
            is PerformanceCommand.Cancel -> BoutRejection.REASON_REQUIRED.takeIf { command.reason.isBlank() }
            is PerformanceCommand.Adjust -> when {
                !running -> BoutRejection.INVALID_TRANSITION
                command.reason.isBlank() -> BoutRejection.REASON_REQUIRED
                command.kind == TechnicalAdjustmentKind.BONUS && !performance.discipline.bonusAllowed -> PerformanceRejection.BONUS_NOT_ALLOWED
                command.kind == TechnicalAdjustmentKind.BONUS &&
                    performance.effectiveAdjustments().any { it.kind == TechnicalAdjustmentKind.BONUS } -> PerformanceRejection.BONUS_NOT_ALLOWED
                else -> null
            }
            is PerformanceCommand.WithdrawAdjustment -> when {
                !running -> BoutRejection.INVALID_TRANSITION
                command.reason.isBlank() -> BoutRejection.REASON_REQUIRED
                performance.effectiveAdjustments().none { it.eventId == command.targetEventId } -> PerformanceRejection.INVALID_ADJUSTMENT_TARGET
                else -> null
            }
            is PerformanceCommand.Complete -> when {
                !running -> BoutRejection.INVALID_TRANSITION
                performance.missingSeats().isNotEmpty() && command.reason.isNullOrBlank() -> PerformanceRejection.SCORES_MISSING
                performance.sends.size < performance.formula.minimumScores -> PerformanceRejection.NOT_ENOUGH_SCORES
                else -> null
            }
            is PerformanceCommand.Start -> null
        }
    }

    private fun sessionOf(command: PerformanceCommand): SessionId = when (command) {
        is PerformanceCommand.SendScore -> command.context.sessionId
        is PerformanceCommand.Begin -> command.sessionId
        is PerformanceCommand.Adjust -> command.sessionId
        is PerformanceCommand.WithdrawAdjustment -> command.sessionId
        is PerformanceCommand.Complete -> command.sessionId
        is PerformanceCommand.Cancel -> command.sessionId
        is PerformanceCommand.Start -> SessionId(nameUuid("session:${command.eventId.value}"))
    }

    /** The event a command produces; deterministic, so an identical retry rebuilds the stored event. */
    private fun eventFor(origin: BoutOrigin, command: PerformanceCommand, occurredAt: Instant): DomainEvent? {
        if (command is PerformanceCommand.Start) return startEvent(origin as BoutOrigin.Operator, command, occurredAt)
        val sessionId = sessionOf(command)
        val performance = performances[sessionId] ?: return null
        val (type, payload) = when (command) {
            is PerformanceCommand.SendScore -> TechnicalPerformanceEvents.SCORE_SENT to Json.encodeToString(
                TechnicalScoreSentPayload(
                    command.vector.technique, command.vector.presentation, command.vector.deductionTenths,
                    command.vector.totalTenths, command.clientTimestamp.toString(), command.sequence,
                ),
            )
            is PerformanceCommand.Begin -> TechnicalPerformanceEvents.SESSION_STARTED to "{}"
            is PerformanceCommand.Adjust -> TechnicalPerformanceEvents.ADJUSTMENT_APPLIED to Json.encodeToString(
                TechnicalAdjustmentPayload(command.kind, command.kind.amountTenths, command.reason.trim()),
            )
            is PerformanceCommand.WithdrawAdjustment -> TechnicalPerformanceEvents.ADJUSTMENT_WITHDRAWN to Json.encodeToString(
                TechnicalAdjustmentWithdrawnPayload(command.targetEventId.value, command.reason.trim()),
            )
            // The result is the projection's when the completion was decided; a retry compares with the stored one.
            is PerformanceCommand.Complete -> TechnicalPerformanceEvents.SESSION_COMPLETED to (
                eventsById[command.eventId]?.event?.payload ?: Json.encodeToString(completedPayload(performance, command.reason))
                )
            is PerformanceCommand.Cancel ->
                TechnicalPerformanceEvents.SESSION_CANCELLED to Json.encodeToString(BoutCancelledPayload(command.reason.trim()))
            is PerformanceCommand.Start -> error("Handled above")
        }
        return when (origin) {
            is BoutOrigin.Judge -> {
                val context = (command as PerformanceCommand.SendScore).context
                val seat = performance.seatOf(origin.deviceId) ?: return null
                DomainEvent(
                    eventId = command.eventId,
                    competitionId = context.competitionId,
                    peerId = context.peerId,
                    courtId = context.courtId,
                    bracketId = context.bracketId,
                    sessionId = sessionId,
                    judgeId = context.judgeId,
                    deviceId = KerugiBoutService.deviceUuid(origin.deviceId),
                    source = EventSource(KerugiBoutEvents.JUDGE_SOURCE),
                    author = seat.surname,
                    occurredAt = occurredAt,
                    type = type,
                    payload = payload,
                )
            }
            is BoutOrigin.Operator -> operatorEvent(
                origin, command.eventId, performance.competitionId, performance.bracketId, sessionId, occurredAt, type, payload,
            )
        }
    }

    private fun completedPayload(performance: TechnicalPerformance, reason: String?): TechnicalCompletedPayload {
        val result = requireNotNull(performance.currentResult())
        val missing = performance.missingSeats()
        return TechnicalCompletedPayload(
            missingSeats = missing,
            reason = reason?.trim()?.takeIf { missing.isNotEmpty() && it.isNotEmpty() },
            totalTenths = result.totalTenths,
            aggregateTenths = result.aggregateTenths,
            adjustmentTenths = result.adjustmentTenths,
            divisor = result.divisor,
            formula = result.formula.code,
        )
    }

    private fun startEvent(origin: BoutOrigin.Operator, command: PerformanceCommand.Start, occurredAt: Instant): DomainEvent {
        val spec = command.spec
        val sessionId = sessionOf(command)
        val payload = TechnicalPerformanceStartedPayload(
            categoryId = spec.categoryId,
            categoryTitle = spec.categoryTitle,
            courtNumber = spec.courtNumber,
            performanceNumber = spec.performanceNumber,
            round = spec.round,
            index = spec.index,
            discipline = spec.discipline.code,
            techniqueCriteria = spec.techniqueCriteria,
            presentationCriteria = TechnicalDiscipline.PRESENTATION_CRITERIA,
            entry = spec.entry,
            seats = spec.judges.mapIndexed { index, judge ->
                BoutSeat(index + 1, nameUuid("judge:${sessionId.value}:${index + 1}"), judge.deviceId, judge.surname.trim())
            },
            formula = spec.formula.code,
        )
        return operatorEvent(
            origin, command.eventId, spec.competitionId, spec.bracketId, sessionId, occurredAt,
            TechnicalPerformanceEvents.PERFORMANCE_STARTED, Json.encodeToString(payload),
        )
    }

    private fun operatorEvent(
        origin: BoutOrigin.Operator,
        eventId: EventId,
        competitionId: CompetitionId,
        bracketId: BracketId,
        sessionId: SessionId,
        occurredAt: Instant,
        type: String,
        payload: String,
    ) = DomainEvent(
        eventId = eventId,
        competitionId = competitionId,
        peerId = peerId,
        courtId = court.courtId,
        bracketId = bracketId,
        sessionId = sessionId,
        judgeId = JudgeId(nameUuid("operator-judge:${peerId.value}")),
        deviceId = DeviceId(nameUuid("operator-device:${peerId.value}")),
        source = EventSource(KerugiBoutEvents.OPERATOR_SOURCE),
        author = origin.author.trim().ifEmpty { "operator" },
        occurredAt = occurredAt,
        type = type,
        payload = payload,
    )

    private fun project(event: SequencedDomainEvent) {
        val sessionId = event.event.sessionId
        val performance = if (event.event.type == TechnicalPerformanceEvents.PERFORMANCE_STARTED) {
            TechnicalPerformance.start(event).also { if (it.courtId == court.courtId) currentSessionId = sessionId }
        } else {
            // Bout events share the journal and the lifecycle types; they belong to no performance here.
            performances[sessionId]?.with(event) ?: return
        }
        performances[sessionId] = performance
        eventsById[event.event.eventId] = event
        cursor = maxOf(cursor, event.sequence)
    }

    companion object {
        /** FHR names five side judges; the pilot may seat up to seven. */
        const val MAX_JUDGES = 7
    }
}

private fun nameUuid(name: String): String = UUID.nameUUIDFromBytes(name.toByteArray()).toString()
