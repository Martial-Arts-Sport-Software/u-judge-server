package org.mass.draw

import org.h2.jdbcx.JdbcDataSource
import org.mass.applications.ApplicationDiscipline
import org.mass.applications.CompetitionApplications
import org.mass.applications.CompetitionApplicationsService
import org.mass.applications.ImportOutcome
import org.mass.applications.JdbcCompetitionApplicationsJournal
import org.mass.domain.BracketState
import org.mass.domain.PeerId
import org.mass.draw.DrawFixtures.category
import org.mass.persistence.JdbcDomainEventStore
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scenario of I4b (ADR-007, ADR-008): draw, shuffle, system and method, swap, confirm, restart, a changed re-import, the lock
 * after the start and a failed write, over the real JDBC journal.
 */
class CompetitionDrawServiceTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private var nextSeed = 100L

    private val kerugi = category(List(8) { "Тренер ${it % 3}" }, id = "kerugi-33")
    private val small = category(List(4) { "Тренер $it" }, id = "kerugi-36", weight = "до 36 кг")
    private val single = category(listOf("Тренер"), id = "kerugi-40", weight = "до 40 кг")
    private val forms = category(List(5) { "Тренер ${it % 2}" }, id = "forms", discipline = ApplicationDiscipline.FREE_FORM)

    @Test
    fun `operator draws, edits and confirms, and finds the draw after a restart`() {
        val (applications, draws) = services("draw-scenario")
        applications.import(competition(kerugi, small, single, forms), replaceConfirmed = false)

        // Before the draw: defaults by size, the single entry has no bouts.
        val initial = draws.board()
        assertEquals(DrawSystem.SINGLE_ELIMINATION, initial.category("kerugi-33")!!.system)
        assertEquals(DrawSystem.ROUND_ROBIN, initial.category("kerugi-36")!!.system)
        assertEquals(CategoryStatus.NO_BOUTS, initial.category("kerugi-40")!!.status)
        assertEquals(DrawOutcome.Rejected(DrawRejection.NOTHING_TO_CONFIRM), draws.confirm())

        // One method for the competition, previews for every category that needs one.
        assertEquals(DrawOutcome.Done, draws.setMethod(DrawMethod(SeparationCriterion.COACH)))
        assertEquals(DrawOutcome.Done, draws.drawAll())
        val previewed = draws.board()
        assertEquals(3, previewed.previews)
        assertEquals(DrawFormat.ELIMINATION, previewed.category("kerugi-33")!!.shown!!.format)
        assertEquals(DrawFormat.ROUND_ROBIN, previewed.category("kerugi-36")!!.shown!!.format)
        assertEquals(DrawFormat.PERFORMANCE_ORDER, previewed.category("forms")!!.shown!!.format)

        // «Перемешать» draws a new seed; the system change keeps the seed.
        val before = previewed.category("kerugi-33")!!.preview!!
        draws.shuffle("kerugi-33")
        assertNotEquals(before.seed, draws.board().category("kerugi-33")!!.preview!!.seed)
        draws.setSystem("kerugi-36", DrawSystem.SINGLE_ELIMINATION)
        assertEquals(DrawFormat.ELIMINATION, draws.board().category("kerugi-36")!!.shown!!.format)
        assertEquals(DrawSystem.SINGLE_ELIMINATION, draws.board().category("kerugi-36")!!.system)

        // A swap inside the preview is not journaled.
        val preview = draws.board().category("kerugi-33")!!.preview!!.draw
        val (first, second) = preview.entryKeys.take(2)
        assertEquals(DrawOutcome.Done, draws.swap("kerugi-33", listOf(first to second)))
        assertEquals(preview.swap(first, second), draws.board().category("kerugi-33")!!.shown)
        assertEquals(DrawOutcome.Rejected(DrawRejection.UNKNOWN_ENTRY), draws.swap("kerugi-33", listOf(first to "nobody")))

        // «Подтвердить» journals the three previews at once.
        val shown = draws.board().categories.associate { it.category.id to it.shown }
        assertEquals(DrawOutcome.Done, draws.confirm())
        val confirmed = draws.board()
        assertEquals(0, confirmed.previews)
        assertEquals(
            listOf(CategoryStatus.CONFIRMED, CategoryStatus.CONFIRMED, CategoryStatus.NO_BOUTS, CategoryStatus.CONFIRMED),
            confirmed.categories.map { it.status },
        )
        assertEquals(shown, confirmed.categories.associate { it.category.id to it.shown })
        val ownership = confirmed.category("kerugi-33")!!.confirmed!!.ownership
        assertEquals(peerId, ownership.ownerPeerId)
        assertEquals(BracketState.PREPARED, ownership.state)

        // Swaps of a confirmed draw are journaled edits, saved together; a rejected one writes none of them.
        val (third, fourth, fifth) = confirmed.category("kerugi-33")!!.shown!!.entryKeys.takeLast(3)
        assertEquals(DrawOutcome.Rejected(DrawRejection.UNKNOWN_ENTRY), draws.swap("kerugi-33", listOf(third to fourth, third to third)))
        assertEquals(confirmed.category("kerugi-33")!!.shown, draws.board().category("kerugi-33")!!.shown)
        assertEquals(DrawOutcome.Done, draws.swap("kerugi-33", listOf(third to fourth, fourth to fifth)))
        val edited = draws.board()
        assertEquals(
            confirmed.category("kerugi-33")!!.shown!!.swap(third, fourth).swap(fourth, fifth),
            edited.category("kerugi-33")!!.shown,
        )

        // Restart: the same board from the journal, method included; the bracket keeps its ID.
        val (_, restarted) = services("draw-scenario")
        val restored = restarted.board()
        assertEquals(edited.method, restored.method)
        assertEquals(edited.categories.map { it.status }, restored.categories.map { it.status })
        assertEquals(edited.categories.map { it.shown }, restored.categories.map { it.shown })
        assertEquals(ownership.bracketId, restored.category("kerugi-33")!!.confirmed!!.ownership.bracketId)

        // A redraw of a confirmed category before the start keeps its bracket.
        restarted.shuffle("kerugi-33")
        restarted.confirm()
        assertEquals(ownership.bracketId, restarted.board().category("kerugi-33")!!.confirmed!!.ownership.bracketId)
    }

    @Test
    fun `a changed re-import asks for a new draw of the changed categories only`() {
        val (applications, draws) = services("draw-reimport")
        applications.import(competition(kerugi, small), replaceConfirmed = false)
        draws.drawAll()
        draws.confirm()

        val changed = category(List(5) { "Тренер $it" }, id = "kerugi-36", weight = "до 36 кг")
        assertIs<ImportOutcome.Imported>(applications.import(competition(kerugi, changed), replaceConfirmed = true))
        val board = draws.board()
        assertEquals(CategoryStatus.CONFIRMED, board.category("kerugi-33")!!.status)
        assertEquals(CategoryStatus.NEEDS_REDRAW, board.category("kerugi-36")!!.status)
        assertNull(board.category("kerugi-36")!!.shown)

        draws.drawAll()
        assertEquals(1, draws.board().previews)
        draws.confirm()
        assertEquals(CategoryStatus.CONFIRMED, draws.board().category("kerugi-36")!!.status)

        // After a reset nothing can be drawn until the next import.
        applications.clear()
        assertEquals(DrawOutcome.Rejected(DrawRejection.NO_COMPETITION), draws.drawAll())
        assertEquals(emptyList(), draws.board().categories)
    }

    @Test
    fun `once everything is drawn the operator redraws all categories and sets one system for all`() {
        val (applications, draws) = services("draw-again")
        val other = category(List(4) { "Тренер $it" }, id = "kerugi-44", weight = "до 44 кг")
        applications.import(competition(kerugi, small, other, single, forms), replaceConfirmed = false)
        draws.drawAll()
        draws.confirm()
        assertTrue(draws.board().drawn)
        draws.start("kerugi-44")

        // «Провести жеребьёвку заново»: new previews for every confirmed category that has not started.
        val confirmed = draws.board().categories.associate { it.category.id to it.confirmed?.seed }
        assertEquals(DrawOutcome.Done, draws.drawAll())
        val again = draws.board()
        assertEquals(setOf("kerugi-33", "kerugi-36", "forms"), again.categories.filter { it.preview != null }.map { it.category.id }.toSet())
        assertNotEquals(confirmed["kerugi-33"], again.category("kerugi-33")!!.preview!!.seed)
        assertEquals(confirmed["kerugi-33"], again.category("kerugi-33")!!.confirmed!!.seed)

        // «Применить ко всем»: the Kerugi categories with a choice get the system, the started one keeps its draw.
        assertEquals(listOf("kerugi-33", "kerugi-36"), again.systemSelectable.map { it.category.id })
        assertEquals(DrawOutcome.Done, draws.setSystemForAll(DrawSystem.ROUND_ROBIN))
        val roundRobin = draws.board()
        assertEquals(DrawFormat.ROUND_ROBIN, roundRobin.category("kerugi-33")!!.shown!!.format)
        assertEquals(DrawFormat.ROUND_ROBIN, roundRobin.category("kerugi-36")!!.shown!!.format)
        assertEquals(CategoryStatus.STARTED, roundRobin.category("kerugi-44")!!.status)
        assertEquals(DrawOutcome.Done, draws.confirm())
        assertEquals(DrawFormat.ROUND_ROBIN, draws.board().category("kerugi-33")!!.shown!!.format)
    }

    @Test
    fun `a started category rejects a redraw and edits, also after a restart`() {
        val (applications, draws) = services("draw-lock")
        applications.import(competition(kerugi), replaceConfirmed = false)
        assertEquals(DrawOutcome.Rejected(DrawRejection.NOT_CONFIRMED), draws.start("kerugi-33"))
        draws.drawAll()
        draws.confirm()
        assertEquals(DrawOutcome.Done, draws.start("kerugi-33"))

        listOf(services("draw-lock").second, draws).forEach { service ->
            val state = service.board().category("kerugi-33")!!
            assertEquals(CategoryStatus.STARTED, state.status)
            assertEquals(BracketState.IN_PROGRESS, state.confirmed!!.ownership.state)
            val (first, second) = state.shown!!.entryKeys.take(2)
            val rejected = DrawOutcome.Rejected(DrawRejection.STARTED)
            assertEquals(rejected, service.shuffle("kerugi-33"))
            assertEquals(rejected, service.swap("kerugi-33", listOf(first to second)))
            assertEquals(rejected, service.setSystem("kerugi-33", DrawSystem.ROUND_ROBIN))
            assertEquals(rejected, service.start("kerugi-33"))
        }
    }

    @Test
    fun `a failed write keeps the previews and writes nothing`() {
        val applications = CompetitionApplicationsService(
            org.mass.applications.CompetitionApplicationsJournal.InMemory(),
            backup = null,
        )
        applications.import(competition(kerugi, small), replaceConfirmed = false)
        val journal = CompetitionDrawJournal.InMemory()
        val draws = CompetitionDrawService(journal, applications.history, peerId, seeds = { nextSeed++ })
        draws.drawAll()
        val previews = draws.board().categories.map { it.preview }

        journal.failNextAppend = true
        assertIs<DrawOutcome.Failed>(draws.confirm())
        assertEquals(emptyList(), journal.events())
        assertEquals(previews, draws.board().categories.map { it.preview })

        assertEquals(DrawOutcome.Done, draws.confirm())
        assertEquals(2, journal.events().size)
        assertTrue(journal.events().all { it.event is DrawEvent.Confirmed })
    }

    private fun services(name: String): Pair<CompetitionApplicationsService, CompetitionDrawService> {
        val store = JdbcDomainEventStore(dataSource(name))
        val applications = CompetitionApplicationsService(JdbcCompetitionApplicationsJournal(store, peerId), backup = null)
        val draws = CompetitionDrawService(JdbcCompetitionDrawJournal(store, peerId), applications.history, peerId, seeds = { nextSeed++ })
        return applications to draws
    }

    private fun competition(vararg categories: org.mass.applications.ApplicationCategory) = CompetitionApplications(
        competitionName = "Первенство",
        competitionDate = LocalDate.of(2025, 11, 15),
        readerVersion = "df-template-v1",
        files = emptyList(),
        categories = categories.toList(),
    )

    private fun dataSource(name: String) = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }
}
