package org.mass.report

import kotlinx.serialization.json.Json
import org.mass.applications.ApplicationCategory
import org.mass.applications.CategoryEntry
import org.mass.applications.CompetitionApplications
import org.mass.bout.BoutClockAdjustedPayload
import org.mass.bout.BoutCompletedPayload
import org.mass.bout.BoutDiscipline
import org.mass.bout.BoutPeriodEndedPayload
import org.mass.bout.BoutResetPayload
import org.mass.bout.BoutScoreCandidatePayload
import org.mass.bout.KerugiBout
import org.mass.bout.KerugiBoutEvents
import org.mass.bout.TanbonScoreCandidatePayload
import org.mass.domain.AggregationFormula
import org.mass.domain.KerugiCompetitor
import org.mass.domain.SessionState
import org.mass.draw.CategoryProgress
import org.mass.draw.CompetitionOfficials
import org.mass.draw.CourtsBoard
import org.mass.draw.DrawFormat
import org.mass.performance.TechnicalAdjustmentWithdrawnPayload
import org.mass.performance.TechnicalPerformance
import org.mass.performance.TechnicalPerformanceEvents
import org.mass.performance.TechnicalScoreSentPayload
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The kind of a session: a Kerugi or a Tanbon bout, or a technical performance (ADR-011). */
enum class SessionKind { KERUGI, TANBON, TECHNICAL }

/**
 * Why an input did or did not count (ADR-012 §1.2): the statuses of the bout history (`KER-009`), plus [TRIMMED] for a
 * judge total the trimmed mean leaves out of a technical result (ADR-011 §8).
 */
enum class HistoryStatus { COUNTED, NO_QUORUM, OUTSIDE_RUNNING_PERIOD, CORRECTED, RESET, APPLIED, RECORDED, AUDIT_ONLY, TRIMMED }

/** A place of a category; [place] is null where the system does not decide it (ADR-012 §2). */
data class PlacedEntry(val place: Int?, val entry: CategoryEntry, val panelDecision: Boolean = false)

data class CategoryReport(
    val category: ApplicationCategory,
    val court: Int,
    val format: DrawFormat?,
    val progress: CategoryProgress,
    /** Empty until every bout or performance of the category is decided. */
    val places: List<PlacedEntry>,
)

/** One started bout or performance with its result. */
data class SessionReport(
    val kind: SessionKind,
    val sessionId: String,
    val number: Int,
    val court: Int,
    val categoryId: String,
    val categoryTitle: String,
    val round: Int,
    val index: Int,
    /** The blue side of a bout or the entry of a performance. */
    val blue: String,
    val red: String?,
    val state: SessionState,
    val blueScore: Int?,
    val redScore: Int?,
    val gamjeomBlue: Int?,
    val gamjeomRed: Int?,
    val winner: KerugiCompetitor?,
    val reason: String?,
    /** Technical results in tenths (ADR-011 §8). */
    val totalTenths: Int?,
    val aggregateTenths: Int?,
    val adjustmentTenths: Int?,
    val divisor: Int?,
    val formula: String?,
    /** «1. Иванов» in seat order. */
    val judges: List<String>,
    val quorum: Int?,
    val windowMillis: Long?,
    val startedAt: Instant,
    val completedAt: Instant?,
) {
    val winnerLabel: String?
        get() = when (winner) {
            KerugiCompetitor.BLUE -> blue
            KerugiCompetitor.RED -> red
            null -> null
        }
}

/** One journaled input of a session (ADR-012 §1.1): the row of the history, of «Оценки» and of the CSV. */
data class HistoryRow(
    val sequence: Long,
    val eventId: String,
    val occurredAt: Instant,
    /** The judge's tap or Send time corrected to the server clock. */
    val clientTimestamp: Instant?,
    val court: Int,
    val categoryId: String,
    val categoryTitle: String,
    val kind: SessionKind,
    val number: Int,
    val sessionId: String,
    val source: String,
    val author: String,
    val seat: Int?,
    val judge: String?,
    /** The journal event type. */
    val action: String,
    val side: KerugiCompetitor?,
    /** A code: `HEAD`, `THROW:2`, `T:8,7,6,5;P:7,7,7,7;D:0;J:56`, a result reason, a remaining time. */
    val value: String,
    val status: HistoryStatus,
    val referenceEventId: String?,
    val reason: String?,
)

