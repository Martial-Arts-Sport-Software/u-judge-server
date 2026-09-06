package org.mass.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

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

    @Test
    fun `rebuilds a session projection in logical event order`() {
        val projection = SessionLifecycleJournal.rebuild(
            BracketOwnership.assign(bracketId, ownerPeerId).start(ownerPeerId),
            sessionId,
            listOf(
                event(sequence = 3, type = "session_resumed"),
                event(sequence = 1, type = "session_started"),
                event(sequence = 2, type = "session_paused"),
            ),
        )

        assertEquals(SessionState.RUNNING, projection.state)
    }

    @Test
    fun `does not apply an identical redelivered lifecycle event twice during rebuild`() {
        val started = event(sequence = 1, type = "session_started")

        val projection = SessionLifecycleJournal.rebuild(
            BracketOwnership.assign(bracketId, ownerPeerId).start(ownerPeerId),
            sessionId,
            listOf(started, started, event(sequence = 2, type = "session_paused")),
        )

        assertEquals(SessionState.PAUSED, projection.state)
    }

    @Test
    fun `rejects a conflicting event id during rebuild`() {
        val started = event(sequence = 1, type = "session_started")
        val conflicting = event(sequence = 2, type = "session_cancelled", eventId = started.event.eventId)

        assertFailsWith<IllegalArgumentException> {
            SessionLifecycleJournal.rebuild(
                BracketOwnership.assign(bracketId, ownerPeerId).start(ownerPeerId),
                sessionId,
                listOf(started, conflicting),
            )
        }
    }

    @Test
    fun `rejects a lifecycle transition ordered before its prerequisite during rebuild`() {
        assertFailsWith<IllegalStateException> {
            SessionLifecycleJournal.rebuild(
                BracketOwnership.assign(bracketId, ownerPeerId).start(ownerPeerId),
                sessionId,
                listOf(
                    event(sequence = 1, type = "session_paused"),
                    event(sequence = 2, type = "session_started"),
                ),
            )
        }
    }

    @Test
    fun `rejects lifecycle events when the bracket is not in progress during rebuild`() {
        assertFailsWith<IllegalArgumentException> {
            SessionLifecycleJournal.rebuild(
                BracketOwnership.assign(bracketId, ownerPeerId),
                sessionId,
                listOf(event(sequence = 1, type = "session_started")),
            )
        }
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

    private fun event(
        sequence: Long,
        type: String,
        eventId: EventId = eventId(sequence.toInt()),
    ) = SequencedDomainEvent(
        command(type = type).toEvent(eventId, Instant.parse("2026-09-06T12:00:00Z")),
        sequence,
    )

    private fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
