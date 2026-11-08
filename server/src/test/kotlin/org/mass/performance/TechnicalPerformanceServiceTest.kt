package org.mass.performance

import org.mass.bout.BoutAthlete
import org.mass.bout.BoutCorner
import org.mass.bout.BoutEventJournal
import org.mass.bout.BoutOrigin
import org.mass.bout.BoutOutcome
import org.mass.bout.BoutRejection
import org.mass.bout.JudgeContext
import org.mass.bout.RosterJudge
import org.mass.domain.AggregationFormula
import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.EventId
import org.mass.domain.JudgeId
import org.mass.domain.JudgeVector
import org.mass.domain.PeerId
import org.mass.domain.SessionId
import org.mass.domain.SessionState
import org.mass.domain.TechnicalDiscipline
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Technical performances (ADR-011 §5-§9): `TEC-006`, `TEC-008`-`TEC-010`, `TEC-012`, `TEC-014`. */
class TechnicalPerformanceServiceTest {
    private val journal = BoutEventJournal.InMemory()
    private var clock = Instant.parse("2026-11-08T12:00:00Z")
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val operator = BoutOrigin.Operator("Оператор")
    private var service = newService()

    private fun newService() = TechnicalPerformanceService(journal, peerId, now = { clock })

    private val devices = (1..5).map { "android-$it" }

    /** The normative vector `hosinsool_younger_five_judges`: total 6.1. */
    private val hosinsool = listOf(
        JudgeVector(listOf(8, 7, 9, 6), listOf(7, 8, 8, 9), 3),
        JudgeVector(listOf(8, 8, 8, 8), listOf(8, 8, 8, 8), 0),
        JudgeVector(listOf(9, 9, 8, 9), listOf(9, 8, 9, 9), 0),
        JudgeVector(listOf(5, 6, 5, 6), listOf(6, 6, 5, 6), 0),
        JudgeVector(listOf(7, 8, 7, 8), listOf(8, 7, 8, 7), 0),
    )

    private fun spec(
        judges: List<String> = devices,
        discipline: TechnicalDiscipline = TechnicalDiscipline.HOSINSOOL,
        criteria: Int = 4,
        formula: AggregationFormula = AggregationFormula.TRIMMED_MEAN,
        index: Int = 0,
    ) = PerformanceStartSpec(
        competitionId = CompetitionId("3f6c1d2e-8a4b-4c7d-9e1f-0a2b3c4d5e60"),
        bracketId = BracketId("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c41"),
        categoryId = "self-defense",
        categoryTitle = "Приёмы самообороны · Юноши",
        performanceNumber = 12 + index,
        round = 1,
        index = index,
        entry = BoutCorner("entry-5", "Иванов И. И. (Москва) / Петров П. П. (Москва)", listOf(BoutAthlete("Иванов Иван Иванович", "Москва"))),
        discipline = discipline,
        techniqueCriteria = criteria,
        judges = judges.mapIndexed { number, device -> RosterJudge(device, "Судья${number + 1}") },
        formula = formula,
    )

    private fun start(spec: PerformanceStartSpec = spec(), running: Boolean = true): SessionId {
        val sessionId = assertIs<BoutOutcome.Applied>(service.dispatch(operator, PerformanceCommand.Start(id(), spec))).sessionId
        if (running) assertIs<BoutOutcome.Applied>(service.dispatch(operator, PerformanceCommand.Begin(id(), sessionId)))
        return sessionId
    }

    private fun context(device: String, performance: TechnicalPerformance = service.current()!!) = JudgeContext(
        performance.competitionId, performance.peerId, performance.courtId, performance.bracketId, performance.sessionId,
        JudgeId(performance.seatOf(device)!!.judgeId), device,
    )

    private fun send(
        device: String,
        vector: JudgeVector,
        total: Int = vector.totalTenths,
        discipline: String = service.current()!!.discipline.code,
        eventId: EventId = id(),
        at: Instant = clock,
    ) = PerformanceCommand.SendScore(eventId, 0, at, context(device), discipline, vector, total)

    private fun judge(device: String, command: PerformanceCommand) = service.dispatch(BoutOrigin.Judge(device), command)

    private fun operator(command: PerformanceCommand) = service.dispatch(operator, command)

    private fun id() = EventId(UUID.randomUUID().toString())

