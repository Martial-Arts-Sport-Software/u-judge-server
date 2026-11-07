package org.mass.bout

import org.h2.jdbcx.JdbcDataSource
import org.mass.PairingApproval
import org.mass.PairingRequestCommand
import org.mass.PairingRequests
import org.mass.PairingSubmission
import org.mass.RealtimeHub
import org.mass.applications.CompetitionApplications
import org.mass.applications.CompetitionApplicationsService
import org.mass.applications.JdbcCompetitionApplicationsJournal
import org.mass.domain.EventId
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.PeerId
import org.mass.draw.CategoryStatus
import org.mass.draw.CompetitionDrawService
import org.mass.draw.DrawFixtures.category
import org.mass.draw.DrawOutcome
import org.mass.draw.DrawRejection
import org.mass.draw.JdbcCompetitionDrawJournal
import org.mass.persistence.JdbcDomainEventStore
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** «Начать» from the confirmed draw: numbering, the roster of connected devices, the draw lock and a restart (I2a). */
class CourtBoutsTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val dataSource = JdbcDataSource().apply { setURL("jdbc:h2:mem:court-bouts;MODE=PostgreSQL;DB_CLOSE_DELAY=-1") }
    private val pairing = PairingRequests()
    private var seed = 1L

    @Test
    fun `the operator starts numbered bouts with the connected judges and the first start locks the draw`() {
        val (court, draws) = court(importAndDraw = true)
        val credentials = (1..3).map { approve("android-$it", "Судья$it") }
        credentials.take(2).forEach(pairing::connected)

        val bouts = court.courtBouts()
        assertEquals((1..8).toList(), bouts.map(CourtBout::number))
        assertEquals(List(4) { "kerugi-a" } + "kerugi-b" + List(3) { "kerugi-a" }, bouts.map { it.category.id })
        assertEquals(List(5) { true } + List(3) { false }, bouts.map(CourtBout::startable))
        assertEquals(listOf("android-1", "android-2"), court.connectedJudges().map(ConnectedJudge::deviceId))

        assertEquals(
            CourtBoutOutcome.Rejected(CourtBouts.JUDGE_NOT_CONNECTED),
            court.start(bouts[0], listOf("android-1", "android-3"), 2, 1_000, "Оператор"),
        )
        assertEquals(CategoryStatus.CONFIRMED, draws.board().category("kerugi-a")!!.status)
        val started = assertIs<CourtBoutOutcome.Done>(court.start(bouts[0], listOf("android-1", "android-2"), 2, 1_000, "Оператор"))

        val bout = court.bouts.current()!!
        assertEquals(started.outcome.sessionId, bout.sessionId)
        assertEquals(1, bout.started.boutNumber)
        assertEquals(listOf("Судья1", "Судья2"), bout.started.seats.map(BoutSeat::surname))
        assertTrue(bout.started.blue.label.matches(Regex("Спортсмен \\d\\. \\(Город\\)")), bout.started.blue.label)
        assertEquals(CategoryStatus.STARTED, draws.board().category("kerugi-a")!!.status)
        assertEquals(DrawOutcome.Rejected(DrawRejection.STARTED), draws.shuffle("kerugi-a"))

        // A start while the bout runs is refused before it can lock another category.
        assertEquals(
            CourtBoutOutcome.Rejected(BoutRejection.BOUT_IN_PROGRESS),
            court.start(bouts[4], listOf("android-1", "android-2"), 2, 1_000, "Оператор"),
        )
        assertEquals(CategoryStatus.CONFIRMED, draws.board().category("kerugi-b")!!.status)

        val sessionId = bout.sessionId
        court.command("Оператор", BoutCommand.ChangeState(EventId.new(), sessionId, BoutStateAction.START))
        court.command("Оператор", BoutCommand.AwardAction(EventId.new(), sessionId, KerugiOperatorActionType.THROW, KerugiCompetitor.RED, 2))
        assertIs<CourtBoutOutcome.Done>(court.command("Оператор", BoutCommand.CompleteBout(EventId.new(), sessionId)))
        assertEquals(listOf(false) + List(4) { true } + List(3) { false }, court.courtBouts().map(CourtBout::startable))
        assertIs<CourtBoutOutcome.Done>(court.start(court.courtBouts()[1], listOf("android-2", "android-1"), 1, 800, "Оператор"))

        val (restarted, _) = court(importAndDraw = false)
        assertEquals(2, restarted.bouts.current()!!.started.boutNumber)
        assertEquals(listOf("android-2", "android-1"), restarted.bouts.current()!!.started.seats.map(BoutSeat::deviceId))
        assertTrue(restarted.courtBouts()[0].judged)
    }

    @Test
    fun `labels follow the protocol form`() {
        val entry = category(listOf("Тренер"), names = listOf("Иванов-Аов Иван Петрович")).entries.single()
        assertEquals("Иванов-Аов И. П. (Город)", CourtBouts.label(entry))
    }

    private fun court(importAndDraw: Boolean): Pair<CourtBouts, CompetitionDrawService> {
        val store = JdbcDomainEventStore(dataSource)
        val applications = CompetitionApplicationsService(JdbcCompetitionApplicationsJournal(store, peerId), backup = null)
        val draws = CompetitionDrawService(JdbcCompetitionDrawJournal(store, peerId), applications.history, peerId, seeds = { seed++ })
        if (importAndDraw) {
            applications.import(
                CompetitionApplications(
                    competitionName = "Первенство",
                    competitionDate = LocalDate.of(2026, 11, 8),
                    readerVersion = "df-template-v1",
                    files = emptyList(),
                    categories = listOf(
                        category(List(8) { "Тренер $it" }, id = "kerugi-a"),
                        category(List(2) { "Тренер $it" }, id = "kerugi-b", weight = "до 36 кг"),
                    ),
                ),
                replaceConfirmed = false,
            )
            draws.drawAll()
            draws.confirm()
        }
        val hub = RealtimeHub(KerugiBoutService(JdbcBoutEventJournal(store), peerId))
        return CourtBouts(hub, draws, pairing) to draws
    }

    private fun approve(deviceId: String, surname: String): String {
        val pending = assertIs<PairingSubmission.Pending>(pairing.submit(PairingRequestCommand(deviceId, surname, "android")))
        return assertIs<PairingApproval.Accepted>(pairing.approve(pending.request.requestId)).request.reconnectCredential
    }
}
