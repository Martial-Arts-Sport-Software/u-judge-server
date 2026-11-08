package org.mass.bout

import org.mass.DeviceConnectionState
import org.mass.PairingRequests
import org.mass.RealtimeHub
import org.mass.SessionView
import org.mass.applications.AgeClass
import org.mass.applications.ApplicationCategory
import org.mass.applications.ApplicationDiscipline
import org.mass.applications.Weapon
import org.mass.domain.AggregationFormula
import org.mass.domain.TechnicalDiscipline
import org.mass.domain.TechnicalPlace
import org.mass.domain.TechnicalScoring
import org.mass.performance.PerformanceCommand
import org.mass.performance.PerformanceStartSpec
import org.mass.performance.TechnicalPerformance
import org.mass.performance.TechnicalPerformanceService
import org.mass.applications.CategoryEntry
import org.mass.domain.CompetitionId
import org.mass.domain.EventId
import org.mass.domain.SessionState
import org.mass.draw.BoutDecision
import org.mass.draw.CategoryStatus
import org.mass.draw.CompetitionCourtsService
import org.mass.draw.CompetitionDrawService
import org.mass.draw.CourtItem
import org.mass.draw.CourtPanel
import org.mass.draw.CourtProgress
import org.mass.draw.DrawOutcome
import org.mass.draw.ItemRef

/**
 * The spectator window (ADR-010 §8): the current bout as a connected device without a seat receives it, and the call list
 * «Приглашаются» ([invited], the next bout) and «Готовятся» ([preparing], the bout after it). Read-only.
 */
data class SpectatorView(val court: Int, val session: SessionView?, val invited: CourtItem?, val preparing: CourtItem?)

/** A paired device the operator can seat: approved, not revoked and connected now. */
data class ConnectedJudge(val deviceId: String, val surname: String, val platform: String)

sealed interface CourtBoutOutcome {
    data class Done(val outcome: BoutOutcome.Applied) : CourtBoutOutcome

    data class Rejected(val code: String) : CourtBoutOutcome

    /** A journal write failed; the operator sees the diagnostic (`UI-007`). */
    data class Failed(val diagnostic: String) : CourtBoutOutcome
}

/**
 * The operator side of the court: the court panel of I4b with the current and the next bout, the connected judges and
 * «Начать», which fixes the roster of connected devices, locks the category's draw on its first bout (`BRK-002`,
 * `CMP-006`) and starts the next bout. Every later operator action goes through the same realtime dispatcher as the
 * judges (ADR-009 §11).
 */
