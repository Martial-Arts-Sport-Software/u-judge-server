package org.mass.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticContextTest {
    @Test
    fun `retains stable audit identifiers without author or payload`() {
        val context = DomainEvent(
            EventId("00000000-0000-4000-8000-000000000001"), CompetitionId("00000000-0000-4000-8000-000000000002"),
            PeerId("00000000-0000-4000-8000-000000000003"), CourtId("00000000-0000-4000-8000-000000000004"),
            BracketId("00000000-0000-4000-8000-000000000005"), SessionId("00000000-0000-4000-8000-000000000006"),
            JudgeId("00000000-0000-4000-8000-000000000007"), DeviceId("00000000-0000-4000-8000-000000000008"),
            EventSource("mobile"), "ivanov", Instant.parse("2026-09-06T00:00:00Z"), "score", "{\"surname\":\"Ivanov\"}",
        ).diagnosticContext()

        assertEquals("00000000-0000-4000-8000-000000000001", context.eventId.value)
        assertEquals("00000000-0000-4000-8000-000000000008", context.deviceId.value)
    }
}
