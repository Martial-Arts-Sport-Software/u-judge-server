package org.mass.domain

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DomainIdsTest {
    @Test
    fun `generates globally unique UUID values for every domain entity type`() {
        val identifiers = listOf(
            CompetitionId.new().value,
            PeerId.new().value,
            CourtId.new().value,
            BracketId.new().value,
            SessionId.new().value,
            JudgeId.new().value,
            DeviceId.new().value,
            EventId.new().value,
        )

        assertEquals(identifiers.size, identifiers.toSet().size)
        identifiers.forEach { assertEquals(it, UUID.fromString(it).toString()) }
    }

    @Test
    fun `rejects malformed and noncanonical domain identifier values`() {
        assertFailsWith<IllegalArgumentException> { CompetitionId("competition-1") }
        assertFailsWith<IllegalArgumentException> { PeerId("550E8400-E29B-41D4-A716-446655440000") }
    }
}