class CourtBouts(
    private val realtime: RealtimeHub,
    private val draws: CompetitionDrawService,
    private val courts: CompetitionCourtsService,
    private val pairing: PairingRequests,
) {
    val bouts: KerugiBoutService
        get() = realtime.bouts

    val performances: TechnicalPerformanceService
        get() = realtime.performances

    fun panel(): CourtPanel = courts.courtPanel()

    fun spectator(): SpectatorView {
        val panel = panel()
        val calls = listOfNotNull(panel.next) + panel.queue.filter { it.ref != panel.next?.ref }
        return SpectatorView(panel.court, realtime.session(null), calls.getOrNull(0), calls.getOrNull(1))
    }

    fun connectedJudges(): List<ConnectedJudge> = pairing.operatorRegistry().devices
        .filter { !it.revoked && it.connectionState == DeviceConnectionState.CONNECTED }
        .map { ConnectedJudge(it.deviceId, it.surname, it.platform) }

    /**
     * «Начать» of the court panel: starts its next bout; [judges] are device IDs in seat order, all connected now. A bout of
     * a «Танбон» category is a Tanbon bout, of a «Весовая категория» one a Kerugi bout (ADR-011 §3).
     */
    fun start(judges: List<String>, quorum: Int, coincidenceWindowMillis: Long, author: String): CourtBoutOutcome {
        val board = draws.board()
        val competitionId = board.competitionId ?: return CourtBoutOutcome.Rejected(NO_COMPETITION)
        val bout = when (val next = courts.courtPanel().next) {
            null -> return CourtBoutOutcome.Rejected(NO_NEXT_BOUT)
            is CourtItem.Performance -> return CourtBoutOutcome.Rejected(NOT_BOUT)
            is CourtItem.Bout -> next
        }
        val discipline = boutDiscipline(bout.category) ?: return CourtBoutOutcome.Rejected(NOT_BOUT)
        val connected = connectedJudges().associateBy(ConnectedJudge::deviceId)
        if (judges.any { it !in connected }) return CourtBoutOutcome.Rejected(JUDGE_NOT_CONNECTED)
        val spec = BoutStartSpec(
            competitionId = CompetitionId(competitionId),
            bracketId = bout.bracketId,
            categoryId = bout.category.id,
            categoryTitle = bout.category.title,
            courtNumber = bout.court,
            boutNumber = bout.number,
            round = bout.ref.round,
            index = bout.ref.index,
            blue = corner(bout.blue),
            red = corner(bout.red),
            judges = judges.map { RosterJudge(it, connected.getValue(it).surname) },
            quorum = quorum,
            coincidenceWindowMillis = coincidenceWindowMillis,
            timing = BoutTiming.of(bout.category.section.ageClass),
            discipline = discipline,
        )
        bouts.startRejection(spec)?.let { return CourtBoutOutcome.Rejected(it) }
        if (realtime.courtBusy()) return CourtBoutOutcome.Rejected(BoutRejection.BOUT_IN_PROGRESS)
        if (board.category(bout.category.id)?.status == CategoryStatus.CONFIRMED) {
            when (val locked = draws.start(bout.category.id)) {
                DrawOutcome.Done -> Unit
                is DrawOutcome.Failed -> return CourtBoutOutcome.Failed(locked.diagnostic)
                is DrawOutcome.Rejected -> return CourtBoutOutcome.Rejected(NOT_CONFIRMED)
            }
        }
        return command(author, BoutCommand.StartBout(EventId.new(), spec))
    }

    /**
     * «Начать» for a performance of the court panel (ADR-011 §5): [judges] are 1-7 connected device IDs in seat order and
     * [formula] the aggregation fixed for the performance. Locks the category's draw on its first performance.
     */
    fun startPerformance(judges: List<String>, formula: AggregationFormula, author: String): CourtBoutOutcome {
        val board = draws.board()
        val competitionId = board.competitionId ?: return CourtBoutOutcome.Rejected(NO_COMPETITION)
        val item = courts.courtPanel().next as? CourtItem.Performance ?: return CourtBoutOutcome.Rejected(NOT_PERFORMANCE)
        val discipline = technicalDiscipline(item.category) ?: return CourtBoutOutcome.Rejected(NOT_PERFORMANCE)
        val connected = connectedJudges().associateBy(ConnectedJudge::deviceId)
        if (judges.any { it !in connected }) return CourtBoutOutcome.Rejected(JUDGE_NOT_CONNECTED)
        if (realtime.courtBusy()) return CourtBoutOutcome.Rejected(BoutRejection.BOUT_IN_PROGRESS)
        val spec = PerformanceStartSpec(
            competitionId = CompetitionId(competitionId),
            bracketId = item.bracketId,
            categoryId = item.category.id,
            categoryTitle = item.category.title,
            performanceNumber = item.number,
            round = item.ref.round,
            index = item.ref.index,
            entry = corner(item.entry),
            discipline = discipline,
            techniqueCriteria = discipline.techniqueCriteria(younger = item.category.section.ageClass in YOUNGER_CLASSES),
            judges = judges.map { RosterJudge(it, connected.getValue(it).surname) },
            courtNumber = item.court,
            formula = formula,
        )
        realtime.performances.startRejection(spec)?.let { return CourtBoutOutcome.Rejected(it) }
        if (board.category(item.category.id)?.status == CategoryStatus.CONFIRMED) {
            when (val locked = draws.start(item.category.id)) {
                DrawOutcome.Done -> Unit
                is DrawOutcome.Failed -> return CourtBoutOutcome.Failed(locked.diagnostic)
                is DrawOutcome.Rejected -> return CourtBoutOutcome.Rejected(NOT_CONFIRMED)
            }
        }
        return performanceCommand(author, PerformanceCommand.Start(EventId.new(), spec))
    }

    /** Any other operator action on the current performance. */
    fun performanceCommand(author: String, command: PerformanceCommand.FromOperator): CourtBoutOutcome =
        outcomeOf(realtime.performanceOperator(author, command))

    /** Results of the category's completed performances by place (ADR-011 §8, `TEC-013`). */
    fun standings(categoryId: String): List<TechnicalPlace<TechnicalPerformance>> = TechnicalScoring.rank(
        realtime.performances.performances()
            .filter { it.started.categoryId == categoryId }
            .mapNotNull { performance -> performance.result()?.let { performance to it } },
    )

    /** Any other operator action on the current bout. */
    fun command(author: String, command: BoutCommand.FromOperator): CourtBoutOutcome = outcomeOf(realtime.operator(author, command))

    private fun outcomeOf(outcome: BoutOutcome): CourtBoutOutcome =
        when (outcome) {
            is BoutOutcome.Applied -> CourtBoutOutcome.Done(outcome)
            is BoutOutcome.Rejected -> if (outcome.code == BoutRejection.JOURNAL_UNAVAILABLE) {
                CourtBoutOutcome.Failed(outcome.code)
            } else {
                CourtBoutOutcome.Rejected(outcome.code)
            }
        }

    companion object {
        const val NO_COMPETITION = "no_competition"
        const val NOT_CONFIRMED = "not_confirmed"
        const val JUDGE_NOT_CONNECTED = "judge_not_connected"
        const val NO_NEXT_BOUT = "no_next_bout"
        const val NOT_BOUT = "not_bout"

        /** The bout discipline of a category by its application sheet (ADR-011 §3). */
        fun boutDiscipline(category: ApplicationCategory): BoutDiscipline? = when (category.discipline) {
            ApplicationDiscipline.KERUGI -> BoutDiscipline.KERUGI
            ApplicationDiscipline.TANBON -> BoutDiscipline.TANBON
            else -> null
        }
        const val NOT_PERFORMANCE = "not_performance"

        /** «Младшие юноши» and «Юноши» perform 4 technical rounds in Hosinsool and Pair (FHR §4.2.5, `TEC-003`). */
        private val YOUNGER_CLASSES = setOf(AgeClass.YOUNGER, AgeClass.YOUTH)

        /** The technical discipline of a category by its application sheet and weapon (ADR-011 §4). */
        fun technicalDiscipline(category: ApplicationCategory): TechnicalDiscipline? = when (category.discipline) {
            ApplicationDiscipline.KERUGI, ApplicationDiscipline.TANBON -> null
            ApplicationDiscipline.SELF_DEFENSE -> TechnicalDiscipline.HOSINSOOL
            ApplicationDiscipline.STAGED_PAIR -> TechnicalDiscipline.PAIR_FREESTYLE
            ApplicationDiscipline.STAGED_GROUP -> TechnicalDiscipline.GROUP_FREESTYLE
            ApplicationDiscipline.FREE_FORM -> when (category.weapon) {
                Weapon.SWORD -> TechnicalDiscipline.SWORD
                Weapon.STAFF -> TechnicalDiscipline.POLE
                Weapon.DOUBLE_NUNCHAKU -> TechnicalDiscipline.PAIRED_NUNCHAKU
                Weapon.FANS -> TechnicalDiscipline.PAIRED_FANS
                null -> null
            }
        }

        /** The court panel's view of both kinds of sessions: the current one (unless cancelled) and the decided ones. */
        fun progress(hub: RealtimeHub): CourtProgress {
            val bout = hub.bouts.current()
            val performance = hub.performances.current()
            val current = when {
                performance != null && (bout == null || bout.startSequence < performance.startSequence) ->
                    performance.takeIf { it.state != SessionState.CANCELLED }
                        ?.let { ItemRef(it.started.categoryId, it.started.round, it.started.index) }
                else -> bout?.takeIf { it.state != SessionState.CANCELLED }?.let(::refOf)
            }
            val bouts = progress(hub.bouts)
            val performed = hub.performances.performances()
                .filter { it.state == SessionState.COMPLETED }
                .map { ItemRef(it.started.categoryId, it.started.round, it.started.index) }
            // Bout decisions move winners on in the bracket (ADR-010 §6); a performance has no bracket to move along.
            return bouts.copy(current = current, judged = bouts.judged + performed)
        }

        /**
         * The court panel's view of the bouts: the current one (unless cancelled), the decided ones and their results, which
         * move the winners on in the bracket (ADR-010 §6, `BRK-004`, `SES-007`).
         */
        fun progress(bouts: KerugiBoutService): CourtProgress {
            val completed = bouts.bouts().filter { it.state == SessionState.COMPLETED }
            return CourtProgress(
                current = bouts.current()?.takeIf { it.state != SessionState.CANCELLED }?.let(::refOf),
                judged = completed.map(::refOf).toSet(),
                decisions = completed.associate { bout ->
                    val result = checkNotNull(bout.result)
                    refOf(bout) to BoutDecision(result.winner, result.reason, bout.sessionId)
                },
            )
        }

        private fun refOf(bout: KerugiBout) = ItemRef(bout.started.categoryId, bout.started.round, bout.started.index)

        /** «Иванов И. И. (Москва)», as in the organiser's protocols; a pair joins its athletes with a slash. */
        fun label(entry: CategoryEntry): String = entry.athletes.joinToString(" / ") { athlete ->
            val parts = athlete.fullName.trim().split(Regex("\\s+"))
            val name = (listOf(parts.first()) + parts.drop(1).map { "${it.first()}." }).joinToString(" ")
            if (athlete.city.isBlank()) name else "$name (${athlete.city.trim()})"
        }

        fun corner(entry: CategoryEntry) = BoutCorner(
            entryId = entry.id,
            label = label(entry),
            athletes = entry.athletes.map { BoutAthlete(it.fullName.trim(), it.city.trim()) },
        )
    }
}
