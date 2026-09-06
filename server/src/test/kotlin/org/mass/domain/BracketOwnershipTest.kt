package org.mass.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BracketOwnershipTest {
    private val bracketId = BracketId("00000000-0000-4000-8000-000000000001")
    private val ownerPeerId = PeerId("00000000-0000-4000-8000-000000000002")

    @Test
    fun `owner starts its assigned bracket`() {
        val bracket = BracketOwnership.assign(bracketId, ownerPeerId)

        val inProgress = bracket.start(ownerPeerId)

        assertEquals(BracketState.IN_PROGRESS, inProgress.state)
        assertEquals(ownerPeerId, inProgress.ownerPeerId)
    }

    @Test
    fun `non-owner cannot start a bracket and does not change its state`() {
        val bracket = BracketOwnership.assign(bracketId, ownerPeerId)
        val otherPeerId = PeerId("00000000-0000-4000-8000-000000000003")

        assertFailsWith<IllegalArgumentException> { bracket.start(otherPeerId) }
        assertEquals(BracketState.PREPARED, bracket.state)
    }
}
