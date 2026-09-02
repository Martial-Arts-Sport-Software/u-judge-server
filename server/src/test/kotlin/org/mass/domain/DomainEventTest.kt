package org.mass.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DomainEventTest {
    @Test
    fun `retains complete audit context and raw payload`() {
        val timestamp = Instant.parse("2026-09-02T12:00:00Z")
        val event = DomainEvent(
            eventId = EventId("00000000-0000-4000-8000-000000000001"),
            competitionId = CompetitionId("00000000-0000-4000-8000-000000000002"),
            peerId = PeerId("00000000-0000-4000-8000-000000000003"),
            courtId = CourtId("00000000-0000-4000-8000-000000000004"),
            bracketId = BracketId("00000000-0000-4000-8000-000000000005"),
            sessionId = SessionId("00000000-0000-4000-8000-000000000006"),
            judgeId = JudgeId("00000000-0000-4000-8000-000000000007"),
            deviceId = DeviceId("00000000-0000-4000-8000-000000000008"),
            source = EventSource("mobile_judge"),
            author = "judge-ivanov",
            occurredAt = timestamp,
            type = "score_candidate",
            payload = "{\"participant\":\"blue\",\"area\":\"body\"}",
        )

        assertEquals(timestamp, event.occurredAt)
        assertEquals("mobile_judge", event.source.value)
        assertEquals("judge-ivanov", event.author)
        assertEquals("{\"participant\":\"blue\",\"area\":\"body\"}", event.payload)
    }

    @Test
    fun `rejects blank audit context and payload`() {
        assertFailsWith<IllegalArgumentException> { EventSource(" ") }
        assertFailsWith<IllegalArgumentException> { validEvent(author = " ") }
        assertFailsWith<IllegalArgumentException> { validEvent(type = " ") }
        assertFailsWith<IllegalArgumentException> { validEvent(payload = " ") }
    }

    private fun validEvent(
        author: String = "judge-ivanov",
        type: String = "score_candidate",
        payload: String = "{}",
    ) = DomainEvent(
        eventId = EventId("00000000-0000-4000-8000-000000000001"),
        competitionId = CompetitionId("00000000-0000-4000-8000-000000000002"),
        peerId = PeerId("00000000-0000-4000-8000-000000000003"),
        courtId = CourtId("00000000-0000-4000-8000-000000000004"),
        bracketId = BracketId("00000000-0000-4000-8000-000000000005"),
        sessionId = SessionId("00000000-0000-4000-8000-000000000006"),
        judgeId = JudgeId("00000000-0000-4000-8000-000000000007"),
        deviceId = DeviceId("00000000-0000-4000-8000-000000000008"),
        source = EventSource("mobile_judge"),
        author = author,
        occurredAt = Instant.parse("2026-09-02T12:00:00Z"),
        type = type,
        payload = payload,
    )
}
