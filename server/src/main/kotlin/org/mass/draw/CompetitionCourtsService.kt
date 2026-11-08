package org.mass.draw

import kotlinx.coroutines.flow.StateFlow
import org.mass.domain.PeerId

/** The progress of a category in «Все категории» (ADR-008 §11, `CMP-008`). */
enum class CategoryProgress {
    /** «свободна»: the category waits for its draw or its first bout. */
    FREE,

    /** «в процессе»: the first bout started (I2a). */
    IN_PROGRESS,

    /** «нет поединков»: a single Kerugi entry. */
    NO_BOUTS,

    /** «требует новой жеребьёвки»: a later import changed the entries of a confirmed draw. */
    NEEDS_REDRAW,

    /** «завершена»: every bout of the category is decided (ADR-010 §6). */
    COMPLETED,
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
    val officials: CompetitionOfficials = CompetitionOfficials(),
    /** The results of the decided bouts by category, with the winners moved on (ADR-010 §6). */
    val results: Map<String, BracketProgress> = emptyMap(),
) {
    fun number(ref: ItemRef): Int? = numbers[ref]

    /** «Все категории»: a started category whose every bout is decided is «завершена». */
    fun progress(state: CategoryDrawState): CategoryProgress =
        if (state.status == CategoryStatus.STARTED && results[state.category.id]?.decided == true) {
            CategoryProgress.COMPLETED
        } else {
            state.progress
        }

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
    /** The bout in progress and the decided bouts (I2a); read on every panel. */
    private val progress: () -> CourtProgress = { CourtProgress.None },
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
        val officials = events.map { it.event }.filterIsInstance<DrawEvent.OfficialsSet>().lastOrNull()?.officials
            ?: CompetitionOfficials()
        val decisions = progress().decisions
        val results = drawBoard.categories.mapNotNull { state ->
            val draw = state.numbered ?: return@mapNotNull null
            val own = decisions.filterKeys { it.categoryId == state.category.id }.mapKeys { (ref, _) -> ref.round to ref.index }
            state.category.id to BracketProgress(draw, own)
        }.toMap()
        return CourtsBoard(competitionId, drawBoard, plan, localCourt, numbers, preliminary, frozen != null, officials, results)
    }

    /** Place, chief judge and chief secretary of the protocols (`competition_officials_set`); unchanged values write nothing. */
    @Synchronized
    fun setOfficials(officials: CompetitionOfficials): DrawOutcome = withBoard { board ->
        val normalised = officials.normalised()
        if (!normalised.valid) return@withBoard DrawOutcome.Rejected(DrawRejection.INVALID_OFFICIALS)
        if (normalised == board.officials) return@withBoard DrawOutcome.Done
        draws.record(listOf(DrawEvent.OfficialsSet(checkNotNull(board.competitionId), normalised)))
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

    /**
     * The court panel of this desktop's court (ADR-008 §9): the seam of I2a's «Начать». The next bout is the operator's pick
     * while it waits on this court, otherwise the lowest number whose participants are known.
     */
    @Synchronized
    fun courtPanel(): CourtPanel = panel(board())

    /**
     * «Следующий бой»: makes [ref], a waiting bout or performance of this desktop's court, the next one instead of the
     * default (`court_next_bout_selected`). Picking the current pick again writes nothing. Selecting does not lock anything.
     */
    @Synchronized
    fun selectNextBout(ref: ItemRef): DrawOutcome = withBoard { board ->
        val panel = panel(board)
        val item = panel.queue.firstOrNull { it.ref == ref } ?: return@withBoard DrawOutcome.Rejected(DrawRejection.UNKNOWN_BOUT)
        if (panel.nextSelected && panel.next?.ref == ref) return@withBoard DrawOutcome.Done
        draws.record(
            listOf(
                DrawEvent.NextBoutSelected(
                    competitionId = checkNotNull(board.competitionId),
                    court = panel.court,
                    categoryId = ref.categoryId,
                    bracketId = item.bracketId.value,
                    round = ref.round,
                    index = ref.index,
                ),
            ),
        )
    }

    private fun panel(board: CourtsBoard): CourtPanel {
        val competitionId = board.competitionId ?: return CourtPanel.Empty
        val court = board.localCourt
        val progress = progress()
        val items = board.categoriesOf(court).flatMap { state -> items(board, state, court) }.sortedBy { it.number }
        val current = items.firstOrNull { it.ref == progress.current }
        val queue = items.filter { it.ref != progress.current && it.ref !in progress.judged }
        val picked = draws.journal().map { it.event }
            .filterIsInstance<DrawEvent.NextBoutSelected>()
            .lastOrNull { it.competitionId == competitionId && it.court == court }
            ?.let { event -> queue.firstOrNull { it.ref == ItemRef(event.categoryId, event.round, event.index) } }
        return CourtPanel(
            court = court,
            current = current,
            next = picked ?: queue.firstOrNull(),
            nextSelected = picked != null,
            queue = queue,
            preliminary = board.preliminary,
        )
    }

    /** The numbered bouts and performances of a category whose participants are known. */
    private fun items(board: CourtsBoard, state: CategoryDrawState, court: Int): List<CourtItem> {
        val draw = state.numbered ?: return emptyList()
        val bracketId = state.confirmed?.ownership?.bracketId ?: return emptyList()
        val entries = state.category.entries.associateBy { it.drawKey }
        val categoryId = state.category.id
        // A winner fills the slot of the next round, so a later bout joins the queue once both sides are known (ADR-010 §6).
        val results = board.results[categoryId] ?: BracketProgress(draw, emptyMap())
        return when (draw.format) {
            DrawFormat.ELIMINATION, DrawFormat.ROUND_ROBIN -> results.bouts.mapNotNull { bout ->
                val blue = results.key(bout.first)?.let(entries::get) ?: return@mapNotNull null
                val red = results.key(bout.second)?.let(entries::get) ?: return@mapNotNull null
                val ref = ItemRef(categoryId, bout.round, bout.index)
                val number = board.number(ref) ?: return@mapNotNull null
                CourtItem.Bout(ref, number, court, state.category, bracketId, blue, red)
            }
            DrawFormat.PERFORMANCE_ORDER -> draw.entryKeys.mapIndexedNotNull { index, key ->
                val ref = ItemRef(categoryId, 1, index)
                val number = board.number(ref) ?: return@mapIndexedNotNull null
                CourtItem.Performance(ref, number, court, state.category, bracketId, entries[key] ?: return@mapIndexedNotNull null)
            }
            DrawFormat.NO_BOUTS -> emptyList()
        }
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
