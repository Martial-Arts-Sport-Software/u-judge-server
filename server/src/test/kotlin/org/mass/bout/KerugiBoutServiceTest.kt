package org.mass.bout

import org.h2.jdbcx.JdbcDataSource
import org.mass.domain.EventId
import org.mass.domain.KerugiCompetitor.BLUE
import org.mass.domain.KerugiCompetitor.RED
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.KerugiScoringArea.BODY
import org.mass.domain.KerugiScoringArea.HEAD
import org.mass.domain.SessionId
import org.mass.domain.SessionState
import org.mass.persistence.JdbcDomainEventStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KerugiBoutServiceTest {
    @Test
    fun `two judges inside the window score and a lone judge is kept for audit`() {
        val court = BoutFixture()
        court.start()
        val lone = court.score("android-3", RED, BODY)

        court.judge("android-1", court.score("android-1"))
        court.judge("android-2", court.score("android-2", afterMillis = 1_000))
        court.judge("android-3", lone)

        val bout = requireNotNull(court.service.current())
        assertEquals(2, bout.score().blue)
        assertEquals(0, bout.score().red)
        assertEquals(BoutEntryStatus.NO_QUORUM, bout.history().single { it.eventId == lone.eventId }.status)
    }

    @Test
    fun `conflicting candidates resolve to the minimum and windows close after the configured time`() {
        val court = BoutFixture()
        court.start()

        court.judge("android-1", court.score("android-1", area = HEAD))
        court.judge("android-2", court.score("android-2", area = BODY, afterMillis = 300))
        court.judge("android-3", court.score("android-3", competitor = RED, afterMillis = 0))
        court.judge("android-1", court.score("android-1", competitor = RED, afterMillis = 1_001))

        val score = requireNotNull(court.service.current()).score()
        assertEquals(1, score.blue)
        assertEquals(0, score.red)
    }

    @Test
    fun `arrival order does not change the score`() {
        val inOrder = BoutFixture().apply { start() }
        val reversed = BoutFixture().apply { start() }
        listOf("android-1" to 0L, "android-2" to 400L).forEach { (device, millis) ->
            inOrder.judge(device, inOrder.score(device, afterMillis = millis))
        }
        listOf("android-2" to 400L, "android-1" to 0L).forEach { (device, millis) ->
            reversed.judge(device, reversed.score(device, afterMillis = millis))
        }

        assertEquals(inOrder.service.current()!!.score(), reversed.service.current()!!.score())
        assertEquals(2, reversed.service.current()!!.score().blue)
    }

    @Test
    fun `an identical retry returns the original outcome and a changed one conflicts`() {
        val court = BoutFixture()
        court.start()
        val tap = court.score("android-1")

        val first = assertIs<BoutOutcome.Applied>(court.judge("android-1", tap))
        court.advance(5_000)
        val retry = assertIs<BoutOutcome.Applied>(court.judge("android-1", tap))

        assertTrue(first.isNew)
        assertEquals(false, retry.isNew)
        assertEquals(first.sequence, retry.sequence)
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.EVENT_ID_CONFLICT),
            court.judge("android-1", tap.copy(area = BODY)),
        )
        assertEquals(1, court.service.current()!!.events.count { it.event.eventId == tap.eventId })
    }

    @Test
    fun `judge commands outside the seat, session or state are rejected without an event`() {
        val court = BoutFixture()
        val sessionId = court.start(running = false)
        val before = court.journal.events().size

        assertEquals(BoutOutcome.Rejected(BoutRejection.SESSION_NOT_STARTED), court.judge("android-1", court.score("android-1")))
        court.state(sessionId, BoutStateAction.START)
        val running = court.journal.events().size
        val tap = court.score("android-1")
        assertEquals(BoutOutcome.Rejected(BoutRejection.JUDGE_NOT_ASSIGNED), court.judge("android-9", tap))
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.JUDGE_NOT_ASSIGNED),
            court.judge("android-1", tap.copy(context = court.context("android-2").copy(deviceId = "android-1"))),
        )
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.SESSION_NOT_CURRENT),
            court.judge("android-1", tap.copy(context = tap.context.copy(sessionId = SessionId(court.id().value)))),
        )
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.SESSION_NOT_CURRENT),
            court.judge("android-1", tap.copy(context = tap.context.copy(competitionId = org.mass.domain.CompetitionId(court.id().value)))),
        )
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.INVALID_CLIENT_TIMESTAMP),
            court.judge("android-1", court.score("android-1", afterMillis = 5_001)),
        )
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.NOT_PERMITTED),
            court.service.dispatch(BoutOrigin.Judge("android-1"), BoutCommand.ChangeState(court.id(), sessionId, BoutStateAction.PAUSE)),
        )
        assertEquals(BoutOutcome.Rejected(BoutRejection.NOT_PERMITTED), court.operator(tap))
        assertEquals(running, court.journal.events().size)
        assertEquals(before + 1, running)
    }

    @Test
    fun `a tap made before the pause counts and one made during the pause is only audited`() {
        val court = BoutFixture()
        val sessionId = court.start()
        val beforePause = court.score("android-1", afterMillis = 100)
        val secondBeforePause = court.score("android-2", afterMillis = 200)
        court.advance(500)
        court.state(sessionId, BoutStateAction.PAUSE)
        court.advance(300)

        court.judge("android-1", beforePause)
        court.judge("android-2", secondBeforePause)
        val duringPause = court.score("android-3", RED)
        court.judge("android-3", duringPause)

        val bout = requireNotNull(court.service.current())
        assertEquals(SessionState.PAUSED, bout.state)
        assertEquals(2, bout.score().blue)
        assertEquals(BoutEntryStatus.OUTSIDE_RUNNING_PERIOD, bout.history().single { it.eventId == duringPause.eventId }.status)
    }

    @Test
    fun `operator throws, spins and Gamjeom are journaled with the author and Gamjeom scores for the opponent`() {
        val court = BoutFixture()
        val sessionId = court.start()

        court.operator(BoutCommand.AwardAction(court.id(), sessionId, KerugiOperatorActionType.THROW, BLUE, 3))
        court.operator(BoutCommand.AwardAction(court.id(), sessionId, KerugiOperatorActionType.SPIN_BONUS, BLUE, 1))
        court.operator(BoutCommand.AwardAction(court.id(), sessionId, KerugiOperatorActionType.GAMJEOM, BLUE, 1))
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.INVALID_POINTS),
            court.operator(BoutCommand.AwardAction(court.id(), sessionId, KerugiOperatorActionType.THROW, BLUE, 4)),
        )

        val bout = requireNotNull(court.service.current())
        assertEquals(4, bout.score().blue)
        assertEquals(1, bout.score().red)
        assertEquals(1, bout.score().gamjeomBlue)
        assertTrue(bout.history().filter { it.type == KerugiBoutEvents.OPERATOR_ACTION }.all { it.author == "Оператор" })
    }

    @Test
    fun `a correction compensates one input and keeps the original`() {
        val court = BoutFixture()
        val sessionId = court.start()
        val throwId = court.id()
        court.operator(BoutCommand.AwardAction(throwId, sessionId, KerugiOperatorActionType.THROW, RED, 2))

        assertIs<BoutOutcome.Applied>(court.operator(BoutCommand.CorrectScore(court.id(), sessionId, throwId)))
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.INVALID_CORRECTION_TARGET),
            court.operator(BoutCommand.CorrectScore(court.id(), sessionId, throwId)),
        )

        val bout = requireNotNull(court.service.current())
        assertEquals(0, bout.score().red)
        assertEquals(BoutEntryStatus.CORRECTED, bout.history().single { it.eventId == throwId }.status)
    }

    @Test
    fun `the bout reset needs a reason and compensates every effective input`() {
        val court = BoutFixture()
        val sessionId = court.start()
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.NOTHING_TO_RESET),
            court.operator(BoutCommand.ResetBout(court.id(), sessionId, "Ошибка")),
        )
        court.judge("android-1", court.score("android-1"))
        court.judge("android-2", court.score("android-2"))
        court.operator(BoutCommand.AwardAction(court.id(), sessionId, KerugiOperatorActionType.GAMJEOM, RED, 1))

        assertEquals(
            BoutOutcome.Rejected(BoutRejection.REASON_REQUIRED),
            court.operator(BoutCommand.ResetBout(court.id(), sessionId, " ")),
        )
        val reset = assertIs<BoutOutcome.Applied>(court.operator(BoutCommand.ResetBout(court.id(), sessionId, "Неверный старт")))

        val bout = requireNotNull(court.service.current())
        assertEquals(setOf(BoutChange.SCORE), reset.changes)
        assertEquals(BoutScore(0, 0, 0, 0, emptyList(), null), bout.score())
        assertEquals(3, bout.history().count { it.status == BoutEntryStatus.RESET })
    }

    @Test
    fun `ten Gamjeom warn, the operator confirms and the opponent wins`() {
        val court = BoutFixture()
        val sessionId = court.start()
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.DISQUALIFICATION_NOT_PENDING),
            court.operator(BoutCommand.ConfirmDisqualification(court.id(), sessionId, RED)),
        )
        repeat(10) { court.operator(BoutCommand.AwardAction(court.id(), sessionId, KerugiOperatorActionType.GAMJEOM, RED, 1)) }
        assertEquals(RED, court.service.current()!!.score().warnings.single().competitor)

        court.operator(BoutCommand.ConfirmDisqualification(court.id(), sessionId, RED))
        val completed = assertIs<BoutOutcome.Applied>(court.operator(BoutCommand.CompleteBout(court.id(), sessionId)))

        val bout = requireNotNull(court.service.current())
        assertEquals(setOf(BoutChange.STATE, BoutChange.TIMER), completed.changes)
        assertEquals(RED, bout.score().disqualified)
        assertEquals(emptyList(), bout.score().warnings)
        assertEquals(BoutResult(BLUE, KerugiBoutService.REASON_DISQUALIFICATION), bout.result)
    }

    @Test
    fun `the result follows the final score and closes the bout for judges`() {
        val court = BoutFixture()
        val sessionId = court.start()
        assertEquals(BoutOutcome.Rejected(BoutRejection.BOUT_NOT_FINISHED), court.operator(BoutCommand.CompleteBout(court.id(), sessionId)))
        court.operator(BoutCommand.AwardAction(court.id(), sessionId, KerugiOperatorActionType.THROW, RED, 1))
        val late = court.score("android-1")
        court.finishRegularTime(sessionId)

        court.operator(BoutCommand.CompleteBout(court.id(), sessionId))

        val bout = requireNotNull(court.service.current())
        assertEquals(SessionState.COMPLETED, bout.state)
        assertEquals(BoutResult(RED, KerugiBoutService.REASON_FINAL_SCORE), bout.result)
        assertEquals(BoutOutcome.Rejected(BoutRejection.SESSION_CLOSED), court.judge("android-1", late))
        assertEquals(BoutOutcome.Rejected(BoutRejection.BOUT_ALREADY_JUDGED), court.operator(BoutCommand.StartBout(court.id(), court.spec())))
        val next = court.start(court.spec(index = 1))
        assertEquals(next, court.service.current()!!.sessionId)
        assertEquals(BoutOutcome.Rejected(BoutRejection.SESSION_CLOSED), court.judge("android-1", late))
    }

    @Test
    fun `the roster and settings are validated at the start and fixed for the bout`() {
        val court = BoutFixture()
        assertEquals(BoutOutcome.Rejected(BoutRejection.INVALID_ROSTER), court.operator(BoutCommand.StartBout(court.id(), court.spec(listOf("a")))))
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.INVALID_ROSTER),
            court.operator(BoutCommand.StartBout(court.id(), court.spec(listOf("a", "b", "c", "d")))),
        )
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.INVALID_SETTINGS),
            court.operator(BoutCommand.StartBout(court.id(), court.spec(listOf("a", "b"), quorum = 3))),
        )
        court.start(court.spec(listOf("a", "b"), quorum = 2, windowMillis = 800))

        val bout = requireNotNull(court.service.current())
        assertEquals(listOf(1, 2), bout.started.seats.map { it.number })
        assertEquals(800, bout.started.coincidenceWindowMillis)
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.BOUT_IN_PROGRESS),
            court.operator(BoutCommand.StartBout(court.id(), court.spec(listOf("a", "b"), index = 1))),
        )
        assertEquals(BoutOutcome.Rejected(BoutRejection.INVALID_TRANSITION), court.state(bout.sessionId, BoutStateAction.START))
    }

    @Test
    fun `a restart rebuilds the same bout, seats and score from the JDBC journal`() {
        val dataSource = JdbcDataSource().apply { setURL("jdbc:h2:mem:kerugi-bout-restart;MODE=PostgreSQL;DB_CLOSE_DELAY=-1") }
        val court = BoutFixture(JdbcBoutEventJournal(JdbcDomainEventStore(dataSource)))
        val sessionId = court.start()
        val tap = court.score("android-1")
        court.judge("android-1", tap)
        court.judge("android-2", court.score("android-2", afterMillis = 200))
        court.operator(BoutCommand.AwardAction(court.id(), sessionId, KerugiOperatorActionType.GAMJEOM, BLUE, 1))
        val before = requireNotNull(court.service.current())

        court.restart()

        val after = requireNotNull(court.service.current())
        assertEquals(sessionId, after.sessionId)
        assertEquals(before.score(), after.score())
        assertEquals(before.started.seats, after.started.seats)
        // ADR-010 §3: the restored clock stands still and waits for the operator.
        assertEquals(SessionState.PAUSED, after.state)
        assertEquals(court.journal.events().maxOf { it.sequence }, court.service.cursor)
        assertEquals(false, assertIs<BoutOutcome.Applied>(court.judge("android-1", tap)).isNew)
        assertNull(court.service.bout(SessionId(EventId.new().value)))
    }
}
