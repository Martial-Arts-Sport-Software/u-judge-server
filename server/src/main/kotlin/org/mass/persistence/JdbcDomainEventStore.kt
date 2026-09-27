package org.mass.persistence

import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.CourtId
import org.mass.domain.DeviceId
import org.mass.domain.DomainEvent
import org.mass.domain.EventId
import org.mass.domain.EventSource
import org.mass.domain.JudgeId
import org.mass.domain.PeerId
import org.mass.domain.SequencedDomainEvent
import org.mass.domain.SessionId
import org.mass.replication.JournalSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import javax.sql.DataSource

/**
 * The single append-only `domain_events` journal of a peer. Every event gets the next sequence of its owner peer across all
 * event types, so `(owner_peer_id, sequence)` identifies one event in the whole journal and an event ID is never reused
 * by another command type.
 */
class JdbcDomainEventStore(private val dataSource: DataSource) {
    init {
        JournalSchema.migrate(dataSource)
    }

    @Synchronized
    fun append(event: DomainEvent): SequencedDomainEvent = dataSource.connection.use { connection ->
        connection.inTransaction {
            val sequenced = SequencedDomainEvent(event, nextSequence(connection, event.peerId.value))
            connection.prepareStatement(
                "INSERT INTO domain_events (event_id, owner_peer_id, sequence, competition_id, court_id, bracket_id, " +
                    "session_id, judge_id, device_id, source, author, occurred_at, event_type, payload) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, event.eventId.value)
                statement.setString(2, event.peerId.value)
                statement.setLong(3, sequenced.sequence)
                statement.setString(4, event.competitionId.value)
                statement.setString(5, event.courtId.value)
                statement.setString(6, event.bracketId.value)
                statement.setString(7, event.sessionId.value)
                statement.setString(8, event.judgeId.value)
                statement.setString(9, event.deviceId.value)
                statement.setString(10, event.source.value)
                statement.setString(11, event.author)
                statement.setString(12, event.occurredAt.toString())
                statement.setString(13, event.type)
                statement.setString(14, event.payload)
                statement.executeUpdate()
            }
            sequenced
        }
    }

    /**
     * Appends a peer-scoped event that belongs to no bracket or session, such as a device registry decision or an import of
     * competition applications.
     */
    @Synchronized
    fun appendPeerEvent(event: PeerScopedEvent): Long = dataSource.connection.use { connection ->
        connection.inTransaction {
            val sequence = nextSequence(connection, event.peerId.value)
            connection.prepareStatement(
                "INSERT INTO domain_events (event_id, owner_peer_id, sequence, competition_id, device_id, source, author, " +
                    "occurred_at, event_type, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, event.eventId.value)
                statement.setString(2, event.peerId.value)
                statement.setLong(3, sequence)
                statement.setString(4, event.competitionId?.value)
                statement.setString(5, event.deviceId)
                statement.setString(6, event.source.value)
                statement.setString(7, event.author)
                statement.setString(8, event.occurredAt.toString())
                statement.setString(9, event.type)
                statement.setString(10, event.payload)
                statement.executeUpdate()
            }
            sequence
        }
    }

    /** Peer-scoped events of [types] in journal order. */
    fun peerEvents(types: Set<String>): List<PeerScopedEvent> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT * FROM domain_events WHERE session_id IS NULL ORDER BY owner_peer_id, sequence, event_id",
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        if (result.getString("event_type") !in types) continue
                        add(
                            PeerScopedEvent(
                                eventId = EventId(result.getString("event_id")),
                                peerId = PeerId(result.getString("owner_peer_id")),
                                competitionId = result.getString("competition_id")?.let(::CompetitionId),
                                deviceId = result.getString("device_id"),
                                source = EventSource(result.getString("source")),
                                author = result.getString("author"),
                                occurredAt = Instant.parse(result.getString("occurred_at")),
                                type = result.getString("event_type"),
                                payload = result.getString("payload"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Returns the stored event when [eventId] belongs to a session-scoped event of one of [types]. */
    fun find(eventId: EventId, types: Set<String>): EventLookup = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM domain_events WHERE event_id = ?").use { statement ->
            statement.setString(1, eventId.value)
            statement.executeQuery().use { result ->
                when {
                    !result.next() -> EventLookup.Missing
                    result.getString("event_type") in types && result.getString("session_id") != null ->
                        EventLookup.Found(result.toSequencedEvent())
                    else -> EventLookup.AssignedElsewhere
                }
            }
        }
    }

    fun sessionEvents(bracketId: BracketId, sessionId: SessionId, types: Set<String>): List<SequencedDomainEvent> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT * FROM domain_events WHERE bracket_id = ? AND session_id = ? " +
                    "ORDER BY owner_peer_id, sequence, event_id",
            ).use { statement ->
                statement.setString(1, bracketId.value)
                statement.setString(2, sessionId.value)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            if (result.getString("event_type") in types) add(result.toSequencedEvent())
                        }
                    }
                }
            }
        }

    private fun nextSequence(connection: Connection, ownerPeerId: String): Long = connection.prepareStatement(
        "SELECT COALESCE(MAX(sequence), 0) FROM domain_events WHERE owner_peer_id = ?",
    ).use { statement ->
        statement.setString(1, ownerPeerId)
        statement.executeQuery().use { result ->
            result.next()
            result.getLong(1) + 1
        }
    }

    private fun ResultSet.toSequencedEvent() = SequencedDomainEvent(
        DomainEvent(
            eventId = EventId(getString("event_id")),
            competitionId = CompetitionId(getString("competition_id")),
            peerId = PeerId(getString("owner_peer_id")),
            courtId = CourtId(getString("court_id")),
            bracketId = BracketId(getString("bracket_id")),
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

    /**
     * Writes every journal row, in journal order, as one JSON object per line: a complete logical backup, since all state is
     * rebuilt from the journal (ADR-005, `BAK-001`).
     */
    fun exportJournal(target: Path) {
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM domain_events ORDER BY owner_peer_id, sequence").use { statement ->
                statement.executeQuery().use { result ->
                    Files.newBufferedWriter(temporary).use { writer ->
                        val columns = (1..result.metaData.columnCount).map { result.metaData.getColumnName(it).lowercase() }
                        while (result.next()) {
                            val row = buildJsonObject {
                                columns.forEach { column ->
                                    val value = result.getObject(column)
                                    put(column, if (value is Number) JsonPrimitive(value) else JsonPrimitive(value?.toString()))
                                }
                            }
                            writer.write(row.toString())
                            writer.newLine()
                        }
                    }
                }
            }
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    data class PeerScopedEvent(
        val eventId: EventId,
        val peerId: PeerId,
        val deviceId: String?,
        val source: EventSource,
        val author: String,
        val occurredAt: Instant,
        val type: String,
        val payload: String,
        val competitionId: CompetitionId? = null,
    )

    sealed interface EventLookup {
        data object Missing : EventLookup

        data class Found(val event: SequencedDomainEvent) : EventLookup

        /** The event ID is already used by another event type or a non-session event. */
        data object AssignedElsewhere : EventLookup
    }
}
