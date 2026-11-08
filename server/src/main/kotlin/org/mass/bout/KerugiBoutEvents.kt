package org.mass.bout

import kotlinx.serialization.Serializable
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.KerugiScoringArea

/** Event types of a Kerugi bout in the single `domain_events` journal (ADR-009); one session is one bout. */
object KerugiBoutEvents {
    const val BOUT_STARTED = "kerugi_bout_started"
    const val SESSION_STARTED = "session_started"
    const val SESSION_PAUSED = "session_paused"
    const val SESSION_RESUMED = "session_resumed"
    const val SESSION_COMPLETED = "session_completed"
    const val SESSION_CANCELLED = "session_cancelled"
    const val SCORE_CANDIDATE = "kerugi_score_candidate"
    const val OPERATOR_ACTION = "kerugi_operator_action"
    const val SCORE_CORRECTION = "kerugi_score_correction"
    const val BOUT_RESET = "kerugi_bout_reset"
    const val DISQUALIFICATION_CONFIRMED = "kerugi_disqualification_confirmed"
    const val JUDGE_ATTENTION = "judge_attention_signalled"

    val ALL = setOf(
        BOUT_STARTED, SESSION_STARTED, SESSION_PAUSED, SESSION_RESUMED, SESSION_COMPLETED, SESSION_CANCELLED,
        SCORE_CANDIDATE, OPERATOR_ACTION, SCORE_CORRECTION, BOUT_RESET, DISQUALIFICATION_CONFIRMED, JUDGE_ATTENTION,
    )

    const val JUDGE_SOURCE = "judge_device"
    const val OPERATOR_SOURCE = "operator"
}

@Serializable
data class BoutAthlete(val fullName: String, val city: String)

/** One side of the bout as the snapshot shows it: blue is the upper slot of the bracket bout, red the lower one. */
@Serializable
data class BoutCorner(val entryId: String, val label: String, val athletes: List<BoutAthlete>)

/** A judge seat of the roster; [deviceId] is the paired device ID, [surname] the judge's pairing surname. */
@Serializable
data class BoutSeat(val number: Int, val judgeId: String, val deviceId: String, val surname: String)

/** Everything fixed for the bout at «Начать»: the bout of the bracket, the sides, the roster and the settings. */
@Serializable
data class KerugiBoutStartedPayload(
    val categoryId: String,
    val categoryTitle: String,
    val courtNumber: Int,
    val boutNumber: Int,
    val round: Int,
    val index: Int,
    val blue: BoutCorner,
    val red: BoutCorner,
    val seats: List<BoutSeat>,
    val quorum: Int,
    val coincidenceWindowMillis: Long,
)

/** A raw side-judge tap; [clientTimestamp] is the tap time corrected to the server clock, [sequence] the outbox one. */
@Serializable
data class BoutScoreCandidatePayload(
    val competitor: KerugiCompetitor,
    val area: KerugiScoringArea,
    val clientTimestamp: String,
    val sequence: Long,
)

@Serializable
data class BoutAttentionPayload(val clientTimestamp: String, val sequence: Long)

@Serializable
data class BoutOperatorActionPayload(val type: KerugiOperatorActionType, val competitor: KerugiCompetitor, val points: Int)

@Serializable
data class BoutCorrectionPayload(val targetEventId: String)

/** `SES-006`: the bout reset compensates every effective score event; the originals stay in the journal. */
@Serializable
data class BoutResetPayload(val reason: String, val targetEventIds: List<String>)

@Serializable
data class BoutDisqualificationPayload(val competitor: KerugiCompetitor)

@Serializable
data class BoutCompletedPayload(val winner: KerugiCompetitor, val reason: String)

@Serializable
data class BoutCancelledPayload(val reason: String)
