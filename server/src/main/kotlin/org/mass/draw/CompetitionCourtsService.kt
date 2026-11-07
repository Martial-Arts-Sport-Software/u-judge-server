package org.mass.draw

import kotlinx.coroutines.flow.StateFlow
import org.mass.domain.PeerId

/** The progress of a category in «Все категории» (ADR-008 §11, `CMP-008`); «завершена» comes with the results (I2b). */
enum class CategoryProgress {
    /** «свободна»: the category waits for its draw or its first bout. */
    FREE,

    /** «в процессе»: the first bout started (I2a). */
    IN_PROGRESS,

    /** «нет поединков»: a single Kerugi entry. */
    NO_BOUTS,

    /** «требует новой жеребьёвки»: a later import changed the entries of a confirmed draw. */
    NEEDS_REDRAW,
}

val CategoryDrawState.progress: CategoryProgress
    get() = when (status) {
        CategoryStatus.NO_BOUTS -> CategoryProgress.NO_BOUTS
        CategoryStatus.NEEDS_REDRAW -> CategoryProgress.NEEDS_REDRAW
        CategoryStatus.STARTED -> CategoryProgress.IN_PROGRESS
        CategoryStatus.NOT_DRAWN, CategoryStatus.PREVIEW, CategoryStatus.CONFIRMED -> CategoryProgress.FREE
    }

/** The courts of the current competition with the numbering derived from its confirmed draws; empty before an import. */
data class CourtsBoard(
    val competitionId: String?,
    val draws: DrawBoard,
    val plan: CourtPlan,
    /** The court this desktop judges, counted from 1. */
    val localCourt: Int,
    val numbers: Map<ItemRef, Int>,
    /** Not every category is confirmed: views and protocols say «нумерация предварительная» (ADR-008 §6). */
    val preliminary: Boolean,
    /** A bout started: the numbers of the bouts numbered then no longer change (I2a). */
    val frozen: Boolean,
) {
    fun number(ref: ItemRef): Int? = numbers[ref]

    fun number(categoryId: String, round: Int, index: Int): Int? = numbers[ItemRef(categoryId, round, index)]

    fun courtOf(categoryId: String): Int = plan.courtOf(categoryId) ?: 1

    /** The categories of [court] in the operator's order. */
    fun categoriesOf(court: Int): List<CategoryDrawState> =
        plan.courts.getOrNull(court - 1).orEmpty().mapNotNull(draws::category)

    /** Every category, courts in order and the categories of a court in the operator's order. */
    val ordered: List<CategoryDrawState>
        get() = plan.courts.flatten().mapNotNull(draws::category)

    companion object {
        val Empty = CourtsBoard(null, DrawBoard(null, DrawMethod(), emptyList()), CourtPlan.single(emptyList()), 1, emptyMap(), false, false)
    }
}

/**
 * Courts and bout numbers of the current competition (ADR-008 §1, §6): the number of courts, the court of every category
 * and their order on it, and the court this desktop judges. Every change is one event in the draw journal, validated
 * before it is appended; an unchanged setting writes nothing. The numbering is derived from the confirmed draws on every
 * read, so a restart restores it from the journal.
 */
