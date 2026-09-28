package org.mass.draw

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mass.applications.ApplicationCategory
import org.mass.applications.ApplicationDiscipline
import org.mass.applications.ApplicationsHistory
import org.mass.domain.BracketId
import org.mass.domain.BracketOwnership
import org.mass.domain.BracketState
import org.mass.domain.PeerId
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/** Where a category is in the draw workflow; the desktop maps it to the HapkiPRO statuses of ADR-008 §11. */
enum class CategoryStatus {
    NOT_DRAWN,
    PREVIEW,
    CONFIRMED,

    /** A confirmed draw whose entries changed with a later import: «требует новой жеребьёвки». */
    NEEDS_REDRAW,

    /** A Kerugi category with a single entry: «нет поединков». */
    NO_BOUTS,

    /** The first bout started (I2a): the draw is locked. */
    STARTED,
}

/** An unconfirmed draw shown to the operator; only «Подтвердить» journals it. */
data class DrawPreview(val system: DrawSystem, val seed: Long, val draw: CategoryDraw)

/** The journaled draw of a category with its edits applied. */
data class ConfirmedDraw(
    val eventId: String,
    val applicationsEventId: String,
    val system: DrawSystem?,
    val method: DrawMethod,
    val seed: Long,
    val draw: CategoryDraw,
    /** The single local owner of the bracket (`CMP-005`); `IN_PROGRESS` after the start (`CMP-006`). */
    val ownership: BracketOwnership,
)

data class CategoryDrawState(
    val category: ApplicationCategory,
    /** The system of the next draw of the category: the operator's choice, the confirmed one or the default. */
    val system: DrawSystem,
    /** The confirmed draw; for [CategoryStatus.NEEDS_REDRAW] it belongs to entries that are no longer current. */
    val confirmed: ConfirmedDraw?,
    val preview: DrawPreview?,
    val status: CategoryStatus,
    val clashes: List<Clash>,
) {
    /** The draw to show: the preview when there is one, otherwise the valid confirmed draw. */
    val shown: CategoryDraw?
        get() = preview?.draw ?: confirmed?.draw?.takeIf { status != CategoryStatus.NEEDS_REDRAW }

    /** Whether the operator can choose the system: Kerugi with three or more entries. */
    val systemSelectable: Boolean
        get() = category.discipline == ApplicationDiscipline.KERUGI && category.entries.size > 2
}

/** The draw of the current competition; empty before an import. */
data class DrawBoard(val competitionId: String?, val method: DrawMethod, val categories: List<CategoryDrawState>) {
    val previews: Int
        get() = categories.count { it.preview != null }

    fun category(categoryId: String): CategoryDrawState? = categories.firstOrNull { it.category.id == categoryId }
}

/** Why a draw command was refused; nothing changed. */
enum class DrawRejection { NO_COMPETITION, UNKNOWN_CATEGORY, STARTED, NOTHING_TO_CONFIRM, NOT_CONFIRMED, UNKNOWN_ENTRY }

sealed interface DrawOutcome {
    data object Done : DrawOutcome

    data class Rejected(val reason: DrawRejection) : DrawOutcome

    /** The journal write failed; previews and the journal are unchanged. */
    data class Failed(val diagnostic: String) : DrawOutcome
}

/**
 * The operator's draw of the current competition (ADR-007, ADR-008): previews of every category with «Перемешать», the
 * system per category and one method for the competition, swaps, and «Подтвердить», which journals all previews in one
 * transaction. The board is derived from the current import and the journal, so a changed re-import marks the categories
 * whose entries changed and a restart restores confirmed draws and edits.
 */
