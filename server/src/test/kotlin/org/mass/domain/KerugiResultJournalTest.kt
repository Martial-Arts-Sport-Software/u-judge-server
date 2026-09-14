package org.mass.domain

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
    fun `records final-score and golden-round victories as immutable audit events`() {
        val finalScore = journal()
        val finalResult = assertIs<KerugiResultJournalResult.Applied>(finalScore.apply(command("BLUE", "FINAL_SCORE"), eventId(10)))
        assertEquals(KerugiCompetitor.BLUE, finalResult.projection.winner)
        assertEquals(KerugiVictoryReason.FINAL_SCORE, finalResult.projection.reason)
        assertEquals(finalScore.projection(), KerugiResultJournal.rebuild(ownership, sessionId, finalScore.events()))

        val goldenRound = journal()
        val goldenResult = assertIs<KerugiResultJournalResult.Applied>(goldenRound.apply(command("RED", "GOLDEN_ROUND"), eventId(11)))
        assertEquals(KerugiCompetitor.RED, goldenResult.projection.winner)
        assertEquals(KerugiVictoryReason.GOLDEN_ROUND, goldenResult.projection.reason)
    }

    @Test
    fun `rejects a second foreign malformed or conflicting result without changing projection`() {
        val journal = journal()
        val applied = assertIs<KerugiResultJournalResult.Applied>(journal.apply(command("BLUE", "FINAL_SCORE"), eventId(10)))
        assertEquals(false, assertIs<KerugiResultJournalResult.Applied>(journal.apply(command("BLUE", "FINAL_SCORE"), eventId(10))).isNew)
        assertIs<KerugiResultJournalResult.Rejected>(journal.apply(command("RED", "GOLDEN_ROUND"), eventId(10)))
        assertIs<KerugiResultJournalResult.Rejected>(journal.apply(command("RED", "GOLDEN_ROUND"), eventId(11)))
        assertIs<KerugiResultJournalResult.Rejected>(journal.apply(command("BLUE", "UNKNOWN"), eventId(12)))
        assertIs<KerugiResultJournalResult.Rejected>(journal.apply(command("BLUE", "FINAL_SCORE", peerId = PeerId("00000000-0000-4000-8000-000000000009")), eventId(13)))
        assertEquals(applied.projection, journal.projection())
        assertEquals(1, journal.events().size)
    }

    private fun journal() = KerugiResultJournal(ownership, sessionId, now = { Instant.parse("2026-09-14T12:00:00Z") })
    private fun command(winner: String, reason: String, peerId: PeerId = this.peerId) = DomainCommand(
        CompetitionId("00000000-0000-4000-8000-000000000004"), peerId, CourtId("00000000-0000-4000-8000-000000000005"), bracketId,
        sessionId, JudgeId("00000000-0000-4000-8000-000000000006"), DeviceId("00000000-0000-4000-8000-000000000007"), EventSource("operator"), "operator",
        KerugiResultJournal.KERUGI_RESULT_EVENT, "{\"winner\":\"$winner\",\"reason\":\"$reason\"}",
    )
    private fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
