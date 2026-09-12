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
        assertIs<KerugiScoreResult.Rejected>(journal.apply(action(KerugiOperatorActionType.GAMJEOM, KerugiCompetitor.RED, 1), eventId(10)))

        assertEquals(1, journal.events().size)
        assertEquals(2, journal.projection().redScore)
    }

    private fun journal() = KerugiScoreJournal(ownership, sessionId, configuration(), now = { Instant.parse("2026-09-12T12:00:00Z") })

    private fun configuration() = KerugiScoringConfiguration(setOf(judgeId(7), judgeId(8)), 2, Duration.ofSeconds(1))

    private fun action(type: KerugiOperatorActionType, competitor: KerugiCompetitor, points: Int) = DomainCommand(
        CompetitionId("00000000-0000-4000-8000-000000000004"), peerId,
        CourtId("00000000-0000-4000-8000-000000000005"), bracketId, sessionId, judgeId(7),
        DeviceId("00000000-0000-4000-8000-000000000006"), EventSource("operator"), "operator",
        KERUGI_OPERATOR_ACTION_EVENT, Json.encodeToString(KerugiOperatorActionPayload(type, competitor, points)),
    )

    private fun judgeId(number: Int) = JudgeId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    private fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
