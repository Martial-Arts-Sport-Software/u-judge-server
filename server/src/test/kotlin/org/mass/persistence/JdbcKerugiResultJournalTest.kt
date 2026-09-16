package org.mass.persistence

import kotlinx.serialization.json.Json
import org.h2.jdbcx.JdbcDataSource
import org.mass.domain.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JdbcKerugiResultJournalTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val bracketId = BracketId("00000000-0000-4000-8000-000000000002")
    private val sessionId = SessionId("00000000-0000-4000-8000-000000000003")
    private val ownership = BracketOwnership.assign(bracketId, peerId).start(peerId)

    @Test
    fun `rebuilds persisted golden round result after recreation and preserves retry`() {
        val source = JdbcDataSource().apply { setURL("jdbc:h2:mem:kerugi-result;MODE=PostgreSQL;DB_CLOSE_DELAY=-1") }
        val first = journal(source)
        val applied = assertIs<KerugiResultJournalResult.Applied>(first.apply(command(), eventId(10)))

        val restarted = journal(source)
        assertEquals(applied.projection, restarted.projection())
        val retry = assertIs<KerugiResultJournalResult.Applied>(restarted.apply(command(), eventId(10)))
        assertEquals(false, retry.isNew)
        assertEquals(applied.projection, retry.projection)
    }

    private fun journal(source: JdbcDataSource) = JdbcKerugiResultJournal(source, ownership, sessionId, now = { Instant.parse("2026-09-14T12:00:00Z") })
    private fun command() = DomainCommand(
        CompetitionId("00000000-0000-4000-8000-000000000004"), peerId, CourtId("00000000-0000-4000-8000-000000000005"), bracketId,
        sessionId, JudgeId("00000000-0000-4000-8000-000000000006"), DeviceId("00000000-0000-4000-8000-000000000007"), EventSource("operator"), "operator",
        KERUGI_RESULT_DECLARED_EVENT, Json.encodeToString(KerugiResultPayload(KerugiCompetitor.RED, KerugiVictoryReason.GOLDEN_ROUND)),
    )
    private fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
