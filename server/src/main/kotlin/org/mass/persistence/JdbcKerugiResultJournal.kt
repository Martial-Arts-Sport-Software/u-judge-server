package org.mass.persistence

import org.mass.domain.*
import org.mass.replication.JournalSchema
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

class JdbcKerugiResultJournal(
    private val dataSource: DataSource,
    private val ownership: BracketOwnership,
    private val sessionId: SessionId,
    private val now: () -> Instant = Instant::now,
) : KerugiResultEventJournal {
    private var currentProjection: KerugiResultProjection?

    init { JournalSchema.migrate(dataSource); currentProjection = KerugiResultJournal.rebuild(ownership, sessionId, events()) }

    @Synchronized
    override fun apply(command: DomainCommand, eventId: EventId): KerugiResultJournalResult {
        val event = command.toEvent(eventId, now())
        findById(eventId)?.let { existing ->
            return if (KerugiResultJournal.sameCommand(existing.event, event)) {
                KerugiResultJournalResult.Applied(existing, requireNotNull(projectionAt(existing)), false)
            } else rejected("Event ID is already assigned to a different command", event)
        }
        val next = try {
            KerugiResultJournal.validate(event, ownership, sessionId, events().map(SequencedDomainEvent::event))
            KerugiResultJournal.projectionFor(event)
        } catch (error: IllegalArgumentException) {
            return rejected(error.message ?: "Invalid Kerugi result command", event)
        }
        val sequenced = append(event)
        currentProjection = next
        return KerugiResultJournalResult.Applied(sequenced, next)
    }

    override fun events(): List<SequencedDomainEvent> = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM kerugi_result_events WHERE bracket_id = ? AND session_id = ? ORDER BY owner_peer_id, sequence, event_id").use { statement ->
            statement.setString(1, ownership.bracketId.value); statement.setString(2, sessionId.value)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toSequencedEvent()) } }
        }
    }

    override fun projection(): KerugiResultProjection? = currentProjection

    private fun projectionAt(event: SequencedDomainEvent) = KerugiResultJournal.rebuild(
        ownership, sessionId, events().filter { it.sequence <= event.sequence },
    )

    private fun append(event: DomainEvent): SequencedDomainEvent = dataSource.connection.use { connection -> connection.inTransaction {
        val sequenced = SequencedDomainEvent(event, nextSequence(connection, event.peerId.value))
        connection.prepareStatement("INSERT INTO kerugi_result_events (event_id, competition_id, owner_peer_id, court_id, bracket_id, session_id, judge_id, device_id, source, author, occurred_at, event_type, payload, sequence) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { statement ->
            statement.setString(1, event.eventId.value); statement.setString(2, event.competitionId.value); statement.setString(3, event.peerId.value)
            statement.setString(4, event.courtId.value); statement.setString(5, event.bracketId.value); statement.setString(6, event.sessionId.value)
            statement.setString(7, event.judgeId.value); statement.setString(8, event.deviceId.value); statement.setString(9, event.source.value)
            statement.setString(10, event.author); statement.setString(11, event.occurredAt.toString()); statement.setString(12, event.type)
            statement.setString(13, event.payload); statement.setLong(14, sequenced.sequence); statement.executeUpdate()
        }
        sequenced
    } }

    private fun findById(eventId: EventId): SequencedDomainEvent? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM kerugi_result_events WHERE event_id = ?").use { statement ->
            statement.setString(1, eventId.value); statement.executeQuery().use { result -> if (result.next()) result.toSequencedEvent() else null }
        }
    }

    private fun nextSequence(connection: Connection, peerId: String): Long = connection.prepareStatement("SELECT COALESCE(MAX(sequence), 0) FROM kerugi_result_events WHERE owner_peer_id = ?").use { statement ->
        statement.setString(1, peerId); statement.executeQuery().use { result -> result.next(); result.getLong(1) + 1 }
    }

    private fun java.sql.ResultSet.toSequencedEvent() = SequencedDomainEvent(
        DomainEvent(EventId(getString("event_id")), CompetitionId(getString("competition_id")), PeerId(getString("owner_peer_id")), CourtId(getString("court_id")), BracketId(getString("bracket_id")), SessionId(getString("session_id")), JudgeId(getString("judge_id")), DeviceId(getString("device_id")), EventSource(getString("source")), getString("author"), Instant.parse(getString("occurred_at")), getString("event_type"), getString("payload")),
        getLong("sequence"),
    )

    private fun <T> Connection.inTransaction(block: () -> T): T {
        val original = autoCommit; autoCommit = false
        return try { block().also { commit() } } catch (error: Exception) { rollback(); throw error } finally { autoCommit = original }
    }

    private fun rejected(reason: String, event: DomainEvent) = KerugiResultJournalResult.Rejected(reason, event.diagnosticContext())
}
