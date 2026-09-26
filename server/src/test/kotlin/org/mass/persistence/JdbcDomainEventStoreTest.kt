package org.mass.persistence

import org.h2.jdbcx.JdbcDataSource
import org.mass.domain.*
import org.mass.replication.JournalSchema
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JdbcDomainEventStoreTest {
    @Test
    fun `allocates one per-peer sequence across lifecycle and timer journals`() {
        val store = JdbcDomainEventStore(dataSource("domain-events-sequence"))
        val lifecycle = JdbcSessionLifecycleJournal(store, DomainJournalFixture.ownership, DomainJournalFixture.sessionId)
        val timer = JdbcKerugiTimerJournal(store, DomainJournalFixture.ownership, DomainJournalFixture.sessionId)

        val started = assertIs<SessionLifecycleResult.Applied>(lifecycle.apply(DomainJournalFixture.command("session_started"), DomainJournalFixture.eventId(1)))
        val timerStarted = assertIs<KerugiTimerResult.Applied>(timer.apply(DomainJournalFixture.command("kerugi_timer_started"), DomainJournalFixture.eventId(2)))
        val paused = assertIs<SessionLifecycleResult.Applied>(lifecycle.apply(DomainJournalFixture.command("session_paused"), DomainJournalFixture.eventId(3)))

        assertEquals(listOf(1L, 2L, 3L), listOf(started.event.sequence, timerStarted.event.sequence, paused.event.sequence))
        assertEquals(listOf(started.event, paused.event), lifecycle.events())
        assertEquals(listOf(timerStarted.event), timer.events())
    }

    @Test
    fun `rejects an event ID already used by another command type`() {
        val store = JdbcDomainEventStore(dataSource("domain-events-conflict"))
        val lifecycle = JdbcSessionLifecycleJournal(store, DomainJournalFixture.ownership, DomainJournalFixture.sessionId)
        val timer = JdbcKerugiTimerJournal(store, DomainJournalFixture.ownership, DomainJournalFixture.sessionId)
        assertIs<SessionLifecycleResult.Applied>(lifecycle.apply(DomainJournalFixture.command("session_started"), DomainJournalFixture.eventId(1)))

        assertIs<KerugiTimerResult.Rejected>(timer.apply(DomainJournalFixture.command("kerugi_timer_started"), DomainJournalFixture.eventId(1)))
        assertEquals(emptyList(), timer.events())
    }

    @Test
    fun `upgrades legacy per-type tables into one ordered journal`() {
        DomainJournalFixture.verifyLegacyUpgrade(dataSource("domain-events-upgrade"))
    }

    private fun dataSource(name: String): JdbcDataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }
}

/** Shared by H2 and real PostgreSQL tests so the upgrade path is proven on both. */
internal object DomainJournalFixture {
    val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    val bracketId = BracketId("00000000-0000-4000-8000-000000000002")
    val sessionId = SessionId("00000000-0000-4000-8000-000000000003")
    val ownership = BracketOwnership.assign(bracketId, peerId).start(peerId)

    fun command(type: String) = DomainCommand(
        CompetitionId("00000000-0000-4000-8000-000000000004"), peerId, CourtId("00000000-0000-4000-8000-000000000005"),
        bracketId, sessionId, JudgeId("00000000-0000-4000-8000-000000000006"), DeviceId("00000000-0000-4000-8000-000000000007"),
        EventSource("operator"), "operator", type, "{}",
    )

    fun eventId(number: Int) = EventId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    /**
     * Prepares the V5 pilot schema with per-table sequences that collide across tables, upgrades it and proves that V6
     * keeps every event, orders them by time into one per-peer sequence and continues that sequence.
     */
    fun verifyLegacyUpgrade(dataSource: DataSource) {
        JournalSchema.migrate(dataSource, targetVersion = 5)
        insertLegacy(dataSource, "session_lifecycle_events", eventId(1), "session_started", sequence = 1, at = "2026-09-20T10:00:00Z")
        insertLegacy(dataSource, "kerugi_timer_events", eventId(2), "kerugi_timer_started", sequence = 1, at = "2026-09-20T10:00:01Z")
        insertLegacy(dataSource, "session_lifecycle_events", eventId(3), "session_paused", sequence = 2, at = "2026-09-20T10:00:02Z")

        val store = JdbcDomainEventStore(dataSource)
        val lifecycle = JdbcSessionLifecycleJournal(store, ownership, sessionId)
        val timer = JdbcKerugiTimerJournal(store, ownership, sessionId)

        assertEquals(SessionState.PAUSED, lifecycle.projection().state)
        assertEquals(KerugiTimerState.RUNNING, timer.projection().state)
        assertEquals(listOf(eventId(1) to 1L, eventId(3) to 3L), lifecycle.events().map { it.event.eventId to it.sequence })
        assertEquals(listOf(eventId(2) to 2L), timer.events().map { it.event.eventId to it.sequence })
        val resumed = assertIs<SessionLifecycleResult.Applied>(lifecycle.apply(command("session_resumed"), eventId(4)))
        assertEquals(4L, resumed.event.sequence)
        dataSource.connection.use { connection ->
            val legacyTables = connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { tables ->
                buildList { while (tables.next()) add(tables.getString("TABLE_NAME").lowercase()) }
            }.filter { it.endsWith("_events") && it != "domain_events" && it != "peer_journal_events" }
            assertEquals(emptyList(), legacyTables)
        }
    }

    private fun insertLegacy(dataSource: DataSource, table: String, eventId: EventId, type: String, sequence: Long, at: String) {
        val event = command(type).toEvent(eventId, Instant.parse(at))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO $table (event_id, competition_id, owner_peer_id, court_id, bracket_id, session_id, judge_id, " +
                    "device_id, source, author, occurred_at, event_type, payload, sequence) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                listOf(
                    event.eventId.value, event.competitionId.value, event.peerId.value, event.courtId.value,
                    event.bracketId.value, event.sessionId.value, event.judgeId.value, event.deviceId.value,
                    event.source.value, event.author, event.occurredAt.toString(), event.type, event.payload,
                ).forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.setLong(14, sequence)
                statement.executeUpdate()
            }
        }
    }
}