class CompetitionDrawService(
    private val journal: CompetitionDrawJournal,
    private val applications: StateFlow<ApplicationsHistory>,
    private val peerId: PeerId,
    private val seeds: () -> Long = { secureRandom.nextLong() },
    private val now: () -> Instant = Instant::now,
) {
    private var events = journal.events()
    private var previewCompetitionId: String? = null
    private val previews = mutableMapOf<String, DrawPreview>()
    private val systems = mutableMapOf<String, DrawSystem>()
    private var chosenMethod: DrawMethod? = null

    private val mutableRevision = MutableStateFlow(0L)

    /** Changes with every accepted command; the board is re-read on a new revision or a new applications history. */
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    @Synchronized
    fun board(): DrawBoard {
        val current = applications.value.current ?: return DrawBoard(null, DrawMethod(), emptyList())
        if (previewCompetitionId != current.competitionId) {
            previews.clear()
            systems.clear()
            chosenMethod = null
            previewCompetitionId = current.competitionId
        }
        val journaled = journaled(current.competitionId)
        // The method of the latest confirmation stays the competition's method after a restart.
        val method = chosenMethod
            ?: journaled.values.maxByOrNull { draw -> events.indexOfFirst { it.eventId == draw.eventId } }?.method
            ?: DrawMethod()
        val categories = current.applications.categories.map { category ->
            val keys = category.entries.map { it.drawKey }.toSet()
            val confirmed = journaled[category.id]
            val valid = confirmed != null && confirmed.draw.entryKeys.toSet() == keys
            // A preview drawn from other entries is dropped with the import that replaced them.
            val preview = previews[category.id]?.takeIf { it.draw.entryKeys.toSet() == keys }
            if (preview == null) previews.remove(category.id)
            val started = confirmed?.ownership?.state == BracketState.IN_PROGRESS
            val status = when {
                started -> CategoryStatus.STARTED
                preview != null -> CategoryStatus.PREVIEW
                category.discipline == ApplicationDiscipline.KERUGI && keys.size == 1 -> CategoryStatus.NO_BOUTS
                valid -> CategoryStatus.CONFIRMED
                confirmed != null -> CategoryStatus.NEEDS_REDRAW
                else -> CategoryStatus.NOT_DRAWN
            }
            val system = systems[category.id] ?: preview?.system ?: confirmed?.system ?: Draw.defaultSystem(category)
            val clashes = when {
                preview != null -> Draw.clashes(category, preview.draw, method)
                valid -> Draw.clashes(category, confirmed.draw, confirmed.method)
                else -> emptyList()
            }
            CategoryDrawState(category, system, confirmed, preview, status, clashes)
        }
        return DrawBoard(current.competitionId, method, categories)
    }

    /** The method of the competition; previews are redrawn with their seeds under the new method. */
    @Synchronized
    fun setMethod(method: DrawMethod): DrawOutcome = withBoard { board ->
        chosenMethod = method
        previews.replaceAll { categoryId, preview ->
            preview.copy(draw = Draw.draw(board.category(categoryId)!!.category, preview.system, method, preview.seed))
        }
        DrawOutcome.Done
    }

    /** «Провести жеребьёвку»: previews for every category that has no confirmed draw of its current entries. */
    @Synchronized
    fun drawAll(): DrawOutcome = withBoard { board ->
        board.categories
            .filter { it.status == CategoryStatus.NOT_DRAWN || it.status == CategoryStatus.NEEDS_REDRAW }
            .forEach { previews[it.category.id] = preview(it.category, it.system, board.method, seeds()) }
        DrawOutcome.Done
    }

    /** «Перемешать»: a new seed for the category, confirmed or not, until it starts. */
    @Synchronized
    fun shuffle(categoryId: String): DrawOutcome = withCategory(categoryId) { board, state ->
        previews[categoryId] = preview(state.category, state.system, board.method, seeds())
        DrawOutcome.Done
    }

    /** The system of a category; the preview is redrawn with the same seed, a confirmed draw gets a preview to confirm. */
    @Synchronized
    fun setSystem(categoryId: String, system: DrawSystem): DrawOutcome = withCategory(categoryId) { board, state ->
        systems[categoryId] = system
        val seed = state.preview?.seed ?: state.confirmed?.seed ?: seeds()
        if (state.preview != null || state.status == CategoryStatus.CONFIRMED) {
            previews[categoryId] = preview(state.category, system, board.method, seed)
        }
        DrawOutcome.Done
    }

    /**
     * «Сохранить» of the editing: applies [swaps] of two entries each in order, inside the preview or as journaled
     * `bracket_edited` events of the confirmed draw in one transaction (`BRK-002`).
     */
    @Synchronized
    fun swap(categoryId: String, swaps: List<Pair<String, String>>): DrawOutcome = withCategory(categoryId) { board, state ->
        val preview = state.preview
        val confirmed = state.confirmed?.takeIf { state.status == CategoryStatus.CONFIRMED }
        var draw = preview?.draw ?: confirmed?.draw ?: return@withCategory DrawOutcome.Rejected(DrawRejection.NOT_CONFIRMED)
        val edits = swaps.map { (first, second) ->
            if (first == second || first !in draw.entryKeys || second !in draw.entryKeys) {
                return@withCategory DrawOutcome.Rejected(DrawRejection.UNKNOWN_ENTRY)
            }
            val swapped = draw.swap(first, second)
            DrawEvent.Edited(
                competitionId = checkNotNull(board.competitionId),
                categoryId = categoryId,
                bracketId = confirmed?.ownership?.bracketId?.value.orEmpty(),
                first = first,
                second = second,
                before = draw.positions,
                after = swapped.positions,
            ).also { draw = swapped }
        }
        if (preview != null) {
            previews[categoryId] = preview.copy(draw = draw)
            DrawOutcome.Done
        } else {
            write(edits)
        }
    }

    /** Drops the preview of a category: its confirmed draw, if any, stays. */
    @Synchronized
    fun discard(categoryId: String): DrawOutcome = withCategory(categoryId) { _, _ ->
        previews.remove(categoryId)
        systems.remove(categoryId)
        DrawOutcome.Done
    }

    /** «Подтвердить»: journals every preview in one transaction (ADR-008 §4); a failed write keeps the previews. */
    @Synchronized
    fun confirm(): DrawOutcome = withBoard { board ->
        val confirmable = board.categories.filter { it.preview != null }
        if (confirmable.isEmpty()) return@withBoard DrawOutcome.Rejected(DrawRejection.NOTHING_TO_CONFIRM)
        val applicationsEventId = checkNotNull(applications.value.current).eventId
        val written = confirmable.map { state ->
            val preview = checkNotNull(state.preview)
            DrawEvent.Confirmed(
                competitionId = checkNotNull(board.competitionId),
                categoryId = state.category.id,
                bracketId = state.confirmed?.ownership?.bracketId?.value ?: BracketId.new().value,
                applicationsEventId = applicationsEventId,
                system = preview.system.takeIf { state.systemSelectable },
                method = board.method,
                seed = preview.seed,
                draw = preview.draw,
            )
        }
        write(written).also { outcome ->
            if (outcome == DrawOutcome.Done) {
                confirmable.forEach {
                    previews.remove(it.category.id)
                    systems.remove(it.category.id)
                }
            }
        }
    }

    /**
     * Locks the category when its first bout starts. I2a calls it from the bout start; until then only tests do, to prove
     * the lock (`BRK-002`).
     */
    @Synchronized
    fun start(categoryId: String): DrawOutcome = withCategory(categoryId) { board, state ->
        val confirmed = state.confirmed?.takeIf { state.status == CategoryStatus.CONFIRMED }
            ?: return@withCategory DrawOutcome.Rejected(DrawRejection.NOT_CONFIRMED)
        write(listOf(DrawEvent.Started(checkNotNull(board.competitionId), categoryId, confirmed.ownership.bracketId.value)))
    }

    private fun preview(category: ApplicationCategory, system: DrawSystem, method: DrawMethod, seed: Long) =
        DrawPreview(system, seed, Draw.draw(category, system, method, seed))

    private fun write(written: List<DrawEvent>): DrawOutcome {
        val stored = written.map { StoredDrawEvent(UUID.randomUUID().toString(), now(), it) }
        return try {
            journal.append(stored)
            events = events + stored
            DrawOutcome.Done
        } catch (exception: Exception) {
            DrawOutcome.Failed("Жеребьёвка не сохранена: ${exception.message}")
        }
    }

    private inline fun withBoard(block: (DrawBoard) -> DrawOutcome): DrawOutcome {
        val board = board()
        if (board.competitionId == null) return DrawOutcome.Rejected(DrawRejection.NO_COMPETITION)
        return block(board).also { if (it == DrawOutcome.Done) mutableRevision.value++ }
    }

    private inline fun withCategory(categoryId: String, block: (DrawBoard, CategoryDrawState) -> DrawOutcome): DrawOutcome =
        withBoard { board ->
            val state = board.category(categoryId) ?: return@withBoard DrawOutcome.Rejected(DrawRejection.UNKNOWN_CATEGORY)
            if (state.status == CategoryStatus.STARTED) return@withBoard DrawOutcome.Rejected(DrawRejection.STARTED)
            block(board, state)
        }

    /** The latest confirmed draw of every category of [competitionId] with its edits and start applied. */
    private fun journaled(competitionId: String): Map<String, ConfirmedDraw> {
        val draws = mutableMapOf<String, ConfirmedDraw>()
        events.filter { it.event.competitionId == competitionId }.forEach { stored ->
            when (val event = stored.event) {
                is DrawEvent.Confirmed -> {
                    val previous = draws[event.categoryId]?.ownership
                    draws[event.categoryId] = ConfirmedDraw(
                        eventId = stored.eventId,
                        applicationsEventId = event.applicationsEventId,
                        system = event.system,
                        method = event.method,
                        seed = event.seed,
                        draw = event.draw,
                        ownership = previous ?: BracketOwnership.assign(BracketId(event.bracketId), peerId),
                    )
                }
                is DrawEvent.Edited -> draws[event.categoryId]?.let { draw ->
                    draws[event.categoryId] = draw.copy(draw = draw.draw.copy(positions = event.after))
                }
                is DrawEvent.Started -> draws[event.categoryId]?.let { draw ->
                    draws[event.categoryId] = draw.copy(ownership = draw.ownership.start(peerId))
                }
            }
        }
        return draws
    }

    private companion object {
        val secureRandom = SecureRandom()
    }
}