class CompetitionCourtsService(
    private val draws: CompetitionDrawService,
    private val peerId: PeerId,
) {
    /** Changes with every accepted draw or court command; the desktop re-reads the boards on a new revision. */
    val revision: StateFlow<Long>
        get() = draws.revision

    @Synchronized
    fun board(): CourtsBoard {
        val drawBoard = draws.board()
        val competitionId = drawBoard.competitionId ?: return CourtsBoard.Empty
        val journal = draws.journal()
        val events = journal.filter { it.event.competitionId == competitionId }
        val categoryIds = drawBoard.categories.map { it.category.id }
        val plan = planOf(events, categoryIds)
        val localCourt = events.map { it.event }.filterIsInstance<DrawEvent.DesktopCourtSelected>()
            .lastOrNull { it.peerId == peerId.value }?.court?.coerceIn(1, plan.count) ?: 1
        val frozen = frozenNumbers(journal, competitionId, drawBoard)
        val numbers = BoutNumbering.number(
            plan.courts.map { court ->
                court.mapNotNull { id -> drawBoard.category(id)?.numbered?.let { id to it } }
            },
            frozen.orEmpty(),
        )
        val preliminary = drawBoard.categories.any {
            it.status == CategoryStatus.NOT_DRAWN || it.status == CategoryStatus.PREVIEW || it.status == CategoryStatus.NEEDS_REDRAW
        }
        return CourtsBoard(competitionId, drawBoard, plan, localCourt, numbers, preliminary, frozen != null)
    }

    /**
     * The number of courts, 1..10; the categories of removed courts move to the last remaining court. Rejected when that would
     * move a category whose first bout started.
     */
    @Synchronized
    fun setCourtCount(count: Int): DrawOutcome = withBoard { board ->
        if (count !in 1..CourtPlan.MAX_COURTS) return@withBoard DrawOutcome.Rejected(DrawRejection.INVALID_COURT)
        val moved = board.plan.courts.drop(count).flatten()
        if (moved.any { board.draws.category(it)?.status == CategoryStatus.STARTED }) {
            return@withBoard DrawOutcome.Rejected(DrawRejection.STARTED)
        }
        setPlan(board, board.plan.withCount(count))
    }

    /**
     * Puts [categoryId] on [court] at [position] of the court's order (0 is the first). A category whose first bout started
     * keeps its court; its place in the order can still change, its bout numbers are frozen anyway.
     */
    @Synchronized
    fun placeCategory(categoryId: String, court: Int, position: Int): DrawOutcome = withBoard { board ->
        val state = board.draws.category(categoryId) ?: return@withBoard DrawOutcome.Rejected(DrawRejection.UNKNOWN_CATEGORY)
        if (court !in 1..board.plan.count) return@withBoard DrawOutcome.Rejected(DrawRejection.INVALID_COURT)
        if (state.status == CategoryStatus.STARTED && board.courtOf(categoryId) != court) {
            return@withBoard DrawOutcome.Rejected(DrawRejection.STARTED)
        }
        setPlan(board, board.plan.place(categoryId, court, position))
    }

    /** The court this desktop judges; its court panel shows the bouts of that court. */
    @Synchronized
    fun selectLocalCourt(court: Int): DrawOutcome = withBoard { board ->
        if (court !in 1..board.plan.count) return@withBoard DrawOutcome.Rejected(DrawRejection.INVALID_COURT)
        if (court == board.localCourt) return@withBoard DrawOutcome.Done
        draws.record(listOf(DrawEvent.DesktopCourtSelected(checkNotNull(board.competitionId), peerId.value, court)))
    }

    private fun setPlan(board: CourtsBoard, plan: CourtPlan): DrawOutcome =
        if (plan == board.plan) {
            DrawOutcome.Done
        } else {
            draws.record(listOf(DrawEvent.CourtsSet(checkNotNull(board.competitionId), plan)))
        }

    private inline fun withBoard(block: (CourtsBoard) -> DrawOutcome): DrawOutcome {
        val board = board()
        if (board.competitionId == null) return DrawOutcome.Rejected(DrawRejection.NO_COMPETITION)
        return block(board)
    }

    /** The latest plan in [events] for the categories of the current import; one court in import order without a plan. */
    private fun planOf(events: List<StoredDrawEvent>, categoryIds: List<String>): CourtPlan =
        (events.map { it.event }.filterIsInstance<DrawEvent.CourtsSet>().lastOrNull()?.plan ?: CourtPlan.single(emptyList()))
            .resolve(categoryIds)

    /**
     * The numbering at the first start of a bout of the competition (I2a calls [CompetitionDrawService.start]): the plan and
     * the draws journaled up to that event, numbered as then. Null before the first start.
     */
    private fun frozenNumbers(journal: List<StoredDrawEvent>, competitionId: String, board: DrawBoard): Map<ItemRef, Int>? {
        val first = journal.indexOfFirst { it.event is DrawEvent.Started && it.event.competitionId == competitionId }
        if (first < 0) return null
        val prefix = journal.subList(0, first + 1).filter { it.event.competitionId == competitionId }
        val drawsThen = replayDraws(prefix, competitionId, peerId)
        val keys = board.categories.associate { state -> state.category.id to state.category.entries.map { it.drawKey }.toSet() }
        val planThen = planOf(prefix, board.categories.map { it.category.id })
        return BoutNumbering.number(
            planThen.courts.map { court ->
                court.mapNotNull { id -> drawsThen[id]?.draw?.takeIf { it.entryKeys.toSet() == keys[id] }?.let { id to it } }
            },
        )
    }
}
