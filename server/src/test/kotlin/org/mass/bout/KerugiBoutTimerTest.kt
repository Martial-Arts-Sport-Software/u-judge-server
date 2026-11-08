package org.mass.bout

import org.mass.applications.AgeClass
import org.mass.domain.KerugiCompetitor.BLUE
import org.mass.domain.KerugiCompetitor.RED
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.SessionState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bout clock of ADR-010 (`KER-010`-`KER-013`): durations, periods, the server timer, golden round and restore. */
class KerugiBoutTimerTest {
    @Test
    fun `durations follow the age class of FHR 2024 4_1_2`() {
        assertEquals(60_000, BoutTiming.of(AgeClass.YOUNGER).roundMillis)
        assertEquals(90_000, BoutTiming.of(AgeClass.YOUTH).roundMillis)
        assertEquals(120_000, BoutTiming.of(AgeClass.JUNIORS).roundMillis)
        assertEquals(120_000, BoutTiming.of(AgeClass.ADULTS).roundMillis)
        AgeClass.entries.map(BoutTiming::of).forEach {
            assertEquals(60_000, it.breakMillis)
            assertEquals(30_000, it.goldenBreakMillis)
            assertEquals(60_000, it.goldenRoundMillis)
        }

        val court = BoutFixture()
        court.start(court.spec(timing = BoutTiming.of(AgeClass.YOUNGER)), running = false)
        val clock = court.service.current()!!.clock
        assertEquals(BoutTimer(BoutPeriod.ROUND, 1, false, 60_000, 60_000, null), clock.timer())
    }

    @Test
    fun `the server timer ends round 1 at its deadline, starts the break and taps after the time do not count`() {
        val court = BoutFixture()
        val sessionId = court.start()
        val startedAt = court.clock
        val inTime = court.score("android-1", afterMillis = 89_500)
        val inTimeSecond = court.score("android-2", afterMillis = 89_600)
        val late = court.score("android-1", RED, afterMillis = 90_100)
        val lateSecond = court.score("android-2", RED, afterMillis = 90_200)

        court.advance(89_900)
        assertNull(court.tick()?.takeIf { court.service.current()!!.events.last().event.type == KerugiBoutEvents.PERIOD_ENDED })
        court.advance(400)
        val ended = assertIs<BoutOutcome.Applied>(court.tick())
        listOf(inTime, inTimeSecond, late, lateSecond).forEach { court.judge(it.context.deviceId, it) }

        val bout = court.service.current()!!
        assertEquals(setOf(BoutChange.STATE, BoutChange.TIMER), ended.changes)
        assertEquals(startedAt.plusMillis(90_000), bout.events.single { it.event.type == KerugiBoutEvents.PERIOD_ENDED }.event.occurredAt)
        assertEquals(KerugiBoutEvents.SERVER_SOURCE, bout.events.single { it.event.type == KerugiBoutEvents.PERIOD_ENDED }.event.source.value)
        assertEquals(SessionState.PAUSED, bout.state)
        assertEquals(BoutTimer(BoutPeriod.BREAK, 2, false, 60_000, 60_000, startedAt.plusMillis(90_000)), bout.clock.timer())
        assertEquals(2, bout.score().blue)
        assertEquals(0, bout.score().red)
        assertEquals(BoutEntryStatus.OUTSIDE_RUNNING_PERIOD, bout.history().single { it.eventId == late.eventId }.status)
        assertEquals(BoutOutcome.Rejected(BoutRejection.INVALID_TRANSITION), court.state(sessionId, BoutStateAction.PAUSE))

        // The break expires too; «Продолжить» starts round 2 with its full time.
        court.advance(60_000)
        assertEquals(setOf(BoutChange.TIMER), assertIs<BoutOutcome.Applied>(court.tick()).changes)
        assertEquals(0, court.service.current()!!.clock.remainingMillis)
        assertIs<BoutOutcome.Applied>(court.state(sessionId, BoutStateAction.RESUME))
        assertEquals(BoutTimer(BoutPeriod.ROUND, 2, false, 90_000, 90_000, court.clock), court.service.current()!!.clock.timer())
        assertEquals(SessionState.RUNNING, court.service.current()!!.state)
    }

