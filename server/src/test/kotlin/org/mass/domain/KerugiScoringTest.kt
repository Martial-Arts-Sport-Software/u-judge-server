package org.mass.domain

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KerugiScoringTest {
    private val judges = (1..3).map(::judgeId).toSet()
    private val startedAt = Instant.parse("2026-09-11T12:00:00Z")

    @Test
    fun `default configuration uses two of three judges and a one second window`() {
        val configuration = KerugiScoringConfiguration.default(judges)

        assertEquals(2, configuration.quorum)
        assertEquals(Duration.ofSeconds(1), configuration.coincidenceWindow)
    }

    @Test
    fun `configured two judge composition can require both judges`() {
        val twoJudgeEngine = KerugiScoringEngine(
            KerugiScoringConfiguration(judges.take(2).toSet(), quorum = 2, coincidenceWindow = Duration.ofSeconds(1)),
        )

        val result = twoJudgeEngine.score(
            listOf(
                candidate(1, 1, KerugiCompetitor.BLUE, KerugiScoringArea.BODY, 0),
                candidate(2, 2, KerugiCompetitor.BLUE, KerugiScoringArea.BODY, 100),
            ),
        )

        assertEquals(1, result.blueScore)
    }

    @Test
    fun `rejects configurations outside the supported judge and quorum bounds`() {
        assertFailsWith<IllegalArgumentException> {
            KerugiScoringConfiguration(emptySet(), 1, Duration.ofSeconds(1))
        }
        assertFailsWith<IllegalArgumentException> {
            KerugiScoringConfiguration(judges + judgeId(4), 2, Duration.ofSeconds(1))
        }
        assertFailsWith<IllegalArgumentException> {
            KerugiScoringConfiguration(judges, 4, Duration.ofSeconds(1))
        }
        assertFailsWith<IllegalArgumentException> {
            KerugiScoringConfiguration(judges, 2, Duration.ZERO)
        }
    }

    @Test
    fun `awards the minimum conflicting candidate score after quorum`() {
        val result = engine().score(
            listOf(
                candidate(1, 1, KerugiCompetitor.BLUE, KerugiScoringArea.HEAD, 0),
                candidate(2, 2, KerugiCompetitor.BLUE, KerugiScoringArea.BODY, 500),
            ),
        )

        assertEquals(1, result.blueScore)
        assertEquals(0, result.redScore)
        assertEquals(listOf(eventId(1), eventId(2)), result.awards.single().candidateEventIds)
        assertEquals(KerugiWindowDecision.SCORE_AWARDED, result.audit.single().decision)
    }

    @Test
    fun `includes candidates on the coincidence window boundary`() {
        val result = engine().score(
            listOf(
                candidate(1, 1, KerugiCompetitor.RED, KerugiScoringArea.HEAD, 0),
                candidate(2, 2, KerugiCompetitor.RED, KerugiScoringArea.HEAD, 1_000),
            ),
        )

        assertEquals(2, result.redScore)
        assertEquals(KerugiWindowDecision.SCORE_AWARDED, result.audit.single().decision)
    }

    @Test
    fun `retains insufficient and late candidates in separate auditable windows`() {
        val result = engine().score(
            listOf(
                candidate(1, 1, KerugiCompetitor.BLUE, KerugiScoringArea.BODY, 0),
                candidate(2, 2, KerugiCompetitor.BLUE, KerugiScoringArea.HEAD, 1_001),
            ),
        )

        assertEquals(0, result.blueScore)
        assertEquals(
            listOf(KerugiWindowDecision.QUORUM_NOT_REACHED, KerugiWindowDecision.QUORUM_NOT_REACHED),
            result.audit.map(KerugiWindowAudit::decision),
        )
    }

    @Test
    fun `requires distinct configured judges for quorum`() {
        val result = engine().score(
            listOf(
                candidate(1, 1, KerugiCompetitor.BLUE, KerugiScoringArea.HEAD, 0),
                candidate(2, 1, KerugiCompetitor.BLUE, KerugiScoringArea.BODY, 100),
            ),
        )

        assertEquals(0, result.blueScore)
        assertEquals(1, result.audit.single().distinctJudgeCount)
        assertEquals(KerugiWindowDecision.QUORUM_NOT_REACHED, result.audit.single().decision)
    }

    @Test
    fun `duplicate delivery with the same event ID does not apply a score twice`() {
        val first = candidate(1, 1, KerugiCompetitor.RED, KerugiScoringArea.HEAD, 0)
        val result = engine().score(
            listOf(
                first,
                first,
                candidate(2, 2, KerugiCompetitor.RED, KerugiScoringArea.HEAD, 100),
            ),
        )

        assertEquals(2, result.redScore)
        assertEquals(listOf(eventId(1), eventId(2)), result.audit.single().candidateEventIds)
    }

    @Test
    fun `out of order delivery produces the same score and audit`() {
        val ordered = listOf(
            candidate(1, 1, KerugiCompetitor.RED, KerugiScoringArea.HEAD, 0),
            candidate(2, 2, KerugiCompetitor.RED, KerugiScoringArea.BODY, 100),
        )

        assertEquals(engine().score(ordered), engine().score(ordered.reversed()))
    }

    @Test
    fun `rejects candidates from a judge outside the configured composition`() {
        assertFailsWith<IllegalArgumentException> {
            engine().score(listOf(candidate(1, 4, KerugiCompetitor.BLUE, KerugiScoringArea.BODY, 0)))
        }
    }

    @Test
    fun `rejects a conflicting reuse of an event ID`() {
        assertFailsWith<IllegalArgumentException> {
            engine().score(
                listOf(
                    candidate(1, 1, KerugiCompetitor.BLUE, KerugiScoringArea.BODY, 0),
                    candidate(1, 2, KerugiCompetitor.BLUE, KerugiScoringArea.BODY, 100),
                ),
            )
        }
    }

    private fun engine() = KerugiScoringEngine(KerugiScoringConfiguration.default(judges))

    private fun candidate(
        event: Int,
        judge: Int,
        competitor: KerugiCompetitor,
        area: KerugiScoringArea,
        milliseconds: Long,
    ) = KerugiScoreCandidate(eventId(event), judgeId(judge), competitor, area, startedAt.plusMillis(milliseconds))

    private fun judgeId(number: Int) = JudgeId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    private fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
