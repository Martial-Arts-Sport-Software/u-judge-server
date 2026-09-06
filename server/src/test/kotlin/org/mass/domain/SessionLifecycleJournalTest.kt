package org.mass.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SessionLifecycleJournalTest {
    private val ownerPeerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val bracketId = BracketId("00000000-0000-4000-8000-000000000002")
    private val sessionId = SessionId("00000000-0000-4000-8000-000000000003")

    @Test
    fun `appends an owner lifecycle command before updating the session projection`() {
        val journal = journal()

        val result = journal.apply(command(type = "session_started"), eventId(4))

        val applied = assertIs<SessionLifecycleResult.Applied>(result)
        assertEquals(1, applied.event.sequence)
        assertEquals(SessionState.RUNNING, applied.projection.state)
        assertEquals(listOf(applied.event), journal.events())
        assertEquals(SessionState.RUNNING, journal.projection().state)
    }

    @Test
    fun `rejects a lifecycle command from a non owner without changing the journal or projection`() {
        val journal = journal()

        val result = journal.apply(command(peerId = PeerId("00000000-0000-4000-8000-000000000009")), eventId(4))

        assertIs<SessionLifecycleResult.Rejected>(result)
        assertEquals(emptyList(), journal.events())
        assertEquals(SessionState.PREPARED, journal.projection().state)
    }

    @Test
    fun `rejects an invalid transition without changing the journal or projection`() {
        val journal = journal()

        val result = journal.apply(command(type = "session_paused"), eventId(4))

        assertIs<SessionLifecycleResult.Rejected>(result)
        assertEquals(emptyList(), journal.events())
        assertEquals(SessionState.PREPARED, journal.projection().state)
    }

    @Test
    fun `returns the original event when an accepted command is redelivered`() {
        val journal = journal()
        val command = command(type = "session_started")

        val first = assertIs<SessionLifecycleResult.Applied>(journal.apply(command, eventId(4)))
        val duplicate = assertIs<SessionLifecycleResult.Applied>(journal.apply(command, eventId(4)))

        assertEquals(first, duplicate)
        assertEquals(listOf(first.event), journal.events())
    }

    @Test
    fun `rejects a different command reusing an accepted event id`() {
        val journal = journal()

        journal.apply(command(type = "session_started"), eventId(4))
        val result = journal.apply(command(type = "session_cancelled"), eventId(4))

        assertIs<SessionLifecycleResult.Rejected>(result)
        assertEquals(1, journal.events().size)
        assertEquals(SessionState.RUNNING, journal.projection().state)
    }

    @Test
    fun `allocates one increasing sequence across the owners session journals`() {
        val sequence = PeerEventSequence(ownerPeerId)
        val firstJournal = journal(sequence = sequence)
        val secondSessionId = SessionId("00000000-0000-4000-8000-000000000010")
        val secondJournal = journal(sessionId = secondSessionId, sequence = sequence)

        val first = assertIs<SessionLifecycleResult.Applied>(firstJournal.apply(command(), eventId(4)))
        val second = assertIs<SessionLifecycleResult.Applied>(
            secondJournal.apply(command(sessionId = secondSessionId), eventId(11)),
        )

        assertEquals(1, first.event.sequence)
        assertEquals(2, second.event.sequence)
    }

    private fun journal(
        sessionId: SessionId = this.sessionId,
        sequence: PeerEventSequence = PeerEventSequence(ownerPeerId),
    ) = SessionLifecycleJournal(
        BracketOwnership.assign(bracketId, ownerPeerId).start(ownerPeerId),
        sessionId,
        sequence,
    )

    private fun command(
        peerId: PeerId = ownerPeerId,
        sessionId: SessionId = this.sessionId,
        type: String = "session_started",
    ) = DomainCommand(
        competitionId = CompetitionId("00000000-0000-4000-8000-000000000005"),
        peerId = peerId,
        courtId = CourtId("00000000-0000-4000-8000-000000000006"),
        bracketId = bracketId,
        sessionId = sessionId,
        judgeId = JudgeId("00000000-0000-4000-8000-000000000007"),
        deviceId = DeviceId("00000000-0000-4000-8000-000000000008"),
        source = EventSource("operator"),
        author = "operator-1",
        type = type,
        payload = "{}",
    )

    private fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
