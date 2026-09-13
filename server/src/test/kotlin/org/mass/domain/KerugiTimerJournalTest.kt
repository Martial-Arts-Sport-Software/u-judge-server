package org.mass.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KerugiTimerJournalTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val bracketId = BracketId("00000000-0000-4000-8000-000000000002")
    private val sessionId = SessionId("00000000-0000-4000-8000-000000000003")
    private val ownership = BracketOwnership.assign(bracketId, peerId).start(peerId)

    @Test
    fun `applies owner start pause resume and stop transitions in append-only order`() {
        val journal = journal()
        listOf("kerugi_timer_started", "kerugi_timer_paused", "kerugi_timer_resumed", "kerugi_timer_stopped").forEachIndexed { index, type ->
            assertIs<KerugiTimerResult.Applied>(journal.apply(command(type), eventId(index + 10)))
        }
        assertEquals(KerugiTimerState.STOPPED, journal.projection().state)
        assertEquals(4, journal.events().size)
        assertEquals(journal.projection(), KerugiTimerJournal.rebuild(ownership, sessionId, journal.events()))
    }

    @Test
    fun `rejects invalid foreign and conflicting timer commands without changing state`() {
        val journal = journal()
        assertIs<KerugiTimerResult.Rejected>(journal.apply(command("kerugi_timer_paused"), eventId(10)))
        assertIs<KerugiTimerResult.Rejected>(journal.apply(command("kerugi_timer_started", peerId = PeerId("00000000-0000-4000-8000-000000000009")), eventId(11)))
        val started = assertIs<KerugiTimerResult.Applied>(journal.apply(command("kerugi_timer_started"), eventId(12)))
        assertEquals(false, assertIs<KerugiTimerResult.Applied>(journal.apply(command("kerugi_timer_started"), eventId(12))).isNew)
        assertIs<KerugiTimerResult.Rejected>(journal.apply(command("kerugi_timer_stopped"), eventId(12)))
        assertEquals(KerugiTimerState.RUNNING, journal.projection().state)
        assertEquals(1, journal.events().size)
        assertEquals(KerugiTimerState.RUNNING, started.projection.state)
    }

    private fun journal() = KerugiTimerJournal(ownership, sessionId, now = { Instant.parse("2026-09-13T12:00:00Z") })
    private fun command(type: String, peerId: PeerId = this.peerId) = DomainCommand(
        CompetitionId("00000000-0000-4000-8000-000000000004"), peerId, CourtId("00000000-0000-4000-8000-000000000005"), bracketId,
        sessionId, JudgeId("00000000-0000-4000-8000-000000000006"), DeviceId("00000000-0000-4000-8000-000000000007"), EventSource("operator"), "operator", type, "{}",
    )
    private fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
