package org.mass.performance

import kotlinx.serialization.Serializable
import org.mass.bout.BoutCorner
import org.mass.bout.BoutSeat
import org.mass.bout.KerugiBoutEvents

/**
 * Event types of a technical performance in the single `domain_events` journal (ADR-011 §5); one session is one
 * performance. The lifecycle events are the shared session events of the bouts.
 */
object TechnicalPerformanceEvents {
    const val PERFORMANCE_STARTED = "technical_performance_started"
    const val SESSION_STARTED = KerugiBoutEvents.SESSION_STARTED
    const val SESSION_COMPLETED = KerugiBoutEvents.SESSION_COMPLETED
    const val SESSION_CANCELLED = KerugiBoutEvents.SESSION_CANCELLED
    const val SCORE_SENT = "technical_score_sent"
    const val ADJUSTMENT_APPLIED = "technical_adjustment_applied"
    const val ADJUSTMENT_WITHDRAWN = "technical_adjustment_withdrawn"

    val ALL = setOf(
        PERFORMANCE_STARTED, SESSION_STARTED, SESSION_COMPLETED, SESSION_CANCELLED, SCORE_SENT, ADJUSTMENT_APPLIED,
        ADJUSTMENT_WITHDRAWN,
    )
}

/** Everything fixed at «Начать» (`TEC-010`): the performance of the order, the entry, the criteria, roster and formula. */
@Serializable
data class TechnicalPerformanceStartedPayload(
    val categoryId: String,
    val categoryTitle: String,
    val courtNumber: Int,
    val performanceNumber: Int,
    val round: Int,
    val index: Int,
    /** The ADR-011 §4 code, for example `hosinsool`. */
    val discipline: String,
    val techniqueCriteria: Int,
    val presentationCriteria: Int,
    val entry: BoutCorner,
    val seats: List<BoutSeat>,
    /** `trimmed_mean` or `mean`. */
    val formula: String,
)

/** A judge's final vector exactly as sent (`TEC-009`); [totalTenths] is the server's total, equal to the client's. */
@Serializable
data class TechnicalScoreSentPayload(
    val techniqueTenths: List<Int>,
    val presentationTenths: List<Int>,
    val deductionTenths: Int,
    val totalTenths: Int,
    val clientTimestamp: String,
    val sequence: Long,
)

/** The arbiter's penalty or bonus, separate from the criteria with its reason (`TEC-006`). */
enum class TechnicalAdjustmentKind(val amountTenths: Int) { BONUS(2), PENALTY(-3) }

@Serializable
data class TechnicalAdjustmentPayload(val kind: TechnicalAdjustmentKind, val amountTenths: Int, val reason: String)

/** A compensating event: the withdrawn adjustment stays in the journal. */
@Serializable
data class TechnicalAdjustmentWithdrawnPayload(val targetEventId: String, val reason: String)

/** The result at completion, recorded with the missing seats and the reason the operator gave for them (`TEC-014`). */
@Serializable
data class TechnicalCompletedPayload(
    val missingSeats: List<Int>,
    val reason: String?,
    val totalTenths: Int,
    val aggregateTenths: Int,
    val adjustmentTenths: Int,
    val divisor: Int,
    val formula: String,
)
