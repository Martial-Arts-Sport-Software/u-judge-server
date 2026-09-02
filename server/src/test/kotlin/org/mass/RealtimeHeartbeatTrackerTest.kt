package org.mass

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealtimeHeartbeatTrackerTest {
    @Test
    fun `expires an authenticated session after its heartbeat deadline`() {
        var now = Instant.parse("2026-09-02T10:00:00Z")
        val tracker = RealtimeHeartbeatTracker(Duration.ofSeconds(10)) { now }

        tracker.connected("credential-1")
        now = now.plusSeconds(10)

        assertEquals(setOf("credential-1"), tracker.expireInactive())
        assertTrue(!tracker.isConnected("credential-1"))
    }

    @Test
    fun `valid heartbeat extends an authenticated session deadline`() {
        var now = Instant.parse("2026-09-02T10:00:00Z")
        val tracker = RealtimeHeartbeatTracker(Duration.ofSeconds(10)) { now }

        tracker.connected("credential-1")
        now = now.plusSeconds(9)
        tracker.heartbeat("credential-1")
        now = now.plusSeconds(9)

        assertEquals(emptySet(), tracker.expireInactive())
        assertTrue(tracker.isConnected("credential-1"))
    }
}
