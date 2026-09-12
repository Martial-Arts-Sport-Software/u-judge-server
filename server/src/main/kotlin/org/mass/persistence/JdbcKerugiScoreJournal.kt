package org.mass.persistence

import org.mass.domain.BracketOwnership
import org.mass.domain.CompetitionId
import org.mass.domain.CourtId
import org.mass.domain.DeviceId
import org.mass.domain.DomainCommand
import org.mass.domain.DomainEvent
import org.mass.domain.EventId
import org.mass.domain.EventSource
import org.mass.domain.JudgeId
import org.mass.domain.KerugiScoreEventJournal
import org.mass.domain.KerugiScoreJournal
import org.mass.domain.KerugiScoreResult
import org.mass.domain.KerugiScoringConfiguration
import org.mass.domain.KerugiScoringResult
import org.mass.domain.PeerId
import org.mass.domain.SessionId
import org.mass.domain.SequencedDomainEvent
import org.mass.domain.diagnosticContext
import org.mass.replication.JournalSchema
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

class JdbcKerugiScoreJournal(
    private val dataSource: DataSource,
    private val ownership: BracketOwnership,
    private val sessionId: SessionId,
    private val configuration: KerugiScoringConfiguration,
    private val now: () -> Instant = Instant::now,
) : KerugiScoreEventJournal {
    private var currentProjection: KerugiScoringResult

    init {
        JournalSchema.migrate(dataSource)
        currentProjection = KerugiScoreJournal.rebuild(ownership, sessionId, configuration, events())
    }

    @Synchronized
    override fun apply(command: DomainCommand, eventId: EventId): KerugiScoreResult {
        val event = command.toEvent(eventId, now())
        findById(eventId)?.let { existing ->
            return if (KerugiScoreJournal.sameCommand(existing.event, event)) {
                KerugiScoreResult.Applied(existing, projectionAt(existing), isNew = false)
            } else {
                rejected("Event ID is already assigned to a different command", event)
            }
        }
        try {
            KerugiScoreJournal.validate(event, configuration, ownership, sessionId)
        } catch (error: IllegalArgumentException) {
            return rejected(error.message ?: "Invalid Kerugi score candidate", event)
        }
        val sequencedEvent = append(event)
        currentProjection = KerugiScoreJournal.rebuild(ownership, sessionId, configuration, events())
        return KerugiScoreResult.Applied(sequencedEvent, currentProjection)
    }

    override fun events(): List<SequencedDomainEvent> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT * FROM kerugi_score_events WHERE bracket_id = ? AND session_id = ? " +
                "ORDER BY owner_peer_id, sequence, event_id",
        ).use { statement ->
            statement.setString(1, ownership.bracketId.value)
            statement.setString(2, sessionId.value)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toSequencedEvent()) } }
        }
    }

    override fun projection(): KerugiScoringResult = currentProjection

    private fun projectionAt(event: SequencedDomainEvent): KerugiScoringResult = KerugiScoreJournal.rebuild(
        ownership, sessionId, configuration, events().filter { it.sequence <= event.sequence },
    )

    private fun append(event: DomainEvent): SequencedDomainEvent = dataSource.connection.use { connection ->
        connection.inTransaction {
            val sequencedEvent = SequencedDomainEvent(event, nextSequence(connection, event.peerId.value))
            connection.prepareStatement(
                "INSERT INTO kerugi_score_events (event_id, competition_id, owner_peer_id, court_id, bracket_id, " +
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
        connection.prepareStatement("SELECT * FROM kerugi_score_events WHERE event_id = ?").use { statement ->
            statement.setString(1, eventId.value)
            statement.executeQuery().use { result -> if (result.next()) result.toSequencedEvent() else null }
        }
    }

    private fun nextSequence(connection: Connection, ownerPeerId: String): Long = connection.prepareStatement(
        "SELECT COALESCE(MAX(sequence), 0) FROM kerugi_score_events WHERE owner_peer_id = ?",
    ).use { statement ->
        statement.setString(1, ownerPeerId)
        statement.executeQuery().use { result -> result.next(); result.getLong(1) + 1 }
    }

    private fun java.sql.ResultSet.toSequencedEvent() = SequencedDomainEvent(
        DomainEvent(
            EventId(getString("event_id")), CompetitionId(getString("competition_id")), PeerId(getString("owner_peer_id")),
            CourtId(getString("court_id")), org.mass.domain.BracketId(getString("bracket_id")), SessionId(getString("session_id")),
            JudgeId(getString("judge_id")), DeviceId(getString("device_id")), EventSource(getString("source")),
            getString("author"), Instant.parse(getString("occurred_at")), getString("event_type"), getString("payload"),
        ),
        getLong("sequence"),
    )

    private fun <T> Connection.inTransaction(block: () -> T): T {
        val originalAutoCommit = autoCommit
        autoCommit = false
        return try { block().also { commit() } } catch (exception: Exception) {
            rollback()
            throw exception
        } finally { autoCommit = originalAutoCommit }
    }

    private fun rejected(reason: String, event: DomainEvent) = KerugiScoreResult.Rejected(reason, event.diagnosticContext())
}
