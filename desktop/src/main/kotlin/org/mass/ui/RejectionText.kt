package org.mass.ui

import org.mass.bout.BoutRejection
import org.mass.bout.CourtBouts
import org.mass.locale.Localization
import org.mass.performance.PerformanceRejection

/**
 * The words of the server's typed rejection codes (ADR-012 §8): every code of [CODES] has a `rejection_<code>` string in both
 * languages, so the operator never sees a raw code; an unknown code is shown as it is.
 */
object RejectionText {
    val CODES = listOf(
        BoutRejection.NOT_PERMITTED, BoutRejection.EVENT_ID_CONFLICT, BoutRejection.SESSION_NOT_CURRENT,
        BoutRejection.SESSION_NOT_STARTED, BoutRejection.SESSION_CLOSED, BoutRejection.JUDGE_NOT_ASSIGNED,
        BoutRejection.INVALID_CLIENT_TIMESTAMP, BoutRejection.JOURNAL_UNAVAILABLE, BoutRejection.DISCIPLINE_MISMATCH,
        BoutRejection.BOUT_IN_PROGRESS, BoutRejection.BOUT_ALREADY_JUDGED, BoutRejection.INVALID_ROSTER,
        BoutRejection.INVALID_SETTINGS, BoutRejection.INVALID_TRANSITION, BoutRejection.INVALID_POINTS,
        BoutRejection.INVALID_CORRECTION_TARGET, BoutRejection.NOTHING_TO_RESET, BoutRejection.REASON_REQUIRED,
        BoutRejection.DISQUALIFICATION_NOT_PENDING, BoutRejection.DISQUALIFICATION_CONFIRMED, BoutRejection.SCORE_TIED,
        BoutRejection.BOUT_NOT_FINISHED, BoutRejection.WINNER_REQUIRED, BoutRejection.WINNER_NOT_ALLOWED,
        BoutRejection.INVALID_CLOCK,
        CourtBouts.NO_COMPETITION, CourtBouts.NOT_CONFIRMED, CourtBouts.JUDGE_NOT_CONNECTED, CourtBouts.NO_NEXT_BOUT,
        CourtBouts.NOT_BOUT, CourtBouts.NOT_PERFORMANCE,
        PerformanceRejection.INVALID_SCORE_VECTOR, PerformanceRejection.TOTAL_MISMATCH,
        PerformanceRejection.SCORES_MISSING, PerformanceRejection.NOT_ENOUGH_SCORES,
        PerformanceRejection.BONUS_NOT_ALLOWED, PerformanceRejection.INVALID_ADJUSTMENT_TARGET,
    )

    fun of(code: String): String = if (code in CODES) Localization.getString("rejection_$code") else code
}
