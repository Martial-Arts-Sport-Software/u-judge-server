package org.mass.replication

import org.h2.jdbcx.JdbcDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JdbcPeerJournalTest {
    @Test
    fun `journal survives recreation and continues local sequence`() {
        val dataSource = dataSource("recovery")
        val firstProcess = JdbcPeerJournal("court-1", dataSource)
        val firstEvent = firstProcess.append("first-score")

        val restartedProcess = JdbcPeerJournal("court-1", dataSource)
        val secondEvent = restartedProcess.append("second-score")

        assertEquals(listOf(firstEvent, secondEvent), restartedProcess.events)
        assertEquals(listOf("first-score", "second-score"), restartedProcess.projectedPayloads)
        assertEquals(2, secondEvent.sequence)
    }

    @Test
    fun `journal ignores identical delivery and rejects conflicting event data`() {
        val journal = JdbcPeerJournal("court-2", dataSource("idempotency"))
        val event = JournalEvent("event-1", "court-1", 1, "score")

        journal.receive(listOf(event, event))

        assertEquals(setOf(event.id), journal.eventIds)
        assertFailsWith<IllegalArgumentException> {
            journal.receive(listOf(event.copy(payload = "different-score")))
        }
        assertFailsWith<IllegalArgumentException> {
            journal.receive(listOf(event.copy(id = "event-2")))
        }
    }

    @Test
    fun `journal retains an out of order event without projecting it`() {
        val dataSource = dataSource("sequence-gap")
        val firstProcess = JdbcPeerJournal("court-2", dataSource)
        firstProcess.receive(listOf(JournalEvent("event-2", "court-1", 2, "second")))

        val restartedProcess = JdbcPeerJournal("court-2", dataSource)

        assertEquals(setOf(1L), restartedProcess.missingSequences["court-1"])
        assertEquals(emptyList(), restartedProcess.projectedPayloads)
    }

    private fun dataSource(name: String): JdbcDataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }
}
