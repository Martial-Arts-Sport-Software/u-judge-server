package org.mass.domain

import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KerugiScoreJournalTest {
    private val ownerPeerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val bracketId = BracketId("00000000-0000-4000-8000-000000000002")
    private val sessionId = SessionId("00000000-0000-4000-8000-000000000003")
    private val judges = (7..9).map(::judgeId).toSet()
    private val ownership = BracketOwnership.assign(bracketId, ownerPeerId).start(ownerPeerId)

    @Test
    fun `appends raw candidates and rebuilds their deterministic score projection`() {
        val journal = journal()

        journal.apply(command(judgeId(7), KerugiCompetitor.BLUE, KerugiScoringArea.HEAD, 0), eventId(10))
        val second = assertIs<KerugiScoreResult.Applied>(
            journal.apply(command(judgeId(8), KerugiCompetitor.BLUE, KerugiScoringArea.BODY, 500), eventId(11)),
        )

        assertEquals(1, second.projection.blueScore)
        assertEquals(2, journal.events().size)
        assertEquals(
            journal.projection(),
            KerugiScoreJournal.rebuild(ownership, sessionId, configuration(), journal.events()),
        )
    }

    @Test
    fun `retains insufficient quorum in the projection audit`() {
        val journal = journal()

        val result = assertIs<KerugiScoreResult.Applied>(
            journal.apply(command(judgeId(7), KerugiCompetitor.RED, KerugiScoringArea.HEAD, 0), eventId(10)),
        )

        assertEquals(0, result.projection.redScore)
        assertEquals(KerugiWindowDecision.QUORUM_NOT_REACHED, result.projection.audit.single().decision)
    }

    @Test
    fun `rejects foreign judge and conflicting retry without changing the journal`() {
        val journal = journal()
        val command = command(judgeId(7), KerugiCompetitor.BLUE, KerugiScoringArea.BODY, 0)
        assertIs<KerugiScoreResult.Applied>(journal.apply(command, eventId(10)))

        assertIs<KerugiScoreResult.Rejected>(
            journal.apply(command(judgeId(10), KerugiCompetitor.BLUE, KerugiScoringArea.BODY, 100), eventId(11)),
        )
        assertIs<KerugiScoreResult.Rejected>(
            journal.apply(command(judgeId(8), KerugiCompetitor.BLUE, KerugiScoringArea.BODY, 100), eventId(10)),
        )

        assertEquals(1, journal.events().size)
        assertEquals(0, journal.projection().blueScore)
    }

    private fun journal() = KerugiScoreJournal(ownership, sessionId, configuration(), now = { Instant.parse("2026-09-12T12:00:00Z") })

    private fun configuration() = KerugiScoringConfiguration(judges, quorum = 2, coincidenceWindow = Duration.ofSeconds(1))

    private fun command(judgeId: JudgeId, competitor: KerugiCompetitor, area: KerugiScoringArea, offset: Long) = DomainCommand(
        CompetitionId("00000000-0000-4000-8000-000000000004"), ownerPeerId,
        CourtId("00000000-0000-4000-8000-000000000005"), bracketId, sessionId, judgeId,
        DeviceId("00000000-0000-4000-8000-000000000006"), EventSource("judge"), "judge-${judgeId.value}",
        KERUGI_SCORE_CANDIDATE_EVENT,
        Json.encodeToString(KerugiScoreCandidatePayload(competitor, area, Instant.parse("2026-09-12T12:00:00Z").plusMillis(offset).toString())),
    )

    private fun judgeId(number: Int) = JudgeId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    private fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
