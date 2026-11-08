package org.mass.report

import org.mass.PairingApproval
import org.mass.PairingRequestCommand
import org.mass.PairingRequests
import org.mass.PairingSubmission
import org.mass.applications.ApplicationDiscipline
import org.mass.applications.CompetitionApplications
import org.mass.applications.CompetitionApplicationsService
import org.mass.applications.ImportOutcome
import org.mass.bout.BoutCommand
import org.mass.bout.BoutDiscipline
import org.mass.bout.BoutOrigin
import org.mass.bout.BoutOutcome
import org.mass.bout.BoutStateAction
import org.mass.bout.CourtBoutOutcome
import org.mass.bout.CourtBouts
import org.mass.bout.JudgeContext
import org.mass.bout.KerugiBout
import org.mass.bout.TanbonArea
import org.mass.domain.AggregationFormula
import org.mass.domain.EventId
import org.mass.domain.JudgeId
import org.mass.domain.JudgeVector
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.KerugiScoringArea
import org.mass.draw.CompetitionDrawService
import org.mass.draw.CourtItem
import org.mass.draw.DrawOutcome
import org.mass.draw.DrawSystem
import org.mass.draw.DrawFixtures.category
import org.mass.performance.PerformanceCommand
import org.mass.performance.TechnicalAdjustmentKind
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * One small competition judged end to end through the services the desktop uses (I6 acceptance): a Kerugi bracket of
 * four, a Tanbon final and three Hosinsool performances, with counted, unconfirmed, corrected, audit-only and trimmed
 * inputs, so every history status and every kind of session reaches the exports.
 */
