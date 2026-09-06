package org.mass.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionProjectionTest {
    private val sessionId = SessionId("00000000-0000-4000-8000-000000000001")

    @Test
    fun `rebuilds a running session after a pause`() {
        val projection = SessionProjection.rebuild(
            sessionId,
            listOf(SessionState.RUNNING, SessionState.PAUSED, SessionState.RUNNING),
        )

        assertEquals(SessionState.RUNNING, projection.state)
    }

    @Test
    fun `rejects an invalid transition without changing the existing projection`() {
        val prepared = SessionProjection.initial(sessionId)

        assertFailsWith<IllegalStateException> { prepared.transitionTo(SessionState.PAUSED) }
        assertEquals(SessionState.PREPARED, prepared.state)
    }

    @Test
    fun `rejects transitions after completion`() {
        val completed = SessionProjection.initial(sessionId)
            .transitionTo(SessionState.RUNNING)
            .transitionTo(SessionState.COMPLETED)

        assertFailsWith<IllegalStateException> { completed.transitionTo(SessionState.RUNNING) }
    }
}
