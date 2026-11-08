package org.mass.ui.report

import org.mass.domain.KerugiCompetitor
import org.mass.locale.Localization
import org.mass.report.HistoryRow
import org.mass.report.HistoryStatus
import org.mass.report.ReportText

/** The words of a history row in the UI language (ADR-012 §1, §8); the codes come from the server report. */
object HistoryText {
    /** Every journal event type the history can show; each has a `history_action_<type>` string. */
    val ACTIONS = listOf(
        "kerugi_bout_started", "tanbon_bout_started", "technical_performance_started", "session_started", "session_paused",
        "session_resumed", "session_completed", "session_cancelled", "kerugi_score_candidate", "tanbon_score_candidate",
        "kerugi_operator_action", "kerugi_score_correction", "kerugi_bout_reset", "kerugi_disqualification_confirmed",
        "judge_attention_signalled", "judge_ready_signalled", "kerugi_period_ended", "kerugi_clock_adjusted",
        "kerugi_clock_saved", "kerugi_clock_stopped", "technical_score_sent", "technical_adjustment_applied",
        "technical_adjustment_withdrawn",
    )

    val REASONS = listOf("final_score", "golden_round", "preference", "disqualification", "cancelled")

    fun action(action: String): String = if (action in ACTIONS) Localization.getString("history_action_$action") else action

    fun status(status: HistoryStatus): String = Localization.getString("history_status_${status.name.lowercase()}")

    fun source(source: String): String = when (source) {
        "judge_device", "operator", "server" -> Localization.getString("history_source_$source")
        else -> source
    }

    fun side(side: KerugiCompetitor?): String = when (side) {
        KerugiCompetitor.BLUE -> Localization.getString("bout_blue")
        KerugiCompetitor.RED -> Localization.getString("bout_red")
        null -> ""
    }

    fun reason(reason: String?): String = when (reason) {
        null -> ""
        in REASONS -> Localization.getString("history_reason_$reason")
        else -> reason
    }

    /** `HEAD` → «голова», `THROW:2` → «бросок +2», a technical vector with the judge total, a period or a time. */
    fun value(row: HistoryRow): String {
        val value = row.value
        val amount = value.substringAfter(':', "")
        return when {
            value.isEmpty() -> ""
            value in setOf("HEAD", "BODY", "CROSS") -> Localization.getString("history_value_${value.lowercase()}")
            value.startsWith("THROW:") -> Localization.getString("bout_throw").replace("%d", amount)
            value.startsWith("SPIN_BONUS:") -> Localization.getString("bout_spin")
            value.startsWith("GAMJEOM:") -> Localization.getString("bout_gamjeom")
            value.startsWith("BONUS:") -> Localization.getString("history_value_bonus").replace("%s", ReportText.tenths(amount.toIntOrNull()))
            value.startsWith("PENALTY:") -> Localization.getString("history_value_penalty").replace("%s", ReportText.tenths(amount.toIntOrNull()))
            value.startsWith("T:") -> {
                val parts = value.split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
                fun list(key: String) = parts[key].orEmpty().split(',').filter(String::isNotEmpty).joinToString(" ") { ReportText.tenths(it.toInt()) }
                Localization.getString("history_value_vector")
                    .replaceFirst("%s", list("T"))
                    .replaceFirst("%s", list("P"))
                    .replaceFirst("%s", ReportText.tenths(parts["D"]?.toIntOrNull()))
                    .replaceFirst("%s", ReportText.tenths(parts["J"]?.toIntOrNull()))
            }
            value.startsWith("round:") -> Localization.getString("history_value_round").replace("%s", amount)
            value.startsWith("break:") -> Localization.getString("history_value_break").replace("%s", amount)
            row.action == "kerugi_clock_adjusted" -> value.toLongOrNull()?.let { "%d:%02d".format(it / 60_000, it / 1000 % 60) } ?: value
            row.action == "kerugi_bout_reset" -> Localization.getString("history_value_reset").replace("%s", value)
            row.action == "session_completed" && ':' in value ->
                Localization.getString("history_value_total").replace("%s", ReportText.tenths(amount.toIntOrNull()))
            else -> reason(value)
        }
    }
}
