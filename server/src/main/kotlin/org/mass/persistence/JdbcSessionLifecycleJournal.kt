package org.mass.persistence

import org.mass.domain.BracketOwnership
import org.mass.domain.BracketState
import org.mass.domain.CompetitionId
import org.mass.domain.CourtId
import org.mass.domain.DeviceId
import org.mass.domain.DomainCommand
import org.mass.domain.DomainEvent
import org.mass.domain.EventId
import org.mass.domain.EventSource
import org.mass.domain.JudgeId
import org.mass.domain.PeerId
import org.mass.domain.SessionId
import org.mass.domain.SessionLifecycleJournal
import org.mass.domain.SessionLifecycleEventJournal
import org.mass.domain.SessionLifecycleResult
import org.mass.domain.SessionProjection
import org.mass.domain.SessionState
import org.mass.domain.SequencedDomainEvent
import org.mass.domain.diagnosticContext
import org.mass.replication.JournalSchema
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

class JdbcSessionLifecycleJournal(
    private val dataSource: DataSource,
    private val ownership: BracketOwnership,
    private val sessionId: SessionId,
    private val now: () -> Instant = Instant::now,
) : SessionLifecycleEventJournal {
    private var currentProjection: SessionProjection

    init {
        JournalSchema.migrate(dataSource)
        currentProjection = SessionLifecycleJournal.rebuild(ownership, sessionId, events())
    }

    @Synchronized
    override fun apply(command: DomainCommand, eventId: EventId): SessionLifecycleResult {
        val event = command.toEvent(eventId, now())
        findById(eventId)?.let { existing ->
            return if (sameCommand(existing.event, event)) {
                SessionLifecycleResult.Applied(existing, projectionAt(existing))
            } else {
                rejected("Event ID is already assigned to a different command", event)
            }
        }
        val nextState = stateFor(event.type) ?: return rejected("Unsupported session lifecycle command", event)
        if (event.bracketId != ownership.bracketId || event.sessionId != sessionId) {
            return rejected("Command does not target this session", event)
        }
        if (event.peerId != ownership.ownerPeerId || ownership.state != BracketState.IN_PROGRESS) {
            return rejected("Only the in-progress bracket owner can change its session", event)
        }
        val nextProjection = try {
            currentProjection.transitionTo(nextState)
        } catch (error: IllegalStateException) {
            return rejected(error.message ?: "Invalid session transition", event)
        }
        val sequencedEvent = append(event)
        currentProjection = nextProjection
        return SessionLifecycleResult.Applied(sequencedEvent, currentProjection)
    }

    override fun events(): List<SequencedDomainEvent> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT * FROM session_lifecycle_events WHERE bracket_id = ? AND session_id = ? " +
                "ORDER BY owner_peer_id, sequence, event_id",
        ).use { statement ->
            statement.setString(1, ownership.bracketId.value)
            statement.setString(2, sessionId.value)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.toSequencedEvent()) }
            }
        }
    }

    override fun projection(): SessionProjection = currentProjection

    private fun projectionAt(event: SequencedDomainEvent): SessionProjection = SessionLifecycleJournal.rebuild(
        ownership,
        sessionId,
        events().filter { it.sequence <= event.sequence },
    )

    private fun append(event: DomainEvent): SequencedDomainEvent = dataSource.connection.use { connection ->
        connection.inTransaction {
            val sequencedEvent = SequencedDomainEvent(event, nextSequence(connection, event.peerId.value))
            connection.prepareStatement(
                "INSERT INTO session_lifecycle_events (event_id, competition_id, owner_peer_id, court_id, bracket_id, " +
                    "session_id, judge_id, device_id, source, author, occurred_at, event_type, payload, sequence) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, event.eventId.value)
                statement.setString(2, event.competitionId.value)
                statement.setString(3, event.peerId.value)
                statement.setString(4, event.courtId.value)
                statement.setString(5, event.bracketId.value)
                statement.setString(6, event.sessionId.value)
                statement.setString(7, event.judgeId.value)
                statement.setString(8, event.deviceId.value)
                statement.setString(9, event.source.value)
                statement.setString(10, event.author)
                statement.setString(11, event.occurredAt.toString())
                statement.setString(12, event.type)
                statement.setString(13, event.payload)
                statement.setLong(14, sequencedEvent.sequence)
                statement.executeUpdate()
            }
            sequencedEvent
        }
    }

    private fun findById(eventId: EventId): SequencedDomainEvent? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM session_lifecycle_events WHERE event_id = ?").use { statement ->
            statement.setString(1, eventId.value)
            statement.executeQuery().use { result -> if (result.next()) result.toSequencedEvent() else null }
        }
    }

    private fun nextSequence(connection: Connection, ownerPeerId: String): Long = connection.prepareStatement(
        "SELECT COALESCE(MAX(sequence), 0) FROM session_lifecycle_events WHERE owner_peer_id = ?",
    ).use { statement ->
        statement.setString(1, ownerPeerId)
        statement.executeQuery().use { result -> result.next(); result.getLong(1) + 1 }
    }

    private fun java.sql.ResultSet.toSequencedEvent() = SequencedDomainEvent(
        DomainEvent(
            eventId = EventId(getString("event_id")),
            competitionId = CompetitionId(getString("competition_id")),
            peerId = PeerId(getString("owner_peer_id")),
            courtId = CourtId(getString("court_id")),
            bracketId = org.mass.domain.BracketId(getString("bracket_id")),
            sessionId = SessionId(getString("session_id")),
            judgeId = JudgeId(getString("judge_id")),
            deviceId = DeviceId(getString("device_id")),
            source = EventSource(getString("source")),
            author = getString("author"),
            occurredAt = Instant.parse(getString("occurred_at")),
            type = getString("event_type"),
            payload = getString("payload"),
        ),
        getLong("sequence"),
    )

    private fun <T> Connection.inTransaction(block: () -> T): T {
        val originalAutoCommit = autoCommit
        autoCommit = false
        return try {
            block().also { commit() }
        } catch (exception: Exception) {
            rollback()
            throw exception
        } finally {
            autoCommit = originalAutoCommit
        }
    }

    private fun rejected(reason: String, event: DomainEvent) =
        SessionLifecycleResult.Rejected(reason, event.diagnosticContext())

    private fun sameCommand(existing: DomainEvent, candidate: DomainEvent): Boolean =
        existing.copy(occurredAt = candidate.occurredAt) == candidate

    private fun stateFor(type: String): SessionState? = when (type) {
        "session_started", "session_resumed" -> SessionState.RUNNING
        "session_paused" -> SessionState.PAUSED
        "session_completed" -> SessionState.COMPLETED
        "session_cancelled" -> SessionState.CANCELLED
        else -> null
    }
}
