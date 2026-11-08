package org.mass.bout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.CourtId
import org.mass.domain.DeviceId
import org.mass.domain.DomainEvent
import org.mass.domain.EventId
import org.mass.domain.EventSource
import org.mass.domain.JudgeId
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.PeerId
import org.mass.domain.SequencedDomainEvent
import org.mass.domain.SessionId
import org.mass.domain.SessionState
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The court identity of this desktop in the journal; the court number of each bout comes from the court panel. */
data class BoutCourt(val courtId: CourtId, val number: Int) {
    companion object {
        /** The single court of a pilot desktop, stable for the peer across restarts. */
        fun single(peerId: PeerId) = BoutCourt(CourtId(nameUuid("court:${peerId.value}:1")), 1)
    }
}

/**
 * Kerugi bouts of one court over the single journal (ADR-009 §11): validates every command against the bout aggregate,
 * appends one event before it takes effect and rebuilds all bouts from the journal on start. Judge devices and the operator
 * reach it through one dispatcher with their origin; a judge may only score and signal attention.
 */
class KerugiBoutService(
    private val journal: BoutEventJournal,
    private val peerId: PeerId,
    val court: BoutCourt = BoutCourt.single(peerId),
    private val now: () -> Instant = Instant::now,
    private val clientTimestampTolerance: Duration = Duration.ofSeconds(5),
    private val checkpointInterval: Duration = Duration.ofSeconds(5),
) {
    private val bouts = linkedMapOf<SessionId, KerugiBout>()
    private val eventsById = mutableMapOf<EventId, SequencedDomainEvent>()
    private var currentSessionId: SessionId? = null

    /** The journal sequence of the latest bout event: the `cursor` of snapshots and pushes. */
    var cursor: Long = 0
        private set

    private val mutableRevision = MutableStateFlow(0L)

    /** Changes with every new bout event, so the desktop re-reads the projection. */
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    init {
        journal.events().sortedBy(SequencedDomainEvent::sequence).forEach(::project)
        restoreClock()
    }

    /**
     * After a restart the clock is never derived from the wall time (ADR-010 §3): a running clock of the current bout stops
     * at the latest saved event of the bout, and the operator resumes or corrects it.
     */
    private fun restoreClock() {
        val bout = current()?.takeIf { !it.closed && it.clock.running } ?: return
        val payload = Json.encodeToString(BoutClockStoppedPayload(RESTORE_REASON, bout.lastEventAt.toString()))
        append(serverEvent(bout, KerugiBoutEvents.CLOCK_STOPPED, payload, now(), author = RESTORE_REASON))
    }

    /**
     * The server timer, called a few times per second: journals the end of a period at its exact deadline, and a checkpoint
     * of the running clock when [checkpointInterval] passed since the bout's latest event (ADR-010 §2-§3).
     */
    @Synchronized
    fun tick(): BoutOutcome.Applied? {
        val bout = current()?.takeIf { !it.closed } ?: return null
        val deadline = bout.clock.deadline ?: return null
        val now = now()
        val event = when {
            !deadline.isAfter(now) -> serverEvent(
                bout,
                KerugiBoutEvents.PERIOD_ENDED,
                Json.encodeToString(BoutPeriodEndedPayload(bout.clock.period.name.lowercase(), bout.clock.round)),
                deadline,
                author = TIMER_AUTHOR,
            )
            Duration.between(bout.lastEventAt, now) >= checkpointInterval ->
                serverEvent(bout, KerugiBoutEvents.CLOCK_SAVED, "{}", now, author = TIMER_AUTHOR)
            else -> return null
        }
        return try {
            append(event)
        } catch (_: Exception) {
            null
        }
    }

    private fun append(event: DomainEvent): BoutOutcome.Applied {
        val before = bouts[event.sessionId]
        val sequenced = journal.append(event)
        project(sequenced)
        mutableRevision.value++
        val after = bouts.getValue(event.sessionId)
        val changes = buildSet {
            if (before == null) add(BoutChange.NEW_SESSION)
            if (before != null && before.state != after.state) add(BoutChange.STATE)
            if (before != null && before.clock.timer() != after.clock.timer()) add(BoutChange.TIMER)
            if (before != null && before.score() != after.score()) add(BoutChange.SCORE)
        }
        return BoutOutcome.Applied(event.eventId, event.sessionId, sequenced.sequence, isNew = true, changes = changes)
    }

    @Synchronized
    fun current(): KerugiBout? = currentSessionId?.let(bouts::get)

    @Synchronized
    fun bout(sessionId: SessionId): KerugiBout? = bouts[sessionId]

    @Synchronized
    fun bouts(): List<KerugiBout> = bouts.values.toList()

    /** Bouts of the bracket already decided: a completed bout cannot be started again. */
    @Synchronized
    fun completed(bracketId: BracketId, round: Int, index: Int): Boolean = bouts.values.any {
        it.bracketId == bracketId && it.started.round == round && it.started.index == index && it.state == SessionState.COMPLETED
    }

    /** A pre-check of «Начать» so the caller can reject before it locks the draw; null when the start would apply. */
    @Synchronized
    fun startRejection(spec: BoutStartSpec): String? = validateStart(spec)

    @Synchronized
    fun dispatch(origin: BoutOrigin, command: BoutCommand): BoutOutcome {
        val permitted = when (origin) {
            is BoutOrigin.Judge -> command is BoutCommand.FromJudge
            is BoutOrigin.Operator -> command is BoutCommand.FromOperator
        }
        if (!permitted) return BoutOutcome.Rejected(BoutRejection.NOT_PERMITTED)
        eventsById[command.eventId]?.let { existing -> return retry(origin, command, existing) }
        val rejection = when (command) {
            is BoutCommand.FromJudge -> validateJudge(origin as BoutOrigin.Judge, command)
            is BoutCommand.StartBout -> validateStart(command.spec)
            is BoutCommand.FromOperator -> validateOperator(command)
        }
        if (rejection != null) return BoutOutcome.Rejected(rejection)
        val event = requireNotNull(eventFor(origin, command, now())) { "A validated command must build an event" }
        return try {
            append(event)
        } catch (_: Exception) {
            BoutOutcome.Rejected(BoutRejection.JOURNAL_UNAVAILABLE)
        }
    }

    /** An identical retry gets the original outcome without a second event; anything else with the same ID conflicts. */
    private fun retry(origin: BoutOrigin, command: BoutCommand, existing: SequencedDomainEvent): BoutOutcome {
        val rebuilt = eventFor(origin, command, existing.event.occurredAt)
        return if (rebuilt == existing.event) {
            BoutOutcome.Applied(command.eventId, existing.event.sessionId, existing.sequence, isNew = false, changes = emptySet())
        } else {
            BoutOutcome.Rejected(BoutRejection.EVENT_ID_CONFLICT)
        }
    }

    private fun validateJudge(origin: BoutOrigin.Judge, command: BoutCommand.FromJudge): String? {
        val context = command.context
        if (context.deviceId != origin.deviceId) return BoutRejection.JUDGE_NOT_ASSIGNED
        val bout = bouts[context.sessionId] ?: return BoutRejection.SESSION_NOT_CURRENT
        if (bout.closed) return BoutRejection.SESSION_CLOSED
        if (bout.sessionId != currentSessionId || context.competitionId != bout.competitionId || context.peerId != bout.peerId ||
            context.courtId != bout.courtId || context.bracketId != bout.bracketId
        ) {
            return BoutRejection.SESSION_NOT_CURRENT
        }
        if (bout.state == SessionState.PREPARED && command is BoutCommand.SubmitScore) return BoutRejection.SESSION_NOT_STARTED
        if (bout.seatOf(origin.deviceId)?.judgeId != context.judgeId.value) return BoutRejection.JUDGE_NOT_ASSIGNED
        if (command.clientTimestamp.isAfter(now().plus(clientTimestampTolerance))) return BoutRejection.INVALID_CLIENT_TIMESTAMP
        return null
    }

    private fun validateStart(spec: BoutStartSpec): String? {
        val current = current()
        if (current != null && !current.closed) return BoutRejection.BOUT_IN_PROGRESS
        val devices = spec.judges.map(RosterJudge::deviceId)
        if (devices.size !in 2..3 || devices.toSet().size != devices.size || spec.judges.any { it.surname.isBlank() }) {
            return BoutRejection.INVALID_ROSTER
        }
        if (spec.quorum !in 1..devices.size || spec.coincidenceWindowMillis !in 1..10_000) return BoutRejection.INVALID_SETTINGS
        if (completed(spec.bracketId, spec.round, spec.index)) return BoutRejection.BOUT_ALREADY_JUDGED
        return null
    }

    private fun validateOperator(command: BoutCommand.FromOperator): String? {
        val sessionId = sessionOf(command)
        val bout = current()?.takeIf { it.sessionId == sessionId } ?: return BoutRejection.SESSION_NOT_CURRENT
        if (bout.closed) return BoutRejection.SESSION_CLOSED
        val scoring = bout.state == SessionState.RUNNING || bout.state == SessionState.PAUSED
        return when (command) {
            is BoutCommand.ChangeState -> BoutRejection.INVALID_TRANSITION.takeIf {
                when (command.action) {
                    BoutStateAction.START -> bout.state != SessionState.PREPARED
                    BoutStateAction.PAUSE -> bout.state != SessionState.RUNNING || !bout.clock.canPause
                    BoutStateAction.RESUME -> bout.state != SessionState.PAUSED || !bout.clock.canResume
                }
            }
            is BoutCommand.EndPeriod -> BoutRejection.INVALID_TRANSITION.takeIf { !scoring || !bout.clock.canEndPeriod }
            is BoutCommand.AdjustClock -> when {
                !scoring || !bout.clock.canAdjust -> BoutRejection.INVALID_TRANSITION
                command.remainingMillis !in 1..bout.clock.durationMillis -> BoutRejection.INVALID_CLOCK
                else -> null
            }
            is BoutCommand.CancelBout -> BoutRejection.REASON_REQUIRED.takeIf { command.reason.isBlank() }
            is BoutCommand.AwardAction -> when {
                !scoring -> BoutRejection.INVALID_TRANSITION
                command.points !in allowedPoints.getValue(command.type) -> BoutRejection.INVALID_POINTS
                else -> null
            }
            is BoutCommand.CorrectScore -> when {
                !scoring -> BoutRejection.INVALID_TRANSITION
                bout.score().disqualified != null -> BoutRejection.DISQUALIFICATION_CONFIRMED
                command.targetEventId !in bout.effectiveScoreEventIds() -> BoutRejection.INVALID_CORRECTION_TARGET
                else -> null
            }
            is BoutCommand.ResetBout -> when {
                !scoring -> BoutRejection.INVALID_TRANSITION
                command.reason.isBlank() -> BoutRejection.REASON_REQUIRED
                bout.score().disqualified != null -> BoutRejection.DISQUALIFICATION_CONFIRMED
                bout.effectiveScoreEventIds().isEmpty() -> BoutRejection.NOTHING_TO_RESET
                else -> null
            }
            is BoutCommand.ConfirmDisqualification -> when {
                !scoring -> BoutRejection.INVALID_TRANSITION
                bout.score().warnings.none { it.competitor == command.competitor } -> BoutRejection.DISQUALIFICATION_NOT_PENDING
                else -> null
            }
            is BoutCommand.CompleteBout -> if (!scoring) BoutRejection.INVALID_TRANSITION else completionRejection(bout, command.winner)
            is BoutCommand.StartBout -> null
        }
    }

    /**
     * The result rules of ADR-010 §4: a confirmed disqualification at any time; the final score once round 2 ended and the
     * golden round has not started; the golden round's first point; the operator's preference after a scoreless golden round.
     */
    private fun completionRejection(bout: KerugiBout, winner: KerugiCompetitor?): String? {
        val score = bout.score()
        val clock = bout.clock
        val tied = score.blue == score.red
        return when {
            score.disqualified != null -> BoutRejection.WINNER_NOT_ALLOWED.takeIf { winner != null }
            !clock.regularTimeOver -> BoutRejection.BOUT_NOT_FINISHED
            clock.phase == KerugiClock.GOLDEN_ROUND && tied && clock.over -> BoutRejection.WINNER_REQUIRED.takeIf { winner == null }
            tied -> BoutRejection.SCORE_TIED
            else -> BoutRejection.WINNER_NOT_ALLOWED.takeIf { winner != null }
        }
    }

    private fun sessionOf(command: BoutCommand): SessionId = when (command) {
        is BoutCommand.FromJudge -> command.context.sessionId
        is BoutCommand.ChangeState -> command.sessionId
        is BoutCommand.EndPeriod -> command.sessionId
        is BoutCommand.AdjustClock -> command.sessionId
        is BoutCommand.AwardAction -> command.sessionId
        is BoutCommand.CorrectScore -> command.sessionId
        is BoutCommand.ResetBout -> command.sessionId
        is BoutCommand.ConfirmDisqualification -> command.sessionId
        is BoutCommand.CompleteBout -> command.sessionId
        is BoutCommand.CancelBout -> command.sessionId
        is BoutCommand.StartBout -> error("A start creates its session")
    }

    /** The event a command produces; deterministic, so an identical retry rebuilds the stored event. */
    private fun eventFor(origin: BoutOrigin, command: BoutCommand, occurredAt: Instant): DomainEvent? {
        if (command is BoutCommand.StartBout) return startEvent(origin as BoutOrigin.Operator, command, occurredAt)
        val sessionId = sessionOf(command)
        val bout = bouts[sessionId] ?: return null
        val (type, payload) = when (command) {
            is BoutCommand.SubmitScore -> KerugiBoutEvents.SCORE_CANDIDATE to Json.encodeToString(
                BoutScoreCandidatePayload(command.competitor, command.area, command.clientTimestamp.toString(), command.sequence),
            )
            is BoutCommand.SignalAttention -> KerugiBoutEvents.JUDGE_ATTENTION to Json.encodeToString(
                BoutAttentionPayload(command.clientTimestamp.toString(), command.sequence),
            )
            is BoutCommand.ChangeState -> when (command.action) {
                BoutStateAction.START -> KerugiBoutEvents.SESSION_STARTED
                BoutStateAction.PAUSE -> KerugiBoutEvents.SESSION_PAUSED
                BoutStateAction.RESUME -> KerugiBoutEvents.SESSION_RESUMED
            } to "{}"
            // The period is the one that ended; a retry compares with the stored payload.
            is BoutCommand.EndPeriod -> KerugiBoutEvents.PERIOD_ENDED to (
                eventsById[command.eventId]?.event?.payload
                    ?: Json.encodeToString(BoutPeriodEndedPayload(bout.clock.period.name.lowercase(), bout.clock.round))
                )
            is BoutCommand.AdjustClock ->
                KerugiBoutEvents.CLOCK_ADJUSTED to Json.encodeToString(BoutClockAdjustedPayload(command.remainingMillis))
            is BoutCommand.AwardAction -> KerugiBoutEvents.OPERATOR_ACTION to Json.encodeToString(
                BoutOperatorActionPayload(command.type, command.competitor, command.points),
            )
            is BoutCommand.CorrectScore ->
                KerugiBoutEvents.SCORE_CORRECTION to Json.encodeToString(BoutCorrectionPayload(command.targetEventId.value))
            // The targets are the effective events when the reset was decided; a retry compares with the stored list.
            is BoutCommand.ResetBout -> KerugiBoutEvents.BOUT_RESET to Json.encodeToString(
                BoutResetPayload(
                    command.reason.trim(),
                    eventsById[command.eventId]?.let { Json.decodeFromString<BoutResetPayload>(it.event.payload).targetEventIds }
                        ?: bout.effectiveScoreEventIds().map(EventId::value),
                ),
            )
            is BoutCommand.ConfirmDisqualification ->
                KerugiBoutEvents.DISQUALIFICATION_CONFIRMED to Json.encodeToString(BoutDisqualificationPayload(command.competitor))
            is BoutCommand.CompleteBout -> KerugiBoutEvents.SESSION_COMPLETED to Json.encodeToString(
                eventsById[command.eventId]?.let { Json.decodeFromString<BoutCompletedPayload>(it.event.payload) }
                    ?: resultOf(bout, command.winner),
            )
            is BoutCommand.CancelBout ->
                KerugiBoutEvents.SESSION_CANCELLED to Json.encodeToString(BoutCancelledPayload(command.reason.trim()))
            is BoutCommand.StartBout -> error("Handled above")
        }
        return when (origin) {
            is BoutOrigin.Judge -> {
                val context = (command as BoutCommand.FromJudge).context
                val seat = bout.seatOf(origin.deviceId) ?: return null
                DomainEvent(
                    eventId = command.eventId,
                    competitionId = context.competitionId,
                    peerId = context.peerId,
                    courtId = context.courtId,
                    bracketId = context.bracketId,
                    sessionId = sessionId,
                    judgeId = context.judgeId,
                    deviceId = deviceUuid(origin.deviceId),
                    source = EventSource(KerugiBoutEvents.JUDGE_SOURCE),
                    author = seat.surname,
                    occurredAt = occurredAt,
                    type = type,
                    payload = payload,
                )
            }
            is BoutOrigin.Operator -> operatorEvent(
                origin, command.eventId, bout.competitionId, bout.bracketId, sessionId, occurredAt, type, payload,
            )
        }
    }

    private fun startEvent(origin: BoutOrigin.Operator, command: BoutCommand.StartBout, occurredAt: Instant): DomainEvent {
        val spec = command.spec
        val sessionId = SessionId(nameUuid("session:${command.eventId.value}"))
        val payload = KerugiBoutStartedPayload(
            categoryId = spec.categoryId,
            categoryTitle = spec.categoryTitle,
            courtNumber = spec.courtNumber,
            boutNumber = spec.boutNumber,
            round = spec.round,
            index = spec.index,
            blue = spec.blue,
            red = spec.red,
            seats = spec.judges.mapIndexed { index, judge ->
                BoutSeat(index + 1, nameUuid("judge:${sessionId.value}:${index + 1}"), judge.deviceId, judge.surname.trim())
            },
            quorum = spec.quorum,
            coincidenceWindowMillis = spec.coincidenceWindowMillis,
            timing = spec.timing,
        )
        return operatorEvent(
            origin, command.eventId, spec.competitionId, spec.bracketId, sessionId, occurredAt,
            KerugiBoutEvents.BOUT_STARTED, Json.encodeToString(payload),
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
    ) = courtEvent(eventId, competitionId, bracketId, sessionId, occurredAt, type, payload, KerugiBoutEvents.OPERATOR_SOURCE, origin.author)

    /** An event the server journals by itself, with a new event ID (ADR-010 §2-§3). */
    private fun serverEvent(bout: KerugiBout, type: String, payload: String, occurredAt: Instant, author: String) = courtEvent(
        EventId.new(), bout.competitionId, bout.bracketId, bout.sessionId, occurredAt, type, payload, KerugiBoutEvents.SERVER_SOURCE, author,
    )

    private fun courtEvent(
        eventId: EventId,
        competitionId: CompetitionId,
        bracketId: BracketId,
        sessionId: SessionId,
        occurredAt: Instant,
        type: String,
        payload: String,
        source: String,
        author: String,
    ) = DomainEvent(
        eventId = eventId,
        competitionId = competitionId,
        peerId = peerId,
        courtId = court.courtId,
        bracketId = bracketId,
        sessionId = sessionId,
        judgeId = JudgeId(nameUuid("operator-judge:${peerId.value}")),
        deviceId = DeviceId(nameUuid("operator-device:${peerId.value}")),
        source = EventSource(source),
        author = author.trim().ifEmpty { "operator" },
        occurredAt = occurredAt,
        type = type,
        payload = payload,
    )

    /** The result by ADR-010 §4 (`KER-013`); a confirmed disqualification gives the bout to the opponent. */
    private fun resultOf(bout: KerugiBout, winner: KerugiCompetitor?): BoutCompletedPayload {
        val score = bout.score()
        val reason = if (bout.clock.phase == KerugiClock.GOLDEN_ROUND) REASON_GOLDEN_ROUND else REASON_FINAL_SCORE
        return when {
            score.disqualified != null -> BoutCompletedPayload(score.disqualified.opponent(), REASON_DISQUALIFICATION)
            winner != null -> BoutCompletedPayload(winner, REASON_PREFERENCE)
            score.blue > score.red -> BoutCompletedPayload(KerugiCompetitor.BLUE, reason)
            else -> BoutCompletedPayload(KerugiCompetitor.RED, reason)
        }
    }

    private fun project(event: SequencedDomainEvent) {
        val sessionId = event.event.sessionId
        val bout = if (event.event.type == KerugiBoutEvents.BOUT_STARTED) {
            KerugiBout.start(event).also { if (it.courtId == court.courtId) currentSessionId = sessionId }
        } else {
            // Events of a session whose start is missing (none in production) are not projected.
            bouts[sessionId]?.with(event) ?: return
        }
        bouts[sessionId] = bout
        eventsById[event.event.eventId] = event
        cursor = maxOf(cursor, event.sequence)
    }

    companion object {
        const val REASON_FINAL_SCORE = "final_score"
        const val REASON_DISQUALIFICATION = "disqualification"
        const val REASON_GOLDEN_ROUND = "golden_round"
        const val REASON_PREFERENCE = "preference"

        const val TIMER_AUTHOR = "timer"
        const val RESTORE_REASON = "server_restart"

        private val allowedPoints = mapOf(
            KerugiOperatorActionType.THROW to 1..3,
            KerugiOperatorActionType.SPIN_BONUS to 1..1,
            KerugiOperatorActionType.GAMJEOM to 1..1,
        )

        /** The domain device ID of a paired device, whose pairing ID is opaque (ADR-009). */
        fun deviceUuid(pairedDeviceId: String) = DeviceId(nameUuid("device:$pairedDeviceId"))
    }
}

private fun nameUuid(name: String): String = UUID.nameUUIDFromBytes(name.toByteArray()).toString()

private fun KerugiCompetitor.opponent() = if (this == KerugiCompetitor.BLUE) KerugiCompetitor.RED else KerugiCompetitor.BLUE
