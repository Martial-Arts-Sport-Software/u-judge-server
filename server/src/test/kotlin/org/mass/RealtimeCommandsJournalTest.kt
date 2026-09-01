package org.mass

import org.h2.jdbcx.JdbcDataSource
import org.mass.replication.JdbcPeerJournal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class RealtimeCommandsJournalTest {
    @Test
    fun `journal backed commands preserve an ACK across handler recreation`() {
        val dataSource = dataSource("durable-ack-retry")
        val journal = JdbcPeerJournal("court-1", dataSource)
        val command = command("event-1")

        val firstOutcome = RealtimeCommands(journal = journal).accept(command)
        val retryOutcome = RealtimeCommands(journal = journal).accept(command)

        assertEquals("event-1", assertIs<RealtimeCommandOutcome.Acknowledged>(firstOutcome).acknowledgement.eventId)
        assertEquals("event-1", assertIs<RealtimeCommandOutcome.Acknowledged>(retryOutcome).acknowledgement.eventId)
        assertEquals(setOf("event-1"), journal.eventIds)
    }

    @Test
    fun `journal backed commands reject a reused event ID with a different envelope`() {
        val journal = JdbcPeerJournal("court-1", dataSource("durable-ack-conflict"))
        val commands = RealtimeCommands(journal = journal)

        commands.accept(command("event-1"))
        val outcome = RealtimeCommands(journal = journal).accept(command("event-1", sequence = 2))

        assertEquals("event_id_conflict", assertIs<RealtimeCommandOutcome.Rejected>(outcome).code)
        assertEquals(setOf("event-1"), journal.eventIds)
    }

    @Test
    fun `journal backed commands reject without an ACK when persistence fails`() {
        val dataSource = dataSource("durable-ack-failure")
        val journal = JdbcPeerJournal("court-1", dataSource)
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("DROP ALL OBJECTS") }
        }

        val outcome = RealtimeCommands(journal = journal).accept(command("event-1"))

        assertEquals("journal_unavailable", assertIs<RealtimeCommandOutcome.Rejected>(outcome).code)
    }

    private fun command(eventId: String, sequence: Long = 1) = RealtimeCommandRequest(
        type = "command",
        eventId = eventId,
        sequence = sequence,
        clientTimestamp = "2026-09-01T10:00:00Z",
        sessionId = "session-1",
        payload = Json.parseToJsonElement("""{"type":"attention"}""").jsonObject,
    )

    private fun dataSource(name: String): JdbcDataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }
}
