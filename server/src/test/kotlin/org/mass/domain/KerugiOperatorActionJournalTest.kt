package org.mass.domain

import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KerugiOperatorActionJournalTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val bracketId = BracketId("00000000-0000-4000-8000-000000000002")
    private val sessionId = SessionId("00000000-0000-4000-8000-000000000003")
    private val ownership = BracketOwnership.assign(bracketId, peerId).start(peerId)

    @Test
    fun `applies throw spin and Gamjeom as separately auditable operator actions`() {
        val journal = journal()

        assertIs<KerugiScoreResult.Applied>(journal.apply(action(KerugiOperatorActionType.THROW, KerugiCompetitor.BLUE, 2), eventId(10)))
        assertIs<KerugiScoreResult.Applied>(journal.apply(action(KerugiOperatorActionType.SPIN_BONUS, KerugiCompetitor.BLUE, 1), eventId(11)))
        val gamjeom = assertIs<KerugiScoreResult.Applied>(
            journal.apply(action(KerugiOperatorActionType.GAMJEOM, KerugiCompetitor.BLUE, 1), eventId(12)),
        )

        assertEquals(3, gamjeom.projection.blueScore)
        assertEquals(1, gamjeom.projection.redScore)
        assertEquals(
            listOf(KerugiOperatorActionType.THROW, KerugiOperatorActionType.SPIN_BONUS, KerugiOperatorActionType.GAMJEOM),
            gamjeom.projection.operatorActions.map(KerugiOperatorAction::type),
        )
        assertEquals(gamjeom.projection, KerugiScoreJournal.rebuild(ownership, sessionId, configuration(), journal.events()))
    }

    @Test
    fun `rejects zero value and conflicting retries without changing operator audit`() {
        val journal = journal()
        val accepted = action(KerugiOperatorActionType.THROW, KerugiCompetitor.RED, 2)
        assertIs<KerugiScoreResult.Applied>(journal.apply(accepted, eventId(10)))

        assertIs<KerugiScoreResult.Rejected>(journal.apply(action(KerugiOperatorActionType.SPIN_BONUS, KerugiCompetitor.RED, 0), eventId(11)))
        assertIs<KerugiScoreResult.Rejected>(
            journal.apply(action(KerugiOperatorActionType.SPIN_BONUS, KerugiCompetitor.RED, 1, source = "judge"), eventId(12)),
        )
        assertIs<KerugiScoreResult.Rejected>(journal.apply(action(KerugiOperatorActionType.GAMJEOM, KerugiCompetitor.RED, 1), eventId(10)))

        assertEquals(1, journal.events().size)
        assertEquals(2, journal.projection().redScore)
    }

    @Test
    fun `applies an append-only correction by removing its target from the score projection`() {
        val journal = journal()
        assertIs<KerugiScoreResult.Applied>(journal.apply(action(KerugiOperatorActionType.THROW, KerugiCompetitor.BLUE, 2), eventId(10)))

        val corrected = assertIs<KerugiScoreResult.Applied>(journal.apply(correction(eventId(10)), eventId(11)))

        assertEquals(0, corrected.projection.blueScore)
        assertEquals(listOf(eventId(10)), corrected.projection.corrections.map(KerugiScoreCorrection::targetEventId))
        assertEquals(2, journal.events().size)
        assertEquals(KERUGI_OPERATOR_ACTION_EVENT, journal.events().first().event.type)
        assertEquals(KERUGI_SCORE_CORRECTION_EVENT, journal.events().last().event.type)
        assertEquals(corrected.projection, KerugiScoreJournal.rebuild(ownership, sessionId, configuration(), journal.events()))
    }

    @Test
    fun `rejects corrections for unknown or already corrected targets without changing the journal`() {
        val journal = journal()
        assertIs<KerugiScoreResult.Applied>(journal.apply(action(KerugiOperatorActionType.THROW, KerugiCompetitor.RED, 2), eventId(10)))
        assertIs<KerugiScoreResult.Applied>(journal.apply(correction(eventId(10)), eventId(11)))

        assertIs<KerugiScoreResult.Rejected>(journal.apply(correction(eventId(12)), eventId(12)))
        assertIs<KerugiScoreResult.Rejected>(journal.apply(correction(eventId(10)), eventId(13)))

        assertEquals(2, journal.events().size)
        assertEquals(0, journal.projection().redScore)
    }

    @Test
    fun `warns at ten effective Gamjeom without automatically disqualifying the competitor`() {
        val journal = journal()
        (10..18).forEach { event ->
            assertIs<KerugiScoreResult.Applied>(
                journal.apply(action(KerugiOperatorActionType.GAMJEOM, KerugiCompetitor.BLUE, 1), eventId(event)),
            )
        }
        assertEquals(emptyList(), journal.projection().disqualificationWarnings)

        val tenthGamjeom = assertIs<KerugiScoreResult.Applied>(
            journal.apply(action(KerugiOperatorActionType.GAMJEOM, KerugiCompetitor.BLUE, 1), eventId(19)),
        )

        assertEquals(listOf(KerugiDisqualificationWarning(KerugiCompetitor.BLUE, 10)), tenthGamjeom.projection.disqualificationWarnings)
        assertEquals(10, tenthGamjeom.projection.redScore)
        assertEquals(false, assertIs<KerugiScoreResult.Applied>(
            journal.apply(action(KerugiOperatorActionType.GAMJEOM, KerugiCompetitor.BLUE, 1), eventId(19)),
        ).isNew)

        val corrected = assertIs<KerugiScoreResult.Applied>(journal.apply(correction(eventId(19)), eventId(20)))
        assertEquals(emptyList(), corrected.projection.disqualificationWarnings)
        assertEquals(9, corrected.projection.redScore)
        assertEquals(corrected.projection, KerugiScoreJournal.rebuild(ownership, sessionId, configuration(), journal.events()))
    }

    private fun journal() = KerugiScoreJournal(ownership, sessionId, configuration(), now = { Instant.parse("2026-09-12T12:00:00Z") })

    private fun configuration() = KerugiScoringConfiguration(setOf(judgeId(7), judgeId(8)), 2, Duration.ofSeconds(1))

    private fun action(
        type: KerugiOperatorActionType,
        competitor: KerugiCompetitor,
        points: Int,
        source: String = "operator",
    ) = DomainCommand(
        CompetitionId("00000000-0000-4000-8000-000000000004"), peerId,
        CourtId("00000000-0000-4000-8000-000000000005"), bracketId, sessionId, judgeId(7),
        DeviceId("00000000-0000-4000-8000-000000000006"), EventSource(source), "operator",
        KERUGI_OPERATOR_ACTION_EVENT, Json.encodeToString(KerugiOperatorActionPayload(type, competitor, points)),
    )

    private fun correction(targetEventId: EventId) = DomainCommand(
        CompetitionId("00000000-0000-4000-8000-000000000004"), peerId,
        CourtId("00000000-0000-4000-8000-000000000005"), bracketId, sessionId, judgeId(7),
        DeviceId("00000000-0000-4000-8000-000000000006"), EventSource("operator"), "operator",
        KERUGI_SCORE_CORRECTION_EVENT, Json.encodeToString(KerugiScoreCorrectionPayload(targetEventId.value)),
    )

    private fun judgeId(number: Int) = JudgeId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    private fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
