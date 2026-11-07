package org.mass.draw

import org.h2.jdbcx.JdbcDataSource
import org.mass.applications.ApplicationCategory
import org.mass.applications.ApplicationDiscipline
import org.mass.applications.CompetitionApplications
import org.mass.applications.CompetitionApplicationsJournal
import org.mass.applications.CompetitionApplicationsService
import org.mass.applications.JdbcCompetitionApplicationsJournal
import org.mass.domain.PeerId
import org.mass.draw.DrawFixtures.category
import org.mass.persistence.JdbcDomainEventStore
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Scenario of I4b part 2 (ADR-008 §1, §6): courts, the court of this desktop and one bout numbering across the courts, with
 * duplicates, rejections, a restart, the freeze after the first start and a failed write, over the real JDBC journal.
 */
class CompetitionCourtsServiceTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private var nextSeed = 100L

    private val kerugi = category(List(8) { "Тренер $it" }, id = "kerugi-33")
    private val small = category(List(4) { "Тренер $it" }, id = "kerugi-36", weight = "до 36 кг")
    private val single = category(listOf("Тренер"), id = "kerugi-40", weight = "до 40 кг")
    private val forms = category(List(5) { "Тренер $it" }, id = "forms", discipline = ApplicationDiscipline.FREE_FORM)

    private class Services(
        val applications: CompetitionApplicationsService,
        val draws: CompetitionDrawService,
        val courts: CompetitionCourtsService,
    )

    @Test
    fun `operator splits the categories over two courts and the bouts are numbered round by round`() {
        val services = services("courts-scenario")
        services.applications.import(competition(kerugi, small, single, forms), replaceConfirmed = false)
        val courts = services.courts

        // Before the draw: one court with every category in import order, nothing numbered yet.
        val initial = courts.board()
        assertEquals(CourtPlan.single(listOf("kerugi-33", "kerugi-36", "kerugi-40", "forms")), initial.plan)
        assertEquals(1, initial.localCourt)
        assertEquals(emptyMap(), initial.numbers)
        assertTrue(initial.preliminary)

        // Previews are not numbered; the confirmation numbers every confirmed draw.
        services.draws.drawAll()
        assertEquals(emptyMap(), courts.board().numbers)
        services.draws.confirm()
        val confirmed = courts.board()
        assertFalse(confirmed.preliminary)
        assertEquals(7 + 6 + 5, confirmed.numbers.size)

        // Two courts: «до 36 кг» on court 2, this desktop judges court 2.
        assertEquals(DrawOutcome.Done, courts.setCourtCount(2))
        assertEquals(DrawOutcome.Done, courts.placeCategory("kerugi-36", court = 2, position = 0))
        assertEquals(DrawOutcome.Done, courts.selectLocalCourt(2))
        val split = courts.board()
        assertEquals(listOf(listOf("kerugi-33", "kerugi-40", "forms"), listOf("kerugi-36")), split.plan.courts)
        assertEquals(2, split.localCourt)
        assertEquals(listOf("kerugi-36"), split.categoriesOf(2).map { it.category.id })

        fun numbers(board: CourtsBoard, categoryId: String, round: Int) =
            board.numbers.filterKeys { it.categoryId == categoryId && it.round == round }.values.toList()
        // Round 1 of court 1 (bouts, then performances), round 1 of court 2, then round 2 of both, and so on.
        assertEquals(listOf(1, 2, 3, 4), numbers(split, "kerugi-33", 1))
        assertEquals(listOf(5, 6, 7, 8, 9), numbers(split, "forms", 1))
        assertEquals(listOf(10, 11), numbers(split, "kerugi-36", 1))
        assertEquals(listOf(12, 13), numbers(split, "kerugi-33", 2))
        assertEquals(listOf(14, 15), numbers(split, "kerugi-36", 2))
        assertEquals(listOf(16), numbers(split, "kerugi-33", 3))
        assertEquals(listOf(17, 18), numbers(split, "kerugi-36", 3))

        // The operator's order on a court changes the numbering until the start.
        assertEquals(DrawOutcome.Done, courts.placeCategory("forms", court = 1, position = 0))
        assertEquals(listOf(1, 2, 3, 4, 5), numbers(courts.board(), "forms", 1))
        assertEquals(DrawOutcome.Done, courts.placeCategory("forms", court = 1, position = 2))

        // A repeated command writes nothing; a rejected one changes nothing.
        val journalSize = services.draws.journal().size
        assertEquals(DrawOutcome.Done, courts.setCourtCount(2))
        assertEquals(DrawOutcome.Done, courts.selectLocalCourt(2))
        assertEquals(DrawOutcome.Done, courts.placeCategory("kerugi-36", court = 2, position = 0))
        val invalid = DrawOutcome.Rejected(DrawRejection.INVALID_COURT)
        assertEquals(invalid, courts.setCourtCount(0))
        assertEquals(invalid, courts.setCourtCount(11))
        assertEquals(invalid, courts.placeCategory("kerugi-33", court = 3, position = 0))
        assertEquals(invalid, courts.selectLocalCourt(3))
        assertEquals(DrawOutcome.Rejected(DrawRejection.UNKNOWN_CATEGORY), courts.placeCategory("nobody", court = 1, position = 0))
        assertEquals(journalSize, services.draws.journal().size)
        val unchanged = courts.board()
        assertEquals(Triple(split.plan, split.localCourt, split.numbers), Triple(unchanged.plan, unchanged.localCourt, unchanged.numbers))

        // Restart: the courts, the court of this desktop and the numbers come back from the journal.
        val restarted = services("courts-scenario").courts.board()
        assertEquals(split.plan, restarted.plan)
        assertEquals(split.localCourt, restarted.localCourt)
        assertEquals(split.numbers, restarted.numbers)
    }

    @Test
    fun `the court panel shows the next bout of this desktop's court and the operator picks another one`() {
        val services = services("courts-panel")
        services.applications.import(competition(kerugi, small, forms), replaceConfirmed = false)
        val courts = services.courts
        assertEquals(null, courts.courtPanel().next)
        services.draws.drawAll()
        services.draws.confirm()
        courts.setCourtCount(2)
        courts.placeCategory("kerugi-36", court = 2, position = 0)
        courts.selectLocalCourt(2)

        // Court 2 has the round robin of «до 36 кг»: every bout has known participants, the lowest number is next.
        val panel = courts.courtPanel()
        assertEquals(2, panel.court)
        assertEquals(null, panel.current)
        assertFalse(panel.nextSelected)
        assertEquals(listOf(10, 11, 14, 15, 17, 18), panel.queue.map { it.number })
        val first = assertIs<CourtItem.Bout>(panel.next)
        assertEquals(10, first.number)
        assertEquals("kerugi-36", first.category.id)
        // Blue is the upper entry of the pair, red the lower one.
        val bout = Bouts.of(services.draws.board().category("kerugi-36")!!.shown!!).first()
        assertEquals((bout.first as BoutSide.Entry).key, first.blue.drawKey)
        assertEquals((bout.second as BoutSide.Entry).key, first.red.drawKey)
        assertEquals(services.draws.board().category("kerugi-36")!!.confirmed!!.ownership.bracketId, first.bracketId)

        // The operator picks bout 15; picking it again writes nothing.
        val picked = panel.queue.first { it.number == 15 }.ref
        assertEquals(DrawOutcome.Done, courts.selectNextBout(picked))
        val journalSize = services.draws.journal().size
        assertEquals(DrawOutcome.Done, courts.selectNextBout(picked))
        assertEquals(journalSize, services.draws.journal().size)
        assertEquals(15, courts.courtPanel().next!!.number)
        assertTrue(courts.courtPanel().nextSelected)

        // A bout of another court or one whose participants are not known yet cannot be picked.
        val unknown = DrawOutcome.Rejected(DrawRejection.UNKNOWN_BOUT)
        assertEquals(unknown, courts.selectNextBout(ItemRef("kerugi-33", 1, 0)))
        assertEquals(unknown, courts.selectNextBout(ItemRef("kerugi-36", 9, 0)))
        assertEquals(journalSize, services.draws.journal().size)

        // Restart: the pick is restored.
        assertEquals(picked, services("courts-panel").courts.courtPanel().next!!.ref)

        // Court 1: round 1 of «до 33 кг» and the performances; the later elimination rounds wait for the results (I2b).
        courts.selectLocalCourt(1)
        val courtOne = courts.courtPanel()
        assertEquals((1..9).toList(), courtOne.queue.map { it.number })
        assertEquals(4, courtOne.queue.count { it is CourtItem.Bout })
        assertIs<CourtItem.Performance>(courtOne.queue.last())
        assertEquals(1, courtOne.next!!.number)
        assertEquals(unknown, courts.selectNextBout(ItemRef("kerugi-33", 2, 0)))

        // Selecting does not lock: the category can still be drawn again; the pick stays while its bout exists.
        courts.selectLocalCourt(2)
        assertEquals(DrawOutcome.Done, services.draws.shuffle("kerugi-36"))
        assertEquals(DrawOutcome.Done, services.draws.confirm())
        assertEquals(picked, courts.courtPanel().next!!.ref)

        // The seam of I2a: the next bout's category starts by its ID.
        assertEquals(DrawOutcome.Done, services.draws.start(courts.courtPanel().next!!.ref.categoryId))
        assertEquals(CategoryProgress.IN_PROGRESS, courts.board().draws.category("kerugi-36")!!.progress)
    }

    @Test
    fun `the first start freezes the numbers and keeps a started category on its court`() {
        val services = services("courts-freeze")
        services.applications.import(competition(kerugi, small), replaceConfirmed = false)
        services.draws.drawAll()
        services.draws.confirm()
        val courts = services.courts
        courts.setCourtCount(2)
        courts.placeCategory("kerugi-33", court = 2, position = 0)
        val before = courts.board()
        assertFalse(before.frozen)

        // I2a starts the first bout of «до 33 кг».
        assertEquals(DrawOutcome.Done, services.draws.start("kerugi-33"))
        val started = courts.board()
        assertTrue(started.frozen)
        assertEquals(before.numbers, started.numbers)

        // A started category stays on its court, also when its court would be removed; others still move.
        val locked = DrawOutcome.Rejected(DrawRejection.STARTED)
        assertEquals(locked, courts.placeCategory("kerugi-33", court = 1, position = 0))
        assertEquals(locked, courts.setCourtCount(1))
        assertEquals(DrawOutcome.Done, courts.placeCategory("kerugi-36", court = 2, position = 1))
        // Moving «до 36 кг» after «до 33 кг» would renumber both; after the start every number stays.
        assertEquals(before.numbers, courts.board().numbers)
        assertEquals(before.numbers, services("courts-freeze").courts.board().numbers)

        // A redraw of the other category keeps the numbers of its bouts as long as it has the same bouts.
        services.draws.shuffle("kerugi-36")
        services.draws.confirm()
        assertEquals(before.numbers, courts.board().numbers)
    }

    @Test
    fun `a changed re-import makes the numbering preliminary and drops the bouts of the changed category`() {
        val services = services("courts-reimport")
        services.applications.import(competition(kerugi, small), replaceConfirmed = false)
        services.draws.drawAll()
        services.draws.confirm()
        services.courts.setCourtCount(3)

        val changed = category(List(5) { "Тренер $it" }, id = "kerugi-36", weight = "до 36 кг")
        services.applications.import(competition(kerugi, changed), replaceConfirmed = true)
        val board = services.courts.board()
        assertTrue(board.preliminary)
        assertEquals(CategoryProgress.NEEDS_REDRAW, board.draws.category("kerugi-36")!!.progress)
        assertEquals(CategoryProgress.FREE, board.draws.category("kerugi-33")!!.progress)
        assertTrue(board.numbers.keys.none { it.categoryId == "kerugi-36" })
        assertEquals(3, board.plan.count)

        // After a reset nothing can be set until the next import.
        services.applications.clear()
        assertEquals(DrawOutcome.Rejected(DrawRejection.NO_COMPETITION), services.courts.setCourtCount(2))
        assertEquals(CourtsBoard.Empty, services.courts.board())
    }

    @Test
    fun `a failed write changes nothing`() {
        val applications = CompetitionApplicationsService(CompetitionApplicationsJournal.InMemory(), backup = null)
        applications.import(competition(kerugi, small), replaceConfirmed = false)
        val journal = CompetitionDrawJournal.InMemory()
        val draws = CompetitionDrawService(journal, applications.history, peerId, seeds = { nextSeed++ })
        val courts = CompetitionCourtsService(draws, peerId)
        val before = courts.board()

        journal.failNextAppend = true
        assertIs<DrawOutcome.Failed>(courts.setCourtCount(2))
        assertEquals(before.plan, courts.board().plan)
        assertEquals(emptyList(), journal.events())
        assertEquals(DrawOutcome.Done, courts.setCourtCount(2))
        assertEquals(2, courts.board().plan.count)
    }

    private fun services(name: String): Services {
        val store = JdbcDomainEventStore(dataSource(name))
        val applications = CompetitionApplicationsService(JdbcCompetitionApplicationsJournal(store, peerId), backup = null)
        val draws = CompetitionDrawService(JdbcCompetitionDrawJournal(store, peerId), applications.history, peerId, seeds = { nextSeed++ })
        return Services(applications, draws, CompetitionCourtsService(draws, peerId))
    }

    private fun competition(vararg categories: ApplicationCategory) = CompetitionApplications(
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
