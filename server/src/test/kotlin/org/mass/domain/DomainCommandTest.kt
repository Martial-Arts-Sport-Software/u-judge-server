package org.mass.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DomainCommandTest {
    @Test
    fun `creates an auditable event with assigned identity and timestamp`() {
        val event = validCommand().toEvent(
            eventId = EventId("00000000-0000-4000-8000-000000000009"),
            occurredAt = Instant.parse("2026-09-06T12:00:00Z"),
        )

        assertEquals("00000000-0000-4000-8000-000000000001", event.competitionId.value)
        assertEquals("00000000-0000-4000-8000-000000000002", event.peerId.value)
        assertEquals("00000000-0000-4000-8000-000000000003", event.courtId.value)
        assertEquals("00000000-0000-4000-8000-000000000004", event.bracketId.value)
        assertEquals("00000000-0000-4000-8000-000000000005", event.sessionId.value)
        assertEquals("00000000-0000-4000-8000-000000000006", event.judgeId.value)
        assertEquals("00000000-0000-4000-8000-000000000007", event.deviceId.value)
        assertEquals("mobile_judge", event.source.value)
        assertEquals("judge-ivanov", event.author)
        assertEquals("score_candidate", event.type)
        assertEquals("{\"participant\":\"blue\",\"area\":\"body\"}", event.payload)
        assertEquals("00000000-0000-4000-8000-000000000009", event.eventId.value)
        assertEquals(Instant.parse("2026-09-06T12:00:00Z"), event.occurredAt)
    }

    @Test
    fun `rejects blank mutable audit fields before event creation`() {
        assertFailsWith<IllegalArgumentException> { validCommand(author = " ") }
        assertFailsWith<IllegalArgumentException> { validCommand(type = " ") }
        assertFailsWith<IllegalArgumentException> { validCommand(payload = " ") }
    }

    private fun validCommand(
        author: String = "judge-ivanov",
        type: String = "score_candidate",
        payload: String = "{\"participant\":\"blue\",\"area\":\"body\"}",
    ) = DomainCommand(
        competitionId = CompetitionId("00000000-0000-4000-8000-000000000001"),
        peerId = PeerId("00000000-0000-4000-8000-000000000002"),
        courtId = CourtId("00000000-0000-4000-8000-000000000003"),
        bracketId = BracketId("00000000-0000-4000-8000-000000000004"),
        sessionId = SessionId("00000000-0000-4000-8000-000000000005"),
        judgeId = JudgeId("00000000-0000-4000-8000-000000000006"),
        deviceId = DeviceId("00000000-0000-4000-8000-000000000007"),
        source = EventSource("mobile_judge"),
        author = author,
        type = type,
        payload = payload,
    )
}
