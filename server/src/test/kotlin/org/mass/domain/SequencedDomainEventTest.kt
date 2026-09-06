package org.mass.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SequencedDomainEventTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")

    @Test
    fun `orders an owners events by logical sequence`() {
        val ordered = DomainEventOrder.order(listOf(event(sequence = 2), event(sequence = 1)))

        assertEquals(listOf(1L, 2L), ordered.map(SequencedDomainEvent::sequence))
    }

    @Test
    fun `rejects two events with the same owner sequence`() {
        assertFailsWith<IllegalArgumentException> {
            DomainEventOrder.order(listOf(event(sequence = 1), event(sequence = 1, eventNumber = 9)))
        }
    }

    @Test
    fun `rejects a non-positive sequence`() {
        assertFailsWith<IllegalArgumentException> { event(sequence = 0) }
    }

    private fun event(sequence: Long, eventNumber: Int = sequence.toInt()) = SequencedDomainEvent(
        event = DomainEvent(
            eventId = EventId("00000000-0000-4000-8000-${eventNumber.toString().padStart(12, '0')}"),
            competitionId = CompetitionId("00000000-0000-4000-8000-000000000002"),
            peerId = peerId,
            courtId = CourtId("00000000-0000-4000-8000-000000000003"),
            bracketId = BracketId("00000000-0000-4000-8000-000000000004"),
            sessionId = SessionId("00000000-0000-4000-8000-000000000005"),
            judgeId = JudgeId("00000000-0000-4000-8000-000000000006"),
            deviceId = DeviceId("00000000-0000-4000-8000-000000000007"),
            source = EventSource("operator"),
            author = "operator-1",
            occurredAt = Instant.parse("2026-09-06T12:00:00Z"),
            type = "session_started",
            payload = "{}",
        ),
        sequence = sequence,
    )
}
