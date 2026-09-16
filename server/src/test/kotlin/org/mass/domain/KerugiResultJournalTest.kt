package org.mass.domain

import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KerugiResultJournalTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val bracketId = BracketId("00000000-0000-4000-8000-000000000002")
    private val sessionId = SessionId("00000000-0000-4000-8000-000000000003")
    private val ownership = BracketOwnership.assign(bracketId, peerId).start(peerId)

    @Test
    fun `persists final score and golden round winner decisions in the audit projection`() {
        val finalScore = journal()
        val final = assertIs<KerugiResultJournalResult.Applied>(finalScore.apply(command(KerugiCompetitor.BLUE, KerugiVictoryReason.FINAL_SCORE), eventId(10)))
        assertEquals(KerugiCompetitor.BLUE, final.projection.winner)
        assertEquals(KerugiVictoryReason.FINAL_SCORE, final.projection.reason)
        assertEquals(final.projection, KerugiResultJournal.rebuild(ownership, sessionId, finalScore.events()))

        val goldenRound = journal()
        val golden = assertIs<KerugiResultJournalResult.Applied>(goldenRound.apply(command(KerugiCompetitor.RED, KerugiVictoryReason.GOLDEN_ROUND), eventId(11)))
        assertEquals(KerugiCompetitor.RED, golden.projection.winner)
        assertEquals(KerugiVictoryReason.GOLDEN_ROUND, golden.projection.reason)
    }

    @Test
    fun `rejects second foreign and conflicting result decisions without changing state`() {
        val journal = journal()
        val applied = assertIs<KerugiResultJournalResult.Applied>(journal.apply(command(KerugiCompetitor.BLUE, KerugiVictoryReason.FINAL_SCORE), eventId(20)))
        assertEquals(false, assertIs<KerugiResultJournalResult.Applied>(journal.apply(command(KerugiCompetitor.BLUE, KerugiVictoryReason.FINAL_SCORE), eventId(20))).isNew)
        assertIs<KerugiResultJournalResult.Rejected>(journal.apply(command(KerugiCompetitor.RED, KerugiVictoryReason.GOLDEN_ROUND), eventId(20)))
        assertIs<KerugiResultJournalResult.Rejected>(journal.apply(command(KerugiCompetitor.RED, KerugiVictoryReason.GOLDEN_ROUND), eventId(21)))
        assertIs<KerugiResultJournalResult.Rejected>(journal.apply(command(KerugiCompetitor.RED, KerugiVictoryReason.GOLDEN_ROUND, peerId = PeerId("00000000-0000-4000-8000-000000000009")), eventId(22)))
        assertEquals(applied.projection, journal.projection())
        assertEquals(1, journal.events().size)
    }

    private fun journal() = KerugiResultJournal(ownership, sessionId, now = { Instant.parse("2026-09-14T12:00:00Z") })
    private fun command(winner: KerugiCompetitor, reason: KerugiVictoryReason, peerId: PeerId = this.peerId) = DomainCommand(
        CompetitionId("00000000-0000-4000-8000-000000000004"), peerId, CourtId("00000000-0000-4000-8000-000000000005"), bracketId,
        sessionId, JudgeId("00000000-0000-4000-8000-000000000006"), DeviceId("00000000-0000-4000-8000-000000000007"), EventSource("operator"), "operator",
        KERUGI_RESULT_DECLARED_EVENT, Json.encodeToString(KerugiResultPayload(winner, reason)),
    )
    private fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