class CompetitionScenario(
    private val applications: CompetitionApplicationsService,
    private val draws: CompetitionDrawService,
    private val court: CourtBouts,
    private val pairing: PairingRequests,
) {
    val judges = listOf("android-1", "android-2", "android-3")

    fun importAndDraw() {
        assertIs<ImportOutcome.Imported>(applications.import(APPLICATIONS, replaceConfirmed = false))
        assertEquals(DrawOutcome.Done, draws.drawAll())
        // Four entries default to the round robin (ADR-008 §3); the bracket of the scenario is an elimination.
        assertEquals(DrawOutcome.Done, draws.setSystem("kerugi", DrawSystem.SINGLE_ELIMINATION))
        assertEquals(DrawOutcome.Done, draws.confirm())
    }

    fun connectJudges() {
        judges.forEachIndexed { index, device ->
            val pending = assertIs<PairingSubmission.Pending>(pairing.submit(PairingRequestCommand(device, "Судья${index + 1}", "android")))
            pairing.connected(assertIs<PairingApproval.Accepted>(pairing.approve(pending.request.requestId)).request.reconnectCredential)
        }
    }

    /** Judges every bout and performance of the court panel until nothing is left. */
    fun judgeEverything() {
        var guard = 0
        var performed = 0
        while (true) {
            val next = court.panel().next ?: return
            check(guard++ < 20) { "The court panel does not empty" }
            when (next) {
                is CourtItem.Bout -> bout(CourtBouts.boutDiscipline(next.category) == BoutDiscipline.TANBON)
                is CourtItem.Performance -> performance(++performed)
            }
        }
    }

    private fun bout(tanbon: Boolean) {
        assertIs<CourtBoutOutcome.Done>(court.start(judges, 2, 1_000, OPERATOR))
        val bout = court.bouts.current()!!
        val sessionId = bout.sessionId
        operator(BoutCommand.ChangeState(EventId.new(), sessionId, BoutStateAction.START))
        // The taps lie inside the running round: the round ends right after them, so they are taken in its past.
        Thread.sleep(450)
        val now = Instant.now().minusMillis(420)
        if (tanbon) {
            judge("android-1", BoutCommand.SubmitTanbonScore(EventId.new(), 1, now, context(bout, "android-1"), KerugiCompetitor.BLUE, TanbonArea.HEAD))
            judge("android-2", BoutCommand.SubmitTanbonScore(EventId.new(), 1, now.plusMillis(300), context(bout, "android-2"), KerugiCompetitor.BLUE, TanbonArea.BODY))
            judge("android-3", BoutCommand.SubmitTanbonScore(EventId.new(), 1, now.plusMillis(400), context(bout, "android-3"), null, TanbonArea.CROSS))
        } else {
            // Two judges agree on the blue head (counted as the minimum, BODY), one red tap stays without a quorum.
            judge("android-1", BoutCommand.SubmitScore(EventId.new(), 1, now, context(bout, "android-1"), KerugiCompetitor.BLUE, KerugiScoringArea.HEAD))
            judge("android-2", BoutCommand.SubmitScore(EventId.new(), 1, now.plusMillis(200), context(bout, "android-2"), KerugiCompetitor.BLUE, KerugiScoringArea.BODY))
            judge("android-3", BoutCommand.SubmitScore(EventId.new(), 1, now.plusMillis(100), context(bout, "android-3"), KerugiCompetitor.RED, KerugiScoringArea.HEAD))
            val mistaken = EventId.new()
            operator(BoutCommand.AwardAction(mistaken, sessionId, KerugiOperatorActionType.THROW, KerugiCompetitor.RED, 3))
            operator(BoutCommand.CorrectScore(EventId.new(), sessionId, mistaken))
            operator(BoutCommand.AwardAction(EventId.new(), sessionId, KerugiOperatorActionType.GAMJEOM, KerugiCompetitor.RED, 1))
        }
        operator(BoutCommand.EndPeriod(EventId.new(), sessionId))
        operator(BoutCommand.ChangeState(EventId.new(), sessionId, BoutStateAction.RESUME))
        operator(BoutCommand.EndPeriod(EventId.new(), sessionId))
        operator(BoutCommand.CompleteBout(EventId.new(), sessionId))
        assertEquals(KerugiCompetitor.BLUE, court.bouts.bout(sessionId)!!.result!!.winner)
    }

    /** Three judges send; the trimmed mean keeps the middle total; one penalty is applied and withdrawn. */
    private fun performance(order: Int) {
        assertIs<CourtBoutOutcome.Done>(court.startPerformance(judges, AggregationFormula.TRIMMED_MEAN, OPERATOR))
        val performance = court.performances.current()!!
        val sessionId = performance.sessionId
        performanceOperator(PerformanceCommand.Begin(EventId.new(), sessionId))
        listOf(50 + order, 60 + order, 70 + order).zip(judges).forEach { (total, device) ->
            val current = court.performances.current()!!
            val vector = vector(total)
            val context = JudgeContext(
                current.competitionId, current.peerId, current.courtId, current.bracketId, current.sessionId,
                JudgeId(current.seatOf(device)!!.judgeId), device,
            )
            val command = PerformanceCommand.SendScore(EventId.new(), 1, Instant.now(), context, current.discipline.code, vector, vector.totalTenths)
            assertIs<BoutOutcome.Applied>(court.performances.dispatch(BoutOrigin.Judge(device), command))
        }
        val penalty = EventId.new()
        performanceOperator(PerformanceCommand.Adjust(penalty, sessionId, TechnicalAdjustmentKind.PENALTY, "Выход за пределы"))
        performanceOperator(PerformanceCommand.WithdrawAdjustment(EventId.new(), sessionId, penalty, "Ошибка арбитра, повтор"))
        performanceOperator(PerformanceCommand.Complete(EventId.new(), sessionId))
    }

    private fun operator(command: BoutCommand.FromOperator) =
        court.command(OPERATOR, command).let { assertIs<CourtBoutOutcome.Done>(it, "$command: $it, score ${court.bouts.current()?.score()}") }

    private fun performanceOperator(command: PerformanceCommand.FromOperator) =
        assertIs<CourtBoutOutcome.Done>(court.performanceCommand(OPERATOR, command), command.toString())

    private fun judge(device: String, command: BoutCommand.FromJudge) =
        assertIs<BoutOutcome.Applied>(court.bouts.dispatch(BoutOrigin.Judge(device), command), command.toString())

    private fun context(bout: KerugiBout, device: String) = JudgeContext(
        bout.competitionId, bout.peerId, bout.courtId, bout.bracketId, bout.sessionId, JudgeId(bout.seatOf(device)!!.judgeId), device,
    )

    /** Four technique and four presentation criteria of `0.1..1.0` adding up to [total] tenths. */
    private fun vector(total: Int): JudgeVector {
        val criteria = IntArray(8) { 1 }
        var rest = total - 8
        criteria.indices.forEach { index -> minOf(9, rest).also { criteria[index] += it; rest -= it } }
        return JudgeVector(criteria.take(4), criteria.drop(4), 0)
    }

    companion object {
        const val OPERATOR = "Оператор"

        val APPLICATIONS = CompetitionApplications(
            competitionName = "Первенство города, \"Осень\"",
            competitionDate = LocalDate.of(2026, 11, 8),
            readerVersion = "df-template-v1",
            files = emptyList(),
            categories = listOf(
                category(List(4) { "Тренер $it" }, id = "kerugi", names = List(4) { "Кёруги$it Иван Петрович" }),
                category(List(2) { "Тренер $it" }, id = "tanbon", discipline = ApplicationDiscipline.TANBON, names = listOf("Танбон Анна", "Танбон Олег")),
                category(List(3) { "Тренер $it" }, id = "hosinsool", discipline = ApplicationDiscipline.SELF_DEFENSE, names = List(3) { "Хосинсул$it Пётр" }),
            ),
        )
    }
}
