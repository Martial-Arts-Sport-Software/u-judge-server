package org.mass.report

import org.mass.domain.KerugiCompetitor
import org.mass.domain.SessionState
import org.mass.draw.CategoryProgress
import org.mass.draw.DrawFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Russian words of the organiser's documents (ADR-012 §3, §8): XLSX and the protocols are Russian whatever the UI
 * language. CSV prints codes instead (§4).
 */
object ReportText {
    private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun date(date: java.time.LocalDate): String = DATE.format(date)

    /** UTC with `Z`, as the journal stores it (`AUD-004`). */
    fun utc(instant: Instant?): String = instant?.toString().orEmpty()

    /** The local time with its offset, so the value is unambiguous without the zone (`AUD-004`). */
    fun local(instant: Instant?, zone: ZoneId): String = instant?.atZone(zone)?.let(LOCAL::format).orEmpty()

    /** `5.6` from 56 tenths (ADR-011 §8). */
    fun tenths(value: Int?): String = value?.let {
        val absolute = Math.abs(it)
        "${if (it < 0) "-" else ""}${absolute / 10}.${absolute % 10}"
    }.orEmpty()

    fun side(side: KerugiCompetitor?): String = when (side) {
        KerugiCompetitor.BLUE -> "синий"
        KerugiCompetitor.RED -> "красный"
        null -> ""
    }

    fun kind(kind: SessionKind): String = when (kind) {
        SessionKind.KERUGI -> "Кёруги"
        SessionKind.TANBON -> "Танбон"
        SessionKind.TECHNICAL -> "Выступление"
    }

    fun state(state: SessionState): String = when (state) {
        SessionState.PREPARED -> "подготовлен"
        SessionState.RUNNING -> "идёт"
        SessionState.PAUSED -> "остановлен"
        SessionState.COMPLETED -> "завершён"
        SessionState.CANCELLED -> "отменён"
    }

    fun format(format: DrawFormat?): String = when (format) {
        DrawFormat.ELIMINATION -> "выбывание"
        DrawFormat.ROUND_ROBIN -> "круговая"
        DrawFormat.PERFORMANCE_ORDER -> "порядок выступлений"
        DrawFormat.NO_BOUTS -> "нет поединков"
        null -> "нет жеребьёвки"
    }

    fun progress(progress: CategoryProgress): String = when (progress) {
        CategoryProgress.FREE -> "свободна"
        CategoryProgress.IN_PROGRESS -> "в процессе"
        CategoryProgress.NO_BOUTS -> "нет поединков"
        CategoryProgress.NEEDS_REDRAW -> "требует новой жеребьёвки"
        CategoryProgress.COMPLETED -> "завершена"
    }

    fun status(status: HistoryStatus): String = when (status) {
        HistoryStatus.COUNTED -> "засчитан"
        HistoryStatus.NO_QUORUM -> "нет кворума"
        HistoryStatus.OUTSIDE_RUNNING_PERIOD -> "вне времени боя"
        HistoryStatus.CORRECTED -> "отменён"
        HistoryStatus.RESET -> "сброшен"
        HistoryStatus.APPLIED -> "применён"
        HistoryStatus.RECORDED -> "записано"
        HistoryStatus.AUDIT_ONLY -> "только аудит"
        HistoryStatus.TRIMMED -> "отброшена (мин./макс.)"
    }

    fun source(source: String): String = when (source) {
        "judge_device" -> "судья"
        "operator" -> "оператор"
        "server" -> "сервер"
        else -> source
    }

    fun action(action: String): String = ACTIONS[action] ?: action

    fun reason(reason: String?): String = when (reason) {
        "final_score" -> "по счёту"
        "golden_round" -> "золотой раунд"
        "preference" -> "предпочтение"
        "disqualification" -> "дисквалификация"
        "cancelled" -> "отменён"
        null -> ""
        else -> reason
    }

    /** The value code of a history row in words: `HEAD` → «голова», `THROW:2` → «бросок +2», a technical vector. */
    fun value(row: HistoryRow): String {
        val value = row.value
        return when {
            value.isEmpty() -> ""
            value == "HEAD" -> "голова"
            value == "BODY" -> "туловище"
            value == "CROSS" -> "крест"
            value.startsWith("THROW:") -> "бросок +${value.substringAfter(':')}"
            value.startsWith("SPIN_BONUS:") -> "вращение +${value.substringAfter(':')}"
            value.startsWith("GAMJEOM:") -> "Gamjeom"
            value.startsWith("BONUS:") || value.startsWith("PENALTY:") ->
                (if (value.startsWith("BONUS")) "бонус " else "штраф ") + tenths(value.substringAfter(':').toInt()).let { if (it.startsWith("-")) it else "+$it" }
            value.startsWith("T:") -> value.split(';').associate { it.substringBefore(':') to it.substringAfter(':') }.let { parts ->
                fun list(key: String) = parts[key].orEmpty().split(',').filter(String::isNotEmpty).joinToString(" ") { tenths(it.toInt()) }
                "техника ${list("T")}; презентация ${list("P")}; вычет ${tenths(parts["D"]?.toInt())}; итог ${tenths(parts["J"]?.toInt())}"
            }
            value.startsWith("round:") -> "раунд ${value.substringAfter(':')}"
            value.startsWith("break:") -> "перерыв перед раундом ${value.substringAfter(':')}"
            row.action == "kerugi_clock_adjusted" -> value.toLongOrNull()?.let { millis -> "%d:%02d".format(millis / 60_000, millis / 1000 % 60) } ?: value
            row.action == "kerugi_bout_reset" -> "событий: $value"
            row.action == "session_completed" && ':' in value -> value.substringAfter(':').toIntOrNull()?.let { "итог ${tenths(it)}" } ?: value
            else -> reason(value)
        }
    }

    private val ACTIONS = mapOf(
        "kerugi_bout_started" to "начало поединка (Кёруги)",
        "tanbon_bout_started" to "начало поединка (Танбон)",
        "technical_performance_started" to "начало выступления",
        "session_started" to "старт",
        "session_paused" to "пауза",
        "session_resumed" to "продолжение",
        "session_completed" to "результат",
        "session_cancelled" to "отмена",
        "kerugi_score_candidate" to "оценка судьи",
        "tanbon_score_candidate" to "оценка судьи",
        "kerugi_operator_action" to "действие оператора",
        "kerugi_score_correction" to "коррекция",
        "kerugi_bout_reset" to "сброс поединка",
        "kerugi_disqualification_confirmed" to "дисквалификация",
        "judge_attention_signalled" to "сигнал судьи",
        "judge_ready_signalled" to "готовность судьи",
        "kerugi_period_ended" to "конец периода",
        "kerugi_clock_adjusted" to "коррекция времени",
        "kerugi_clock_saved" to "сохранение времени",
        "kerugi_clock_stopped" to "остановка времени при перезапуске",
        "technical_score_sent" to "оценка судьи",
        "technical_adjustment_applied" to "решение арбитра",
        "technical_adjustment_withdrawn" to "отзыв решения арбитра",
    )
}
