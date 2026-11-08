package org.mass.bout

import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.CourtId
import org.mass.domain.EventId
import org.mass.domain.JudgeId
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.KerugiScoringArea
import org.mass.domain.PeerId
import org.mass.domain.SessionId
import java.time.Instant

/** Who sends a command: a paired judge device over the realtime socket or the operator in the desktop process. */
sealed interface BoutOrigin {
    data class Judge(val deviceId: String) : BoutOrigin

    data class Operator(val author: String) : BoutOrigin
}

/** The audit context a judge copies from its snapshot (ADR-009 §6); the server checks every ID against the seat. */
data class JudgeContext(
    val competitionId: CompetitionId,
    val peerId: PeerId,
    val courtId: CourtId,
    val bracketId: BracketId,
    val sessionId: SessionId,
    val judgeId: JudgeId,
    val deviceId: String,
)

/** A judge at «Начать»: a connected paired device and the surname it paired with. */
data class RosterJudge(val deviceId: String, val surname: String)

/** The bout of a confirmed bracket the operator starts, with the roster and the settings fixed for the bout. */
data class BoutStartSpec(
    val competitionId: CompetitionId,
    val bracketId: BracketId,
    val categoryId: String,
    val categoryTitle: String,
    val boutNumber: Int,
    val round: Int,
    val index: Int,
    val blue: BoutCorner,
    val red: BoutCorner,
    val judges: List<RosterJudge>,
    val courtNumber: Int = 1,
    val quorum: Int = 2,
    val coincidenceWindowMillis: Long = 1_000,
    /** Durations by the category's age class (ADR-010 §1). */
    val timing: BoutTiming = BoutTiming(),
)

enum class BoutStateAction { START, PAUSE, RESUME }

sealed interface BoutCommand {
    val eventId: EventId

    /** A judge command; only these come from a device. */
    sealed interface FromJudge : BoutCommand {
        val sequence: Long
        val clientTimestamp: Instant
        val context: JudgeContext
    }

    /** An operator command; only the desktop sends these. */
    sealed interface FromOperator : BoutCommand

    data class SubmitScore(
        override val eventId: EventId,
        override val sequence: Long,
        override val clientTimestamp: Instant,
        override val context: JudgeContext,
        val competitor: KerugiCompetitor,
        val area: KerugiScoringArea,
    ) : FromJudge

    data class SignalAttention(
        override val eventId: EventId,
        override val sequence: Long,
        override val clientTimestamp: Instant,
        override val context: JudgeContext,
    ) : FromJudge

    /** «Готов»: the seated judge is ready for the bout (ADR-010 §9). */
    data class SignalReady(
        override val eventId: EventId,
        override val sequence: Long,
        override val clientTimestamp: Instant,
        override val context: JudgeContext,
    ) : FromJudge

    data class StartBout(override val eventId: EventId, val spec: BoutStartSpec) : FromOperator

    data class ChangeState(override val eventId: EventId, val sessionId: SessionId, val action: BoutStateAction) : FromOperator

    data class AwardAction(
        override val eventId: EventId,
        val sessionId: SessionId,
        val type: KerugiOperatorActionType,
        val competitor: KerugiCompetitor,
        val points: Int,
    ) : FromOperator

    data class CorrectScore(override val eventId: EventId, val sessionId: SessionId, val targetEventId: EventId) : FromOperator

    data class ResetBout(override val eventId: EventId, val sessionId: SessionId, val reason: String) : FromOperator

    data class ConfirmDisqualification(
        override val eventId: EventId,
        val sessionId: SessionId,
        val competitor: KerugiCompetitor,
    ) : FromOperator

    /** «Стоп периода»: ends the current round or break now (ADR-010 §2). */
    data class EndPeriod(override val eventId: EventId, val sessionId: SessionId) : FromOperator

    /** «Время»: the remaining time of a stopped round, set by the technical assistant (FHR 2024 §3.3.3). */
    data class AdjustClock(override val eventId: EventId, val sessionId: SessionId, val remainingMillis: Long) : FromOperator

    /** [winner] is the operator's choice by preference after a scoreless golden round, otherwise null (ADR-010 §4). */
    data class CompleteBout(
        override val eventId: EventId,
        val sessionId: SessionId,
        val winner: KerugiCompetitor? = null,
    ) : FromOperator

    data class CancelBout(override val eventId: EventId, val sessionId: SessionId, val reason: String) : FromOperator
}

/** Typed rejection codes; the judge ones are the wire codes of ADR-009 §8. */
object BoutRejection {
    const val NOT_PERMITTED = "command_not_permitted"
    const val EVENT_ID_CONFLICT = "event_id_conflict"
    const val SESSION_NOT_CURRENT = "session_not_current"
    const val SESSION_NOT_STARTED = "session_not_started"
    const val SESSION_CLOSED = "session_closed"
    const val JUDGE_NOT_ASSIGNED = "judge_not_assigned"
    const val INVALID_CLIENT_TIMESTAMP = "invalid_client_timestamp"
    const val JOURNAL_UNAVAILABLE = "journal_unavailable"

    const val BOUT_IN_PROGRESS = "bout_in_progress"
    const val BOUT_ALREADY_JUDGED = "bout_already_judged"
    const val INVALID_ROSTER = "invalid_roster"
    const val INVALID_SETTINGS = "invalid_settings"
    const val INVALID_TRANSITION = "invalid_transition"
    const val INVALID_POINTS = "invalid_points"
    const val INVALID_CORRECTION_TARGET = "invalid_correction_target"
    const val NOTHING_TO_RESET = "nothing_to_reset"
    const val REASON_REQUIRED = "reason_required"
    const val DISQUALIFICATION_NOT_PENDING = "disqualification_not_pending"
    const val DISQUALIFICATION_CONFIRMED = "disqualification_confirmed"
    const val SCORE_TIED = "score_tied"
    const val BOUT_NOT_FINISHED = "bout_not_finished"
    const val WINNER_REQUIRED = "winner_required"
    const val WINNER_NOT_ALLOWED = "winner_not_allowed"
    const val INVALID_CLOCK = "invalid_clock"
}

/** What an applied event changed, so the publisher sends only the matching pushes. */
enum class BoutChange { NEW_SESSION, STATE, TIMER, SCORE }

sealed interface BoutOutcome {
    data class Applied(
        val eventId: EventId,
        val sessionId: SessionId,
        val sequence: Long,
        val isNew: Boolean,
        val changes: Set<BoutChange>,
        /** Events the server journaled as a consequence, published after this one: the golden point's result (ADR-010 §4). */
        val followUps: List<Applied> = emptyList(),
    ) : BoutOutcome

    data class Rejected(val code: String) : BoutOutcome
}