    @Test
    fun `five judges send their final vectors and the server totals them by the trimmed mean`() {
        val sessionId = start(running = false)
        assertEquals(SessionState.PREPARED, service.current()!!.state)
        assertEquals(BoutOutcome.Rejected(BoutRejection.SESSION_NOT_STARTED), judge("android-1", send("android-1", hosinsool[0])))
        assertIs<BoutOutcome.Applied>(operator(PerformanceCommand.Begin(id(), sessionId)))

        devices.zip(hosinsool).forEachIndexed { index, (device, vector) ->
            val applied = assertIs<BoutOutcome.Applied>(judge(device, send(device, vector)))
            assertEquals((1..index + 1).toList(), service.current()!!.submittedSeats())
            assertTrue(applied.isNew)
        }
        assertNull(service.current()!!.result(), "no result before completion")
        assertTrue(service.current()!!.missingSeats().isEmpty())

        val completed = assertIs<BoutOutcome.Applied>(operator(PerformanceCommand.Complete(id(), sessionId)))
        val performance = service.current()!!
        assertEquals(SessionState.COMPLETED, performance.state)
        assertEquals(61, performance.result()!!.totalTenths)
        assertEquals(3, performance.result()!!.divisor)
        assertEquals(61, performance.completion!!.totalTenths)
        assertEquals(emptyList(), performance.completion!!.missingSeats)
        assertTrue(completed.changes.isNotEmpty())
        // Raw criteria, not only the sum, are journaled (`TEC-009`).
        assertEquals(hosinsool, performance.sends.values.sortedBy { it.seat }.map { it.vector })
        assertEquals(BoutOutcome.Rejected(BoutRejection.SESSION_CLOSED), judge("android-1", send("android-1", hosinsool[0])))
    }

