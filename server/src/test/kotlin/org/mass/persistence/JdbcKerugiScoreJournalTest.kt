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
import org.mass.domain.KERUGI_SCORE_CORRECTION_EVENT
import org.mass.domain.KERUGI_OPERATOR_ACTION_EVENT
import org.mass.domain.KERUGI_DISQUALIFICATION_EVENT
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiDisqualification
import org.mass.domain.KerugiDisqualificationPayload
import org.mass.domain.KerugiDisqualificationWarning
import org.mass.domain.KerugiOperatorActionPayload
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.KerugiScoreCandidatePayload
import org.mass.domain.KerugiScoreCorrectionPayload
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

    @Test
    fun `recovers persisted operator actions after recreation`() {
        val dataSource = JdbcDataSource().apply { setURL("jdbc:h2:mem:kerugi-operator-recovery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1") }
        val firstProcess = journal(dataSource)
        assertIs<KerugiScoreResult.Applied>(firstProcess.apply(operatorAction(KerugiOperatorActionType.THROW, KerugiCompetitor.BLUE, 2), eventId(10)))
        assertIs<KerugiScoreResult.Applied>(firstProcess.apply(operatorAction(KerugiOperatorActionType.GAMJEOM, KerugiCompetitor.BLUE, 1), eventId(11)))

        val restartedProcess = journal(dataSource)

        assertEquals(2, restartedProcess.projection().blueScore)
        assertEquals(1, restartedProcess.projection().redScore)
        assertEquals(2, restartedProcess.projection().operatorActions.size)
    }

    @Test
    fun `recovers a persisted score correction after recreation`() {
        val dataSource = JdbcDataSource().apply { setURL("jdbc:h2:mem:kerugi-correction-recovery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1") }
        val firstProcess = journal(dataSource)
        assertIs<KerugiScoreResult.Applied>(firstProcess.apply(operatorAction(KerugiOperatorActionType.THROW, KerugiCompetitor.BLUE, 2), eventId(10)))
        assertIs<KerugiScoreResult.Applied>(firstProcess.apply(correction(eventId(10)), eventId(11)))

        val restartedProcess = journal(dataSource)

        assertEquals(0, restartedProcess.projection().blueScore)
        assertEquals(listOf(eventId(10)), restartedProcess.projection().corrections.map { it.targetEventId })
        assertEquals(2, restartedProcess.events().size)
    }

    @Test
    fun `recovers a disqualification warning from persisted Gamjeom actions`() {
        val dataSource = JdbcDataSource().apply { setURL("jdbc:h2:mem:kerugi-gamjeom-warning;MODE=PostgreSQL;DB_CLOSE_DELAY=-1") }
        val firstProcess = journal(dataSource)
        (10..19).forEach { event ->
            assertIs<KerugiScoreResult.Applied>(
                firstProcess.apply(operatorAction(KerugiOperatorActionType.GAMJEOM, KerugiCompetitor.BLUE, 1), eventId(event)),
            )
        }

        val restartedProcess = journal(dataSource)

        assertEquals(listOf(KerugiDisqualificationWarning(KerugiCompetitor.BLUE, 10)), restartedProcess.projection().disqualificationWarnings)
        assertEquals(10, restartedProcess.projection().redScore)
    }

    @Test
    fun `recovers an operator-confirmed disqualification after recreation`() {
        val dataSource = JdbcDataSource().apply { setURL("jdbc:h2:mem:kerugi-disqualification-recovery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1") }
        val firstProcess = journal(dataSource)
        (10..19).forEach { event ->
            assertIs<KerugiScoreResult.Applied>(
                firstProcess.apply(operatorAction(KerugiOperatorActionType.GAMJEOM, KerugiCompetitor.BLUE, 1), eventId(event)),
            )
        }
        assertIs<KerugiScoreResult.Applied>(firstProcess.apply(disqualification(KerugiCompetitor.BLUE), eventId(20)))

        val restartedProcess = journal(dataSource)

        assertEquals(KerugiDisqualification(eventId(20).value, KerugiCompetitor.BLUE), restartedProcess.projection().disqualification)
        assertEquals(11, restartedProcess.events().size)
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

    private fun operatorAction(type: KerugiOperatorActionType, competitor: KerugiCompetitor, points: Int) = DomainCommand(
        CompetitionId("00000000-0000-4000-8000-000000000004"), peerId,
        CourtId("00000000-0000-4000-8000-000000000005"), bracketId, sessionId, judgeId(7),
        DeviceId("00000000-0000-4000-8000-000000000006"), EventSource("operator"), "operator",
        KERUGI_OPERATOR_ACTION_EVENT, Json.encodeToString(KerugiOperatorActionPayload(type, competitor, points)),
    )

    private fun correction(targetEventId: EventId) = DomainCommand(
        CompetitionId("00000000-0000-4000-8000-000000000004"), peerId,
        CourtId("00000000-0000-4000-8000-000000000005"), bracketId, sessionId, judgeId(7),
        DeviceId("00000000-0000-4000-8000-000000000006"), EventSource("operator"), "operator",
        KERUGI_SCORE_CORRECTION_EVENT, Json.encodeToString(KerugiScoreCorrectionPayload(targetEventId.value)),
    )

    private fun disqualification(competitor: KerugiCompetitor) = DomainCommand(
        CompetitionId("00000000-0000-4000-8000-000000000004"), peerId,
        CourtId("00000000-0000-4000-8000-000000000005"), bracketId, sessionId, judgeId(7),
        DeviceId("00000000-0000-4000-8000-000000000006"), EventSource("operator"), "operator",
        KERUGI_DISQUALIFICATION_EVENT, Json.encodeToString(KerugiDisqualificationPayload(competitor)),
    )

    private fun judgeId(number: Int) = JudgeId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    private fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
