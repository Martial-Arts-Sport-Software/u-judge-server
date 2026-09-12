package org.mass.persistence

import kotlinx.serialization.json.Json
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
import org.mass.domain.KERUGI_SCORE_CANDIDATE_EVENT
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiScoreCandidatePayload
import org.mass.domain.KerugiScoreResult
import org.mass.domain.KerugiScoringArea
import org.mass.domain.KerugiScoringConfiguration
import org.mass.domain.PeerId
import org.mass.domain.SessionId
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JdbcKerugiScoreJournalTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val bracketId = BracketId("00000000-0000-4000-8000-000000000002")
    private val sessionId = SessionId("00000000-0000-4000-8000-000000000003")
    private val judges = setOf(judgeId(7), judgeId(8))
    private val ownership = BracketOwnership.assign(bracketId, peerId).start(peerId)

    @Test
    fun `rebuilds the persisted score projection after recreation`() {
        val dataSource = JdbcDataSource().apply { setURL("jdbc:h2:mem:kerugi-recovery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1") }
        val firstProcess = journal(dataSource)
        firstProcess.apply(command(judgeId(7), KerugiScoringArea.HEAD, 0), eventId(10))
        assertIs<KerugiScoreResult.Applied>(firstProcess.apply(command(judgeId(8), KerugiScoringArea.BODY, 500), eventId(11)))

        val restartedProcess = journal(dataSource)

        assertEquals(1, restartedProcess.projection().blueScore)
        assertEquals(listOf(eventId(10), eventId(11)), restartedProcess.events().map { it.event.eventId })
    }

    private fun journal(dataSource: JdbcDataSource) = JdbcKerugiScoreJournal(
        dataSource, ownership, sessionId, KerugiScoringConfiguration(judges, 2, Duration.ofSeconds(1)),
        now = { Instant.parse("2026-09-12T12:00:00Z") },
    )

    private fun command(judgeId: JudgeId, area: KerugiScoringArea, offset: Long) = DomainCommand(
        CompetitionId("00000000-0000-4000-8000-000000000004"), peerId,
        CourtId("00000000-0000-4000-8000-000000000005"), bracketId, sessionId, judgeId,
        DeviceId("00000000-0000-4000-8000-000000000006"), EventSource("judge"), "judge-${judgeId.value}",
        KERUGI_SCORE_CANDIDATE_EVENT,
        Json.encodeToString(KerugiScoreCandidatePayload(KerugiCompetitor.BLUE, area, Instant.parse("2026-09-12T12:00:00Z").plusMillis(offset).toString())),
    )

    private fun judgeId(number: Int) = JudgeId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    private fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