    @Test
    fun `Send is final - a retry is acknowledged once, a changed retry conflicts and a second Send is refused`() {
        start()
        val first = send("android-1", hosinsool[0])
        val applied = assertIs<BoutOutcome.Applied>(judge("android-1", first))
        val retried = assertIs<BoutOutcome.Applied>(judge("android-1", first))
        assertFalse(retried.isNew)
        assertEquals(applied.sequence, retried.sequence)
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.EVENT_ID_CONFLICT),
            judge("android-1", first.copy(vector = hosinsool[1], declaredTotalTenths = hosinsool[1].totalTenths)),
        )
        assertEquals(
            BoutOutcome.Rejected(PerformanceRejection.SCORE_ALREADY_SENT),
            judge("android-1", send("android-1", hosinsool[1])),
        )
        assertEquals(1, service.current()!!.sends.size)
        assertEquals(hosinsool[0], service.current()!!.sends.getValue(1).vector)

        // A restart rebuilds the Send; the retry still gets its original outcome.
        service = newService()
        assertEquals(hosinsool[0], service.current()!!.sends.getValue(1).vector)
        assertEquals(applied.sequence, assertIs<BoutOutcome.Applied>(judge("android-1", first)).sequence)
    }

    @Test
    fun `the server checks the vector, the local total, the discipline, the seat and the clock`() {
        start()
        assertEquals(
            BoutOutcome.Rejected(PerformanceRejection.TOTAL_MISMATCH),
            judge("android-1", send("android-1", hosinsool[0], total = hosinsool[0].totalTenths + 1)),
        )
        assertEquals(
            BoutOutcome.Rejected(PerformanceRejection.INVALID_SCORE_VECTOR),
            judge("android-1", send("android-1", JudgeVector(List(6) { 5 }, List(4) { 5 }, 0))),
        )
        assertEquals(
            BoutOutcome.Rejected(PerformanceRejection.INVALID_SCORE_VECTOR),
            judge("android-1", send("android-1", JudgeVector(listOf(0, 5, 5, 5), List(4) { 5 }, 0))),
        )
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.DISCIPLINE_MISMATCH),
            judge("android-1", send("android-1", hosinsool[0], discipline = "pair_freestyle")),
        )
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.JUDGE_NOT_ASSIGNED),
            judge("android-2", send("android-1", hosinsool[0])),
        )
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.INVALID_CLIENT_TIMESTAMP),
            judge("android-1", send("android-1", hosinsool[0], at = clock.plusSeconds(30))),
        )
        assertEquals(BoutOutcome.Rejected(BoutRejection.NOT_PERMITTED), service.dispatch(operator, send("android-1", hosinsool[0])))
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.NOT_PERMITTED),
            judge("android-1", PerformanceCommand.Begin(id(), service.current()!!.sessionId)),
        )
        assertTrue(service.current()!!.sends.isEmpty(), "rejected commands change nothing")
    }

    @Test
    fun `a missing seat needs a reason and enough scores, and the result shows the divisor`() {
        val sessionId = start()
        devices.take(2).zip(hosinsool).forEach { (device, vector) -> judge(device, send(device, vector)) }
        assertEquals(listOf(3, 4, 5), service.current()!!.missingSeats())
        assertEquals(BoutOutcome.Rejected(PerformanceRejection.SCORES_MISSING), operator(PerformanceCommand.Complete(id(), sessionId)))
        assertEquals(
            BoutOutcome.Rejected(PerformanceRejection.NOT_ENOUGH_SCORES),
            operator(PerformanceCommand.Complete(id(), sessionId, "Судья 3 потерял связь")),
        )
        judge("android-3", send("android-3", hosinsool[2]))
        assertIs<BoutOutcome.Applied>(operator(PerformanceCommand.Complete(id(), sessionId, "Судьи 4 и 5 без связи")))
        val completion = service.current()!!.completion!!
        assertEquals(listOf(4, 5), completion.missingSeats)
        assertEquals("Судьи 4 и 5 без связи", completion.reason)
        // Totals 59, 64, 70: the trimmed mean keeps 64 with divisor 1 (`TEC-012`).
        assertEquals(1, completion.divisor)
        assertEquals(64, completion.totalTenths)
    }

    @Test
    fun `arbiter penalties and the two-item bonus are separate, reasoned and withdrawable`() {
        val sessionId = start(spec(discipline = TechnicalDiscipline.PAIRED_FANS, criteria = 6, judges = devices.take(3)))
        val vector = JudgeVector(List(6) { 8 }, List(4) { 8 }, 0)
        devices.take(3).forEach { judge(it, send(it, vector)) }
        assertEquals(BoutOutcome.Rejected(BoutRejection.REASON_REQUIRED), operator(PerformanceCommand.Adjust(id(), sessionId, TechnicalAdjustmentKind.PENALTY, " ")))
        val bonus = PerformanceCommand.Adjust(id(), sessionId, TechnicalAdjustmentKind.BONUS, "Два предмета")
        assertIs<BoutOutcome.Applied>(operator(bonus))
        assertEquals(
            BoutOutcome.Rejected(PerformanceRejection.BONUS_NOT_ALLOWED),
            operator(PerformanceCommand.Adjust(id(), sessionId, TechnicalAdjustmentKind.BONUS, "Ещё раз")),
        )
        val penalty = PerformanceCommand.Adjust(id(), sessionId, TechnicalAdjustmentKind.PENALTY, "Падение оружия")
        assertIs<BoutOutcome.Applied>(operator(penalty))
        assertIs<BoutOutcome.Applied>(operator(PerformanceCommand.Adjust(id(), sessionId, TechnicalAdjustmentKind.PENALTY, "Выход за границу")))
        assertEquals(
            BoutOutcome.Rejected(PerformanceRejection.INVALID_ADJUSTMENT_TARGET),
            operator(PerformanceCommand.WithdrawAdjustment(id(), sessionId, id(), "Ошибка")),
        )
        assertIs<BoutOutcome.Applied>(operator(PerformanceCommand.WithdrawAdjustment(id(), sessionId, penalty.eventId, "Ошибка арбитра")))
        operator(PerformanceCommand.Complete(id(), sessionId))
        val performance = service.current()!!
        assertEquals(80 + 2 - 3, performance.result()!!.totalTenths)
        assertEquals(-1, performance.result()!!.adjustmentTenths)
        val withdrawn = performance.adjustments.single { it.eventId == penalty.eventId }
        assertTrue(withdrawn.withdrawn)
        assertEquals("Оператор", withdrawn.author)
        assertEquals("Падение оружия", withdrawn.reason)
        assertEquals(3, performance.adjustments.size)

        start(spec(index = 1, judges = devices.take(3)))
        assertEquals(
            BoutOutcome.Rejected(PerformanceRejection.BONUS_NOT_ALLOWED),
            operator(PerformanceCommand.Adjust(id(), service.current()!!.sessionId, TechnicalAdjustmentKind.BONUS, "Два предмета")),
        )
    }

    @Test
    fun `roster, formula and criteria are validated and fixed at the start`() {
        assertEquals(BoutOutcome.Rejected(BoutRejection.INVALID_ROSTER), operator(PerformanceCommand.Start(id(), spec(judges = emptyList()))))
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.INVALID_ROSTER),
            operator(PerformanceCommand.Start(id(), spec(judges = (1..8).map { "android-$it" }))),
        )
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.INVALID_ROSTER),
            operator(PerformanceCommand.Start(id(), spec(judges = listOf("android-1", "android-1", "android-2")))),
        )
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.INVALID_SETTINGS),
            operator(PerformanceCommand.Start(id(), spec(judges = devices.take(2)))),
            "the trimmed mean needs three judges",
        )
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.INVALID_SETTINGS),
            operator(PerformanceCommand.Start(id(), spec(discipline = TechnicalDiscipline.SWORD, criteria = 4))),
        )
        val sessionId = start(spec(judges = devices.take(2), formula = AggregationFormula.MEAN))
        val performance = service.current()!!
        assertEquals(AggregationFormula.MEAN, performance.formula)
        assertEquals(2, performance.seats.size)
        assertEquals(BoutOutcome.Rejected(BoutRejection.BOUT_IN_PROGRESS), operator(PerformanceCommand.Start(id(), spec(index = 1))))
        judge("android-1", send("android-1", hosinsool[0]))
        judge("android-2", send("android-2", hosinsool[1]))
        operator(PerformanceCommand.Complete(id(), sessionId))
        assertEquals((59 + 64 + 1) / 2, service.current()!!.result()!!.totalTenths)
        assertEquals(BoutOutcome.Rejected(BoutRejection.BOUT_ALREADY_JUDGED), operator(PerformanceCommand.Start(id(), spec())))

        // A cancelled performance can be started again.
        val next = start(spec(index = 1))
        assertIs<BoutOutcome.Applied>(operator(PerformanceCommand.Cancel(id(), next, "Сбой музыки")))
        assertIs<BoutOutcome.Applied>(operator(PerformanceCommand.Start(id(), spec(index = 1))))
    }
}
