package org.mass.bout

import org.mass.domain.EventId
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.SessionId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tanbon on the Kerugi pipeline (ADR-011 §3): `TAN-001`-`TAN-005`. */
class TanbonBoutServiceTest {
    private val fixture = BoutFixture()

    private fun startTanbon(quorum: Int = 2): SessionId =
        fixture.start(fixture.spec(quorum = quorum).copy(discipline = BoutDiscipline.TANBON))

    private fun tanbon(
        device: String,
        competitor: KerugiCompetitor?,
        area: TanbonArea,
        afterMillis: Long = 0,
        eventId: EventId = fixture.id(),
    ) = BoutCommand.SubmitTanbonScore(eventId, 0, fixture.clock.plusMillis(afterMillis), fixture.context(device), competitor, area)

    @Test
    fun `head scores two and body one under the configured quorum and window`() {
        startTanbon()
        assertEquals(BoutDiscipline.TANBON, fixture.service.current()!!.discipline)
        fixture.judge("android-1", tanbon("android-1", KerugiCompetitor.BLUE, TanbonArea.HEAD))
        fixture.judge("android-2", tanbon("android-2", KerugiCompetitor.BLUE, TanbonArea.HEAD, afterMillis = 400))
        assertEquals(2, fixture.service.current()!!.score().blue)

        fixture.judge("android-1", tanbon("android-1", KerugiCompetitor.RED, TanbonArea.BODY, afterMillis = 3_000))
        fixture.judge("android-3", tanbon("android-3", KerugiCompetitor.RED, TanbonArea.BODY, afterMillis = 3_900))
        assertEquals(1, fixture.service.current()!!.score().red)

        // A lone tap and two taps outside the window do not score (`TAN-001`).
        fixture.judge("android-1", tanbon("android-1", KerugiCompetitor.RED, TanbonArea.HEAD, afterMillis = 6_000))
        fixture.judge("android-2", tanbon("android-2", KerugiCompetitor.RED, TanbonArea.HEAD, afterMillis = 7_500))
        assertEquals(1, fixture.service.current()!!.score().red)
    }

    @Test
    fun `CROSS is acknowledged and audited but never scores, cannot be undone and is not reset`() {
        val sessionId = startTanbon(quorum = 1)
        val cross = tanbon("android-1", null, TanbonArea.CROSS)
        val applied = assertIs<BoutOutcome.Applied>(fixture.judge("android-1", cross))
        assertFalse(BoutChange.SCORE in applied.changes)
        fixture.judge("android-2", tanbon("android-2", null, TanbonArea.CROSS, afterMillis = 100))
        val bout = fixture.service.current()!!
        assertEquals(0, bout.score().blue)
        assertEquals(0, bout.score().red)
        val entry = bout.history().single { it.eventId == cross.eventId }
        assertEquals(BoutEntryStatus.AUDIT_ONLY, entry.status)
        assertEquals(KerugiBoutEvents.TANBON_SCORE_CANDIDATE, entry.type)
        assertFalse(entry.correctable)
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.INVALID_CORRECTION_TARGET),
            fixture.operator(BoutCommand.CorrectScore(fixture.id(), sessionId, cross.eventId)),
        )
        assertEquals(BoutOutcome.Rejected(BoutRejection.NOTHING_TO_RESET), fixture.operator(BoutCommand.ResetBout(fixture.id(), sessionId, "Ошибка")))

        val head = tanbon("android-1", KerugiCompetitor.BLUE, TanbonArea.HEAD, afterMillis = 500)
        fixture.judge("android-1", head)
        assertEquals(2, fixture.service.current()!!.score().blue)
        assertIs<BoutOutcome.Applied>(fixture.operator(BoutCommand.ResetBout(fixture.id(), sessionId, "Ошибка")))
        assertEquals(0, fixture.service.current()!!.score().blue)
        assertEquals(BoutEntryStatus.AUDIT_ONLY, fixture.service.current()!!.history().single { it.eventId == cross.eventId }.status)
        assertEquals(BoutEntryStatus.RESET, fixture.service.current()!!.history().single { it.eventId == head.eventId }.status)
    }

    @Test
    fun `an event of one discipline cannot be applied to a session of the other`() {
        startTanbon()
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.DISCIPLINE_MISMATCH),
            fixture.judge("android-1", fixture.score("android-1")),
        )
        val sessionId = fixture.service.current()!!.sessionId
        listOf(KerugiOperatorActionType.THROW, KerugiOperatorActionType.SPIN_BONUS, KerugiOperatorActionType.GAMJEOM).forEach { type ->
            assertEquals(
                BoutOutcome.Rejected(BoutRejection.DISCIPLINE_MISMATCH),
                fixture.operator(BoutCommand.AwardAction(fixture.id(), sessionId, type, KerugiCompetitor.RED, 1)),
            )
        }
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.DISCIPLINE_MISMATCH),
            fixture.operator(BoutCommand.ConfirmDisqualification(fixture.id(), sessionId, KerugiCompetitor.RED)),
        )
        fixture.operator(BoutCommand.CancelBout(fixture.id(), sessionId, "Смена дисциплины"))

        fixture.start(fixture.spec(index = 1))
        assertEquals(BoutDiscipline.KERUGI, fixture.service.current()!!.discipline)
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.DISCIPLINE_MISMATCH),
            fixture.judge("android-1", tanbon("android-1", KerugiCompetitor.BLUE, TanbonArea.HEAD)),
        )
    }

    @Test
    fun `a Tanbon bout in prepared rejects taps, a duplicate gets the same outcome and a restart rebuilds the score`() {
        val sessionId = fixture.start(fixture.spec(quorum = 1).copy(discipline = BoutDiscipline.TANBON), running = false)
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.SESSION_NOT_STARTED),
            fixture.judge("android-1", tanbon("android-1", KerugiCompetitor.BLUE, TanbonArea.BODY)),
        )
        fixture.state(sessionId, BoutStateAction.START)
        val body = tanbon("android-1", KerugiCompetitor.BLUE, TanbonArea.BODY)
        val first = assertIs<BoutOutcome.Applied>(fixture.judge("android-1", body))
        val again = assertIs<BoutOutcome.Applied>(fixture.judge("android-1", body))
        assertTrue(first.isNew)
        assertFalse(again.isNew)
        assertEquals(first.sequence, again.sequence)
        assertEquals(
            BoutOutcome.Rejected(BoutRejection.EVENT_ID_CONFLICT),
            fixture.judge("android-1", body.copy(area = TanbonArea.HEAD)),
        )
        fixture.judge("android-2", tanbon("android-2", KerugiCompetitor.RED, TanbonArea.HEAD, afterMillis = 2_000))
        fixture.restart()
        val restored = fixture.service.current()!!
        assertEquals(BoutDiscipline.TANBON, restored.discipline)
        assertEquals(1, restored.score().blue)
        assertEquals(2, restored.score().red)
        fixture.finishRegularTime(sessionId)
        assertIs<BoutOutcome.Applied>(fixture.operator(BoutCommand.CompleteBout(fixture.id(), sessionId)))
        assertEquals(KerugiCompetitor.RED, fixture.service.current()!!.result!!.winner)
        assertEquals(Instant.parse("2026-11-08T10:00:00Z"), fixture.clock)
    }
}
