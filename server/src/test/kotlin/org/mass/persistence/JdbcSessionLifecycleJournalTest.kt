package org.mass.persistence

import org.h2.jdbcx.JdbcDataSource
import org.mass.domain.BracketId
import org.mass.domain.BracketOwnership
import org.mass.domain.CompetitionId
import org.mass.domain.CourtId
import org.mass.domain.DeviceId
import org.mass.domain.DomainCommand
import org.mass.domain.EventId
import org.mass.domain.EventSource
import org.mass.domain.JudgeId
import org.mass.domain.PeerId
import org.mass.domain.SessionId
import org.mass.domain.SessionLifecycleResult
import org.mass.domain.SessionState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JdbcSessionLifecycleJournalTest {
    private val ownerPeerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val bracketId = BracketId("00000000-0000-4000-8000-000000000002")
    private val sessionId = SessionId("00000000-0000-4000-8000-000000000003")
    private val ownership = BracketOwnership.assign(bracketId, ownerPeerId).start(ownerPeerId)

    @Test
    fun `rebuilds the lifecycle projection from durably stored events after recreation`() {
        val dataSource = dataSource("lifecycle-recovery")
        val firstProcess = journal(dataSource)
        val started: SessionLifecycleResult.Applied = assertIs<SessionLifecycleResult.Applied>(
            firstProcess.apply(command("session_started"), eventId(4)),
        )
        assertIs<SessionLifecycleResult.Applied>(firstProcess.apply(command("session_paused"), eventId(5)))

        val restartedProcess = journal(dataSource)

        assertEquals(SessionState.PAUSED, restartedProcess.projection().state)
        assertEquals(started.event, restartedProcess.events().first())
        assertEquals(listOf(started.event.event.eventId, eventId(5)), restartedProcess.events().map { it.event.eventId })
    }

    private fun journal(dataSource: JdbcDataSource) = JdbcSessionLifecycleJournal(
        dataSource = dataSource,
        ownership = ownership,
        sessionId = sessionId,
        now = { Instant.parse("2026-09-07T12:00:00Z") },
    )

    private fun command(type: String) = DomainCommand(
        competitionId = CompetitionId("00000000-0000-4000-8000-000000000005"),
        peerId = ownerPeerId,
        courtId = CourtId("00000000-0000-4000-8000-000000000006"),
        bracketId = bracketId,
        sessionId = sessionId,
        judgeId = JudgeId("00000000-0000-4000-8000-000000000007"),
        deviceId = DeviceId("00000000-0000-4000-8000-000000000008"),
        source = EventSource("operator"),
        author = "operator-1",
        type = type,
        payload = "{}",
    )

    private fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    private fun dataSource(name: String): JdbcDataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }
}