/** The filters of the history (`AUD-003`); null matches everything, set filters are combined with AND. */
data class HistoryFilter(
    val court: Int? = null,
    val categoryId: String? = null,
    val sessionId: String? = null,
    val judge: String? = null,
    val action: String? = null,
) {
    fun matches(row: HistoryRow): Boolean =
        (court == null || row.court == court) &&
            (categoryId == null || row.categoryId == categoryId) &&
            (sessionId == null || row.sessionId == sessionId) &&
            (judge == null || row.judge == judge) &&
            (action == null || row.action == action)
}

data class ReportCompetition(
    val id: String,
    val name: String,
    val date: LocalDate,
    val officials: CompetitionOfficials,
    /** When the report was built; with the competition ID it distinguishes two files (`REP-004`). */
    val generatedAt: Instant,
    /** The local zone of the times next to UTC (`AUD-004`). */
    val zone: ZoneId,
)

/**
 * Everything the exports print (ADR-012 §1-§4), rebuilt from the projections of the journal on every call: the competition,
 * the places of every category, every started session and every input with the reason it did or did not count.
 */
data class CompetitionReport(
    val competition: ReportCompetition,
    val categories: List<CategoryReport>,
    val sessions: List<SessionReport>,
    val history: List<HistoryRow>,
) {
    fun history(filter: HistoryFilter): List<HistoryRow> = history.filter(filter::matches)

    companion object {
        const val FORMAT = "u-judge-report-v1"

        fun of(
            applications: CompetitionApplications,
            board: CourtsBoard,
            bouts: List<KerugiBout>,
            performances: List<TechnicalPerformance>,
            generatedAt: Instant,
            zone: ZoneId,
        ): CompetitionReport {
            val competitionId = checkNotNull(board.competitionId) { "No competition" }
            val ownBouts = bouts.filter { it.competitionId.value == competitionId }
            val ownPerformances = performances.filter { it.competitionId.value == competitionId }
            val sessions = (ownBouts.map(::boutReport) + ownPerformances.map(::performanceReport))
                .sortedWith(compareBy(SessionReport::number, SessionReport::startedAt))
            val history = (ownBouts.flatMap(::boutHistory) + ownPerformances.flatMap(::performanceHistory)).sortedBy(HistoryRow::sequence)
            val categories = board.ordered.map { state ->
                val id = state.category.id
                val places = Places.of(state.category, state.numbered, board.results[id], ownPerformances.filter { it.started.categoryId == id })
                // A performance order has no bracket progress: it is complete once every entry performed and is placed.
                val performed = state.numbered?.format == DrawFormat.PERFORMANCE_ORDER && places.isNotEmpty()
                CategoryReport(
                    category = state.category,
                    court = board.courtOf(id),
                    format = state.numbered?.format,
                    progress = if (performed) CategoryProgress.COMPLETED else board.progress(state),
                    places = places,
                )
            }
            return CompetitionReport(
                ReportCompetition(competitionId, applications.competitionName, applications.competitionDate, board.officials, generatedAt, zone),
                categories,
                sessions,
                history,
            )
        }

        private fun boutReport(bout: KerugiBout): SessionReport {
            val started = bout.started
            val score = bout.score()
            val tanbon = bout.discipline == BoutDiscipline.TANBON
            return SessionReport(
                kind = if (tanbon) SessionKind.TANBON else SessionKind.KERUGI,
                sessionId = bout.sessionId.value,
                number = started.boutNumber,
                court = started.courtNumber,
                categoryId = started.categoryId,
                categoryTitle = started.categoryTitle,
                round = started.round,
                index = started.index,
                blue = started.blue.label,
                red = started.red.label,
                state = bout.state,
                blueScore = score.blue,
                redScore = score.red,
                gamjeomBlue = score.gamjeomBlue.takeUnless { tanbon },
                gamjeomRed = score.gamjeomRed.takeUnless { tanbon },
                winner = bout.result?.winner,
                reason = bout.result?.reason ?: bout.events.lastOrNull { it.event.type == KerugiBoutEvents.SESSION_CANCELLED }
                    ?.let { "cancelled" },
                totalTenths = null,
                aggregateTenths = null,
                adjustmentTenths = null,
                divisor = null,
                formula = null,
                judges = started.seats.map { "${it.number}. ${it.surname}" },
                quorum = started.quorum,
                windowMillis = started.coincidenceWindowMillis,
                startedAt = bout.events.first().event.occurredAt,
                completedAt = closedAt(bout.events.map { it.event.type to it.event.occurredAt }),
            )
        }

        private fun performanceReport(performance: TechnicalPerformance): SessionReport {
            val started = performance.started
            val result = performance.result()
            return SessionReport(
                kind = SessionKind.TECHNICAL,
                sessionId = performance.sessionId.value,
                number = started.performanceNumber,
                court = started.courtNumber,
                categoryId = started.categoryId,
                categoryTitle = started.categoryTitle,
                round = started.round,
                index = started.index,
                blue = started.entry.label,
                red = null,
                state = performance.state,
                blueScore = null,
                redScore = null,
                gamjeomBlue = null,
                gamjeomRed = null,
                winner = null,
                reason = performance.completion?.reason ?: performance.cancelReason,
                totalTenths = result?.totalTenths,
                aggregateTenths = result?.aggregateTenths,
                adjustmentTenths = result?.adjustmentTenths,
                divisor = result?.divisor,
                formula = started.formula,
                judges = started.seats.map { "${it.number}. ${it.surname}" },
                quorum = null,
                windowMillis = null,
                startedAt = performance.events.first().event.occurredAt,
                completedAt = closedAt(performance.events.map { it.event.type to it.event.occurredAt }),
            )
        }

        private fun closedAt(events: List<Pair<String, Instant>>): Instant? = events.lastOrNull { (type, _) ->
            type == KerugiBoutEvents.SESSION_COMPLETED || type == KerugiBoutEvents.SESSION_CANCELLED
        }?.second

        private fun boutHistory(bout: KerugiBout): List<HistoryRow> {
            val entries = bout.history().associateBy { it.eventId }
            return bout.events.map { sequenced ->
                val event = sequenced.event
                val entry = entries.getValue(event.eventId)
                val seat = bout.seatOfJudge(event.judgeId.value)
                var clientTimestamp: Instant? = null
                var reference: String? = null
                var reason: String? = null
                val value = when (event.type) {
                    KerugiBoutEvents.SCORE_CANDIDATE -> Json.decodeFromString<BoutScoreCandidatePayload>(event.payload)
                        .also { clientTimestamp = Instant.parse(it.clientTimestamp) }.area.name
                    KerugiBoutEvents.TANBON_SCORE_CANDIDATE -> Json.decodeFromString<TanbonScoreCandidatePayload>(event.payload)
                        .also { clientTimestamp = Instant.parse(it.clientTimestamp) }.area.name
                    KerugiBoutEvents.SCORE_CORRECTION -> "".also { reference = entry.detail }
                    KerugiBoutEvents.BOUT_RESET -> Json.decodeFromString<BoutResetPayload>(event.payload)
                        .also { reason = it.reason }.targetEventIds.size.toString()
                    KerugiBoutEvents.SESSION_CANCELLED -> "".also { reason = entry.detail }
                    KerugiBoutEvents.SESSION_COMPLETED -> Json.decodeFromString<BoutCompletedPayload>(event.payload).reason
                    KerugiBoutEvents.PERIOD_ENDED -> Json.decodeFromString<BoutPeriodEndedPayload>(event.payload)
                        .let { "${it.period}:${it.round}" }
                    KerugiBoutEvents.CLOCK_ADJUSTED ->
                        Json.decodeFromString<BoutClockAdjustedPayload>(event.payload).remainingMillis.toString()
                    else -> entry.detail
                }
                HistoryRow(
                    sequence = sequenced.sequence,
                    eventId = event.eventId.value,
                    occurredAt = event.occurredAt,
                    clientTimestamp = clientTimestamp,
                    court = bout.started.courtNumber,
                    categoryId = bout.started.categoryId,
                    categoryTitle = bout.started.categoryTitle,
                    kind = if (bout.discipline == BoutDiscipline.TANBON) SessionKind.TANBON else SessionKind.KERUGI,
                    number = bout.started.boutNumber,
                    sessionId = bout.sessionId.value,
                    source = event.source.value,
                    author = event.author,
                    seat = seat?.number,
                    judge = seat?.surname,
                    action = event.type,
                    side = entry.competitor,
                    value = value,
                    status = HistoryStatus.valueOf(entry.status.name),
                    referenceEventId = reference,
                    reason = reason,
                )
            }
        }

        private fun performanceHistory(performance: TechnicalPerformance): List<HistoryRow> {
            val withdrawn = performance.adjustments.filter { it.withdrawn }.map { it.eventId.value }.toSet()
            val trimmed = trimmedSeats(performance)
            return performance.events.map { sequenced ->
                val event = sequenced.event
                val seat = performance.seatOfJudge(event.judgeId.value)
                var clientTimestamp: Instant? = null
                var reference: String? = null
                var reason: String? = null
                var status = HistoryStatus.RECORDED
                val value = when (event.type) {
                    TechnicalPerformanceEvents.SCORE_SENT -> {
                        val payload = Json.decodeFromString<TechnicalScoreSentPayload>(event.payload)
                        clientTimestamp = Instant.parse(payload.clientTimestamp)
                        status = if (seat?.number in trimmed) HistoryStatus.TRIMMED else HistoryStatus.COUNTED
                        "T:${payload.techniqueTenths.joinToString(",")};P:${payload.presentationTenths.joinToString(",")};" +
                            "D:${payload.deductionTenths};J:${payload.totalTenths}"
                    }
                    TechnicalPerformanceEvents.ADJUSTMENT_APPLIED -> {
                        val adjustment = performance.adjustments.single { it.eventId == event.eventId }
                        reason = adjustment.reason
                        status = if (event.eventId.value in withdrawn) HistoryStatus.CORRECTED else HistoryStatus.APPLIED
                        "${adjustment.kind.name}:${adjustment.amountTenths}"
                    }
                    TechnicalPerformanceEvents.ADJUSTMENT_WITHDRAWN ->
                        Json.decodeFromString<TechnicalAdjustmentWithdrawnPayload>(event.payload).let {
                            reference = it.targetEventId
                            reason = it.reason
                            ""
                        }
                    TechnicalPerformanceEvents.SESSION_COMPLETED -> performance.completion?.let {
                        reason = it.reason
                        "${it.formula}:${it.totalTenths}"
                    }.orEmpty()
                    TechnicalPerformanceEvents.SESSION_CANCELLED -> "".also { reason = performance.cancelReason }
                    else -> ""
                }
                HistoryRow(
                    sequence = sequenced.sequence,
                    eventId = event.eventId.value,
                    occurredAt = event.occurredAt,
                    clientTimestamp = clientTimestamp,
                    court = performance.started.courtNumber,
                    categoryId = performance.started.categoryId,
                    categoryTitle = performance.started.categoryTitle,
                    kind = SessionKind.TECHNICAL,
                    number = performance.started.performanceNumber,
                    sessionId = performance.sessionId.value,
                    source = event.source.value,
                    author = event.author,
                    seat = seat?.number,
                    judge = seat?.surname,
                    action = event.type,
                    side = null,
                    value = value,
                    status = status,
                    referenceEventId = reference,
                    reason = reason,
                )
            }
        }

        /**
         * The seats whose totals the trimmed mean leaves out: one lowest and one highest total (ADR-011 §8). Among equal
         * totals the lower seat is the dropped lowest and the higher seat the dropped highest; only the values matter for
         * the result, the seats are shown to explain it.
         */
        private fun trimmedSeats(performance: TechnicalPerformance): Set<Int> {
            if (performance.formula != AggregationFormula.TRIMMED_MEAN || performance.sends.size < performance.formula.minimumScores) return emptySet()
            val sends = performance.sends.values.sortedWith(compareBy({ it.vector.totalTenths }, { it.seat }))
            return setOf(sends.first().seat, sends.last().seat)
        }
    }
}
