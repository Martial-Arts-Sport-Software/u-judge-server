package org.mass.persistence

import org.h2.jdbcx.JdbcDataSource
import org.mass.domain.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JdbcKerugiTimerJournalTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val bracketId = BracketId("00000000-0000-4000-8000-000000000002")
    private val sessionId = SessionId("00000000-0000-4000-8000-000000000003")
    private val ownership = BracketOwnership.assign(bracketId, peerId).start(peerId)

    @Test
    fun `rebuilds persisted paused timer after recreation and preserves retry projection`() {
        val source = JdbcDataSource().apply { setURL("jdbc:h2:mem:kerugi-timer;MODE=PostgreSQL;DB_CLOSE_DELAY=-1") }
        val first = journal(source)
        val started = assertIs<KerugiTimerResult.Applied>(first.apply(command("kerugi_timer_started"), eventId(10)))
        assertIs<KerugiTimerResult.Applied>(first.apply(command("kerugi_timer_paused"), eventId(11)))
        val restarted = journal(source)
        assertEquals(KerugiTimerState.PAUSED, restarted.projection().state)
        val retry = assertIs<KerugiTimerResult.Applied>(restarted.apply(command("kerugi_timer_started"), eventId(10)))
        assertEquals(false, retry.isNew)
        assertEquals(KerugiTimerState.RUNNING, retry.projection.state)
        assertEquals(started.event, retry.event)
    }

    private fun journal(source: JdbcDataSource) = JdbcKerugiTimerJournal(source, ownership, sessionId, now = { Instant.parse("2026-09-13T12:00:00Z") })
    private fun command(type: String) = DomainCommand(
        CompetitionId("00000000-0000-4000-8000-000000000004"), peerId, CourtId("00000000-0000-4000-8000-000000000005"), bracketId,
        sessionId, JudgeId("00000000-0000-4000-8000-000000000006"), DeviceId("00000000-0000-4000-8000-000000000007"), EventSource("operator"), "operator", type, "{}",
    )
    private fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
