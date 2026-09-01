package org.mass.replication

import java.sql.Connection
import javax.sql.DataSource
import java.util.UUID

class JdbcPeerJournal(
    private val peerId: String,
    private val dataSource: DataSource,
) {
    init {
        require(peerId.isNotBlank())
        JournalSchema.migrate(dataSource)
    }

    val events: List<JournalEvent>
        get() = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT id, owner_peer_id, sequence, payload FROM peer_journal_events " +
                    "ORDER BY owner_peer_id, sequence, id",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(result.toJournalEvent())
                        }
                    }
                }
            }
        }

    val eventIds: Set<String>
        get() = events.mapTo(linkedSetOf(), JournalEvent::id)

    val missingSequences: Map<String, Set<Long>>
        get() = events.groupBy(JournalEvent::ownerPeerId)
            .mapValues { (_, ownerEvents) -> missingSequences(ownerEvents) }
            .filterValues { it.isNotEmpty() }

    val projectedPayloads: List<String>
        get() = events.groupBy(JournalEvent::ownerPeerId)
            .toSortedMap()
            .values
            .flatMap(::contiguousEvents)
            .map(JournalEvent::payload)

    fun append(payload: String): JournalEvent = append(UUID.randomUUID().toString(), payload)

    fun append(id: String, payload: String): JournalEvent = dataSource.connection.use { connection ->
        connection.inTransaction {
            val existing = findById(connection, id)
            if (existing != null) {
                require(existing.ownerPeerId == peerId && existing.payload == payload) {
                    "Event ID $id conflicts with an existing event"
                }
                return@inTransaction existing
            }
            val event = JournalEvent(
                id = id,
                ownerPeerId = peerId,
                sequence = nextSequence(connection),
                payload = payload,
            )
            receive(connection, listOf(event))
            event
        }
    }

    fun receive(incomingEvents: Collection<JournalEvent>) {
        dataSource.connection.use { connection ->
            connection.inTransaction {
                receive(connection, incomingEvents)
            }
        }
    }

    private fun receive(connection: Connection, incomingEvents: Collection<JournalEvent>) {
        incomingEvents.forEach { event ->
            require(event.id.isNotBlank())
            require(event.ownerPeerId.isNotBlank())
            require(event.sequence > 0)

            val existing = findById(connection, event.id)
            require(existing == null || existing == event) { "Event ID ${event.id} conflicts with an existing event" }
            val sequenceEvent = findByOwnerAndSequence(connection, event.ownerPeerId, event.sequence)
            require(sequenceEvent == null || sequenceEvent.id == event.id) {
                "Sequence ${event.sequence} already exists for ${event.ownerPeerId}"
            }
            if (existing == null) {
                connection.prepareStatement(
                    "INSERT INTO peer_journal_events (id, owner_peer_id, sequence, payload) VALUES (?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, event.id)
                    statement.setString(2, event.ownerPeerId)
                    statement.setLong(3, event.sequence)
                    statement.setString(4, event.payload)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun nextSequence(connection: Connection): Long = connection.prepareStatement(
        "SELECT COALESCE(MAX(sequence), 0) FROM peer_journal_events WHERE owner_peer_id = ?",
    ).use { statement ->
        statement.setString(1, peerId)
        statement.executeQuery().use { result ->
            result.next()
            result.getLong(1) + 1
        }
    }

    private fun findById(connection: Connection, id: String): JournalEvent? = connection.prepareStatement(
        "SELECT id, owner_peer_id, sequence, payload FROM peer_journal_events WHERE id = ?",
    ).use { statement ->
        statement.setString(1, id)
        statement.executeQuery().use { result -> if (result.next()) result.toJournalEvent() else null }
    }

    private fun findByOwnerAndSequence(connection: Connection, ownerPeerId: String, sequence: Long): JournalEvent? =
        connection.prepareStatement(
            "SELECT id, owner_peer_id, sequence, payload FROM peer_journal_events " +
                "WHERE owner_peer_id = ? AND sequence = ?",
        ).use { statement ->
            statement.setString(1, ownerPeerId)
            statement.setLong(2, sequence)
            statement.executeQuery().use { result -> if (result.next()) result.toJournalEvent() else null }
        }

    private fun missingSequences(ownerEvents: List<JournalEvent>): Set<Long> {
        val receivedSequences = ownerEvents.mapTo(sortedSetOf(), JournalEvent::sequence)
        val highestSequence = receivedSequences.lastOrNull() ?: return emptySet()
        return (1..highestSequence).filterTo(sortedSetOf()) { it !in receivedSequences }
    }

    private fun contiguousEvents(ownerEvents: List<JournalEvent>): List<JournalEvent> = buildList {
        ownerEvents.sortedBy(JournalEvent::sequence).forEach { event ->
            if (event.sequence == size + 1L) {
                add(event)
            }
        }
    }

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

    private fun java.sql.ResultSet.toJournalEvent() = JournalEvent(
        id = getString("id"),
        ownerPeerId = getString("owner_peer_id"),
        sequence = getLong("sequence"),
        payload = getString("payload"),
    )
}

private object JournalSchema {
    private const val migrationVersion = 1

    fun migrate(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS u_judge_schema_migrations (version INTEGER PRIMARY KEY)",
                )
            }
            if (isApplied(connection)) return
            connection.inTransaction {
                val statements = checkNotNull(JournalSchema::class.java.getResourceAsStream("/db/migration/V1__peer_journal.sql"))
                    .bufferedReader()
                    .use { it.readText() }
                    .split(';')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                connection.createStatement().use { statement -> statements.forEach(statement::executeUpdate) }
                connection.prepareStatement("INSERT INTO u_judge_schema_migrations (version) VALUES (?)").use { statement ->
                    statement.setInt(1, migrationVersion)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun isApplied(connection: Connection): Boolean = connection.prepareStatement(
        "SELECT 1 FROM u_judge_schema_migrations WHERE version = ?",
    ).use { statement ->
        statement.setInt(1, migrationVersion)
        statement.executeQuery().use { it.next() }
    }

    private fun Connection.inTransaction(block: () -> Unit) {
        val originalAutoCommit = autoCommit
        autoCommit = false
        try {
            block()
            commit()
        } catch (exception: Exception) {
            rollback()
            throw exception
        } finally {
            autoCommit = originalAutoCommit
        }
    }
}
