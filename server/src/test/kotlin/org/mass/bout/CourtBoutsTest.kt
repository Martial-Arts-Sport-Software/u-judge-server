package org.mass.bout

import org.h2.jdbcx.JdbcDataSource
import org.mass.PairingApproval
import org.mass.PairingRequestCommand
import org.mass.PairingRequests
import org.mass.PairingSubmission
import org.mass.RealtimeHub
import org.mass.applications.ApplicationCategory
import org.mass.applications.ApplicationDiscipline
import org.mass.applications.CompetitionApplications
import org.mass.applications.CompetitionApplicationsService
import org.mass.applications.JdbcCompetitionApplicationsJournal
import org.mass.domain.EventId
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.PeerId
import org.mass.draw.CategoryStatus
import org.mass.draw.CompetitionCourtsService
import org.mass.draw.CompetitionDrawService
import org.mass.draw.DrawFixtures.category
import org.mass.draw.DrawOutcome
import org.mass.draw.DrawRejection
import org.mass.draw.JdbcCompetitionDrawJournal
import org.mass.persistence.JdbcDomainEventStore
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Начать» on the court panel (I2a with I4b): the next bout starts with the connected judges, becomes the current one,
 * locks its category's draw (`CMP-006`) and leaves the queue once decided; a restart restores the panel (`SES-003`).
 */
class CourtBoutsTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val dataSource = JdbcDataSource().apply { setURL("jdbc:h2:mem:court-bouts-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1") }
    private val pairing = PairingRequests()
    private var seed = 1L

    private class Court(val bouts: CourtBouts, val draws: CompetitionDrawService, val courts: CompetitionCourtsService)

    @Test
    fun `the court panel starts its next bout with the connected judges, locks the draw and survives a restart`() {
        val court = court(
            category(List(8) { "Тренер $it" }, id = "kerugi-a"),
            category(List(2) { "Тренер $it" }, id = "kerugi-b", weight = "до 36 кг"),
        )
        val credentials = (1..3).map { approve("android-$it", "Судья$it") }
        credentials.take(2).forEach(pairing::connected)

        val initial = court.bouts.panel()
        assertNull(initial.current)
        assertEquals(1, initial.next!!.number)
        assertEquals(listOf("android-1", "android-2"), court.bouts.connectedJudges().map(ConnectedJudge::deviceId))
        assertEquals(
            CourtBoutOutcome.Rejected(CourtBouts.JUDGE_NOT_CONNECTED),
            court.bouts.start(listOf("android-1", "android-3"), 2, 1_000, "Оператор"),
        )
        assertEquals(CategoryStatus.CONFIRMED, court.draws.board().category("kerugi-a")!!.status)

        assertIs<CourtBoutOutcome.Done>(court.bouts.start(listOf("android-1", "android-2"), 2, 1_000, "Оператор"))

        val bout = court.bouts.bouts.current()!!
        val running = court.bouts.panel()
        assertEquals(1, running.current!!.number)
        assertEquals(2, running.next!!.number)
        assertEquals(1, bout.started.boutNumber)
        assertEquals(1, bout.started.courtNumber)
        assertEquals(listOf("Судья1", "Судья2"), bout.started.seats.map(BoutSeat::surname))
        assertTrue(bout.started.blue.label.matches(Regex("Спортсмен \\d\\. \\(Город\\)")), bout.started.blue.label)
        assertEquals(CategoryStatus.STARTED, court.draws.board().category("kerugi-a")!!.status)
        assertEquals(DrawOutcome.Rejected(DrawRejection.STARTED), court.draws.shuffle("kerugi-a"))

        // Another start while the bout runs is refused before it can lock another category.
        val final = running.queue.single { it.category.id == "kerugi-b" }
        assertEquals(DrawOutcome.Done, court.courts.selectNextBout(final.ref))
        assertEquals(
            CourtBoutOutcome.Rejected(BoutRejection.BOUT_IN_PROGRESS),
            court.bouts.start(listOf("android-1", "android-2"), 2, 1_000, "Оператор"),
        )
        assertEquals(CategoryStatus.CONFIRMED, court.draws.board().category("kerugi-b")!!.status)

        val sessionId = bout.sessionId
        court.bouts.command("Оператор", BoutCommand.ChangeState(EventId.new(), sessionId, BoutStateAction.START))
        court.bouts.command("Оператор", BoutCommand.AwardAction(EventId.new(), sessionId, KerugiOperatorActionType.THROW, KerugiCompetitor.RED, 2))
        assertIs<CourtBoutOutcome.Done>(court.bouts.command("Оператор", BoutCommand.CompleteBout(EventId.new(), sessionId)))
        val decided = court.bouts.panel()
        assertEquals(1, decided.current!!.number)
        assertTrue(decided.queue.none { it.number == 1 })
        assertEquals(final.ref, decided.next!!.ref)

        assertIs<CourtBoutOutcome.Done>(court.bouts.start(listOf("android-2", "android-1"), 1, 800, "Оператор"))
        assertEquals(CategoryStatus.STARTED, court.draws.board().category("kerugi-b")!!.status)

        val restarted = courtOf(null)
        val panel = restarted.bouts.panel()
        assertEquals(final.number, panel.current!!.number)
        assertTrue(panel.queue.none { it.number == 1 || it.number == final.number })
        assertEquals(listOf("android-2", "android-1"), restarted.bouts.bouts.current()!!.started.seats.map(BoutSeat::deviceId))
    }

    @Test
    fun `a technical performance cannot be started as a bout`() {
        val court = court(category(List(3) { "Тренер $it" }, id = "forms", discipline = ApplicationDiscipline.FREE_FORM))
        approve("android-1", "Судья1").let(pairing::connected)
        approve("android-2", "Судья2").let(pairing::connected)

        assertEquals(
            CourtBoutOutcome.Rejected(CourtBouts.NOT_KERUGI),
            court.bouts.start(listOf("android-1", "android-2"), 2, 1_000, "Оператор"),
        )
        assertNull(court.bouts.bouts.current())
    }

    @Test
    fun `labels follow the protocol form`() {
        val entry = category(listOf("Тренер"), names = listOf("Иванов-Аов Иван Петрович")).entries.single()
        assertEquals("Иванов-Аов И. П. (Город)", CourtBouts.label(entry))
    }

    /** A court over the shared journal; with [categories] it imports, draws and confirms them first. */
    private fun court(vararg categories: ApplicationCategory): Court = courtOf(categories.toList())

    private fun courtOf(categories: List<ApplicationCategory>?): Court {
        val store = JdbcDomainEventStore(dataSource)
        val applications = CompetitionApplicationsService(JdbcCompetitionApplicationsJournal(store, peerId), backup = null)
        val draws = CompetitionDrawService(JdbcCompetitionDrawJournal(store, peerId), applications.history, peerId, seeds = { seed++ })
        if (categories != null) {
            applications.import(
                CompetitionApplications(
                    competitionName = "Первенство",
                    competitionDate = LocalDate.of(2026, 11, 8),
                    readerVersion = "df-template-v1",
                    files = emptyList(),
                    categories = categories,
                ),
                replaceConfirmed = false,
            )
            draws.drawAll()
            draws.confirm()
        }
        val hub = RealtimeHub(KerugiBoutService(JdbcBoutEventJournal(store), peerId))
        val courts = CompetitionCourtsService(draws, peerId) { CourtBouts.progress(hub.bouts) }
        return Court(CourtBouts(hub, draws, courts, pairing), draws, courts)
    }

    private fun approve(deviceId: String, surname: String): String {
        val pending = assertIs<PairingSubmission.Pending>(pairing.submit(PairingRequestCommand(deviceId, surname, "android")))
        return assertIs<PairingApproval.Accepted>(pairing.approve(pending.request.requestId)).request.reconnectCredential
    }
}