    @Test
    fun `pause, resume and the time correction keep the remaining time, invalid clock commands change nothing`() {
        val court = BoutFixture()
        val sessionId = court.start()
        court.advance(30_000)
        assertEquals(BoutOutcome.Rejected(BoutRejection.INVALID_TRANSITION), court.operator(BoutCommand.AdjustClock(court.id(), sessionId, 10_000)))
        court.state(sessionId, BoutStateAction.PAUSE)
        assertEquals(60_000, court.service.current()!!.clock.remainingMillis)
        court.advance(45_000)
        assertEquals(60_000, court.service.current()!!.clock.remainingAt(court.clock))
        assertNull(court.tick())

        val events = court.journal.events().size
        assertEquals(BoutOutcome.Rejected(BoutRejection.INVALID_CLOCK), court.operator(BoutCommand.AdjustClock(court.id(), sessionId, 0)))
        assertEquals(BoutOutcome.Rejected(BoutRejection.INVALID_CLOCK), court.operator(BoutCommand.AdjustClock(court.id(), sessionId, 90_001)))
        assertEquals(BoutOutcome.Rejected(BoutRejection.INVALID_TRANSITION), court.state(sessionId, BoutStateAction.PAUSE))
        assertEquals(events, court.journal.events().size)

        val adjust = BoutCommand.AdjustClock(court.id(), sessionId, 61_500)
        assertEquals(setOf(BoutChange.TIMER), assertIs<BoutOutcome.Applied>(court.operator(adjust)).changes)
        assertEquals(false, assertIs<BoutOutcome.Applied>(court.operator(adjust)).isNew)
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.EVENT_ID_CONFLICT),
            court.operator(BoutCommand.AdjustClock(adjust.eventId, sessionId, 50_000)),
        )
        court.state(sessionId, BoutStateAction.RESUME)
        court.advance(1_500)
        assertEquals(60_000, court.service.current()!!.clock.remainingAt(court.clock))

        // A repeated «Стоп периода» is the same event, not a second end.
        val stop = BoutCommand.EndPeriod(court.id(), sessionId)
        assertIs<BoutOutcome.Applied>(court.operator(stop))
        court.advance(1_000)
        assertEquals(false, assertIs<BoutOutcome.Applied>(court.operator(stop)).isNew)
        assertEquals(BoutPeriod.BREAK, court.service.current()!!.clock.period)
        assertEquals(1, court.service.current()!!.events.count { it.event.type == KerugiBoutEvents.PERIOD_ENDED })
    }

    @Test
    fun `a tie after round 2 leads through the rest to the golden round, whose first point wins`() {
        val court = BoutFixture()
        val sessionId = court.start()
        court.operator(BoutCommand.AwardAction(court.id(), sessionId, KerugiOperatorActionType.THROW, BLUE, 2))
        court.operator(BoutCommand.AwardAction(court.id(), sessionId, KerugiOperatorActionType.THROW, RED, 2))
        court.endPeriod(sessionId)
        court.state(sessionId, BoutStateAction.RESUME)
        court.endPeriod(sessionId)

        val rest = court.service.current()!!.clock
        assertEquals(BoutTimer(BoutPeriod.BREAK, 3, true, 30_000, 30_000, court.clock), rest.timer())
        assertEquals(BoutOutcome.Rejected(BoutRejection.SCORE_TIED), court.operator(BoutCommand.CompleteBout(court.id(), sessionId)))

        court.advance(10_000)
        court.state(sessionId, BoutStateAction.RESUME)
        assertEquals(BoutTimer(BoutPeriod.ROUND, 3, true, 60_000, 60_000, court.clock), court.service.current()!!.clock.timer())
        assertEquals(BoutOutcome.Rejected(BoutRejection.SCORE_TIED), court.operator(BoutCommand.CompleteBout(court.id(), sessionId)))
        // A lone tap forms no point and decides nothing.
        court.judge("android-2", court.score("android-2", BLUE, afterMillis = 1_000))
        assertEquals(SessionState.RUNNING, court.service.current()!!.state)

        // The judges' quorum is the golden point: the round and the bout end at once (FHR 2024 §4.1.13).
        court.advance(4_000)
        court.judge("android-1", court.score("android-1", RED, afterMillis = 0))
        val point = assertIs<BoutOutcome.Applied>(court.judge("android-3", court.score("android-3", RED, afterMillis = 300)))
        val result = point.followUps.single()
        assertEquals(setOf(BoutChange.SCORE), point.changes)
        assertEquals(setOf(BoutChange.STATE, BoutChange.TIMER), result.changes)
        val bout = court.service.current()!!
        assertEquals(SessionState.COMPLETED, bout.state)
        assertEquals(BoutResult(RED, KerugiBoutService.REASON_GOLDEN_ROUND), bout.result)
        assertEquals(KerugiBoutEvents.SERVER_SOURCE, bout.events.last().event.source.value)
        // The clock stops at the deciding tap's time when it runs ahead of the server clock.
        assertEquals(55_700, bout.clock.remainingMillis)
        assertFalse(bout.clock.running)

        // ADR-010 §4: the golden point is final in the system; a correction of it is refused like any change of a closed bout.
        val decisive = bout.history().last { it.status == BoutEntryStatus.COUNTED }
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.SESSION_CLOSED),
            court.operator(BoutCommand.CorrectScore(court.id(), sessionId, decisive.eventId)),
        )
        assertEquals(BoutResult(RED, KerugiBoutService.REASON_GOLDEN_ROUND), court.service.current()!!.result)
        // A retry of the deciding tap writes nothing more.
        court.restart()
        assertEquals(bout.events.size, court.service.current()!!.events.size)
    }

    @Test
    fun `a throw or a Gamjeom in the golden round decides it, a Gamjeom for the opponent`() {
        fun golden(): Pair<BoutFixture, org.mass.domain.SessionId> {
            val court = BoutFixture()
            val sessionId = court.start()
            court.finishRegularTime(sessionId)
            court.state(sessionId, BoutStateAction.RESUME)
            return court to sessionId
        }

        val (thrown, thrownId) = golden()
        thrown.state(thrownId, BoutStateAction.PAUSE)
        val throwOutcome = assertIs<BoutOutcome.Applied>(
            thrown.operator(BoutCommand.AwardAction(thrown.id(), thrownId, KerugiOperatorActionType.THROW, BLUE, 2)),
        )
        assertEquals(1, throwOutcome.followUps.size)
        assertEquals(BoutResult(BLUE, KerugiBoutService.REASON_GOLDEN_ROUND), thrown.service.current()!!.result)

        val (penalised, penalisedId) = golden()
        penalised.operator(BoutCommand.AwardAction(penalised.id(), penalisedId, KerugiOperatorActionType.GAMJEOM, RED, 1))
        assertEquals(BoutResult(BLUE, KerugiBoutService.REASON_GOLDEN_ROUND), penalised.service.current()!!.result)
        assertEquals(1, penalised.service.current()!!.score().gamjeomRed)
    }

    @Test
    fun `a scoreless golden round is decided by the operator's preference`() {
        val court = BoutFixture()
        val sessionId = court.start()
        court.finishRegularTime(sessionId)
        court.state(sessionId, BoutStateAction.RESUME)
        assertEquals(BoutOutcome.Rejected(BoutRejection.SCORE_TIED), court.operator(BoutCommand.CompleteBout(court.id(), sessionId, BLUE)))
        court.advance(60_000)
        assertIs<BoutOutcome.Applied>(court.tick())
        assertTrue(court.service.current()!!.clock.over)

        assertEquals(BoutOutcome.Rejected(BoutRejection.WINNER_REQUIRED), court.operator(BoutCommand.CompleteBout(court.id(), sessionId)))
        assertIs<BoutOutcome.Applied>(court.operator(BoutCommand.CompleteBout(court.id(), sessionId, winner = BLUE)))
        assertEquals(BoutResult(BLUE, KerugiBoutService.REASON_PREFERENCE), court.service.current()!!.result)
    }

    @Test
    fun `the timer saves a running clock five seconds after the latest event and a restart stops it there`() {
        val court = BoutFixture()
        val sessionId = court.start()
        court.advance(4_000)
        assertNull(court.tick())
        court.advance(1_000)
        assertEquals(setOf(), assertIs<BoutOutcome.Applied>(court.tick()).changes)
        court.advance(2_000)
        court.operator(BoutCommand.AwardAction(court.id(), sessionId, KerugiOperatorActionType.THROW, BLUE, 1))
        court.advance(4_900)
        assertNull(court.tick())
        val saved = court.service.current()!!.lastEventAt

        court.advance(30_000)
        court.restart()
        val restored = court.service.current()!!
        assertEquals(SessionState.PAUSED, restored.state)
        assertEquals(90_000L - 7_000, restored.clock.remainingMillis)
        assertEquals(saved, Instant.parse(restored.events.last().event.payload.substringAfter("\"stoppedAt\":\"").substringBefore('"')))
        court.restart()
        assertEquals(restored.events.size, court.service.current()!!.events.size)
    }

    @Test
    fun `a seated judge reports ready before the start and a judge without a seat cannot`() {
        val court = BoutFixture()
        val sessionId = court.start(running = false)
        val ready = BoutCommand.SignalReady(court.id(), 0, court.clock, court.context("android-2"))

        assertIs<BoutOutcome.Applied>(court.judge("android-2", ready))
        assertEquals(false, assertIs<BoutOutcome.Applied>(court.judge("android-2", ready)).isNew)
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.JUDGE_NOT_ASSIGNED),
            court.judge("android-9", BoutCommand.SignalReady(court.id(), 0, court.clock, court.context("android-2").copy(deviceId = "android-9"))),
        )
        assertEquals(setOf(2), court.service.current()!!.ready)
        assertEquals(SessionState.PREPARED, court.service.bout(sessionId)!!.state)
        court.restart()
        assertEquals(setOf(2), court.service.current()!!.ready)
    }

    @Test
    fun `a break interrupted by a restart ends and the operator starts round 2`() {
        val court = BoutFixture()
        val sessionId = court.start()
        court.endPeriod(sessionId)
        court.advance(20_000)
        court.restart()

        val clock = court.service.current()!!.clock
        assertEquals(BoutTimer(BoutPeriod.BREAK, 2, false, 60_000, 0, null), clock.timer())
        assertIs<BoutOutcome.Applied>(court.state(sessionId, BoutStateAction.RESUME))
        assertEquals(2, court.service.current()!!.clock.round)
        assertEquals(SessionState.RUNNING, court.service.current()!!.state)
    }
}
