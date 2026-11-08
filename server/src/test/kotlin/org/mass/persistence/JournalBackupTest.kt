package org.mass.persistence

import org.h2.jdbcx.JdbcDataSource
import org.mass.domain.EventId
import org.mass.domain.EventSource
import org.mass.domain.PeerId
import org.mass.replication.JournalSchema
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readLines
import kotlin.io.path.writeLines
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `u-judge-backup` file of ADR-012 §6 and the verified restore of §7, including every refusal. */
class JournalBackupTest {
    private val root = createTempDirectory()
    private val createdAt = Instant.parse("2026-11-08T10:15:00.123Z")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a backup restores the same rows and peer into an empty journal and verifies them`() {
        val source = dataSource()
        val peerId = LocalPeerIdentity.loadOrCreate(source)
        val store = JdbcDomainEventStore(source)
        appendEvents(store, peerId, 3, payload = "{\"name\":\"Иванов, \\\"Пётр\\\"\"}")
        val file = root.resolve(JournalBackups.fileName(createdAt, JournalBackups.MANUAL))

        val header = store.exportJournal(file, JournalBackups.MANUAL, createdAt)

        assertEquals(JournalBackup.FORMAT, header.format)
        assertEquals(JournalSchema.latestVersion, header.schema)
        assertEquals(peerId.value, header.peerId)
        assertEquals(3, header.events)
        assertEquals("20261108-101500-123-manual.jsonl", file.fileName.toString())
        assertEquals(4, file.readLines().size)
        val backup = assertIs<BackupInspection.Valid>(JournalBackup.inspect(file)).backup
        assertEquals(header, backup.header)

        val target = dataSource()
        val restored = assertIs<JournalRestore.Restored>(JournalBackup.restore(backup, target, Instant.now()))
        assertEquals(3, restored.events)
        assertEquals(JournalBackup.checksum(source), JournalBackup.checksum(target))
        assertEquals(header.sha256, restored.sha256)
        assertEquals(peerId, LocalPeerIdentity.loadOrCreate(target))
        // The restored journal continues the peer's sequence (`SYS-008`).
        assertEquals(4L, JdbcDomainEventStore(target).appendPeerEvent(event(peerId, "after_restore")))
    }

    @Test
    fun `the restore refuses a journal that is not empty and writes nothing`() {
        val source = dataSource()
        val peerId = LocalPeerIdentity.loadOrCreate(source)
        appendEvents(JdbcDomainEventStore(source), peerId, 2)
        val file = root.resolve("backup.jsonl")
        JdbcDomainEventStore(source).exportJournal(file)
        val backup = assertIs<BackupInspection.Valid>(JournalBackup.inspect(file)).backup
        val before = JournalBackup.checksum(source)

        assertEquals(JournalRestore.Failed(JournalBackup.TARGET_NOT_EMPTY), JournalBackup.restore(backup, source, Instant.now()))
        assertEquals(before, JournalBackup.checksum(source))
    }

    @Test
    fun `a damaged or foreign file is refused before any database is touched`() {
        val source = dataSource()
        val peerId = LocalPeerIdentity.loadOrCreate(source)
        appendEvents(JdbcDomainEventStore(source), peerId, 2)
        val file = root.resolve("good.jsonl")
        JdbcDomainEventStore(source).exportJournal(file)
        val lines = file.readLines()

        fun variant(name: String, content: List<String>): Path = root.resolve(name).also { it.writeLines(content) }
        fun code(path: Path) = assertIs<BackupInspection.Invalid>(JournalBackup.inspect(path)).code

        assertEquals(JournalBackup.CHECKSUM_MISMATCH, code(variant("tampered.jsonl", listOf(lines[0], lines[1].replace("\"author\":\"operator\"", "\"author\":\"someone\""), lines[2]))))
        assertEquals(JournalBackup.COUNT_MISMATCH, code(variant("short.jsonl", lines.dropLast(1))))
        assertEquals(JournalBackup.UNKNOWN_FORMAT, code(variant("foreign.jsonl", listOf(lines[0].replace(JournalBackup.FORMAT, "other-backup")) + lines.drop(1))))
        assertEquals(JournalBackup.UNSUPPORTED_VERSION, code(variant("version.jsonl", listOf(lines[0].replace("\"version\":1", "\"version\":2")) + lines.drop(1))))
        assertEquals(
            JournalBackup.NEWER_SCHEMA,
            code(variant("newer.jsonl", listOf(lines[0].replace("\"schema\":${JournalSchema.latestVersion}", "\"schema\":${JournalSchema.latestVersion + 1}")) + lines.drop(1))),
        )
        assertEquals(JournalBackup.INVALID_ROW, code(variant("row.jsonl", listOf(lines[0], "{\"event_id\":\"x\"}", lines[2]))))
        assertEquals(JournalBackup.INVALID_ROW, code(variant("text.jsonl", listOf(lines[0], "not json", lines[2]))))
        assertEquals(JournalBackup.DUPLICATE_EVENT, code(variant("duplicate.jsonl", listOf(lines[0].replace("\"events\":2", "\"events\":3"), lines[1], lines[1], lines[2]))))
        assertEquals(JournalBackup.EMPTY, code(root.resolve("empty.jsonl").also { it.writeText("") }))
        assertEquals(JournalBackup.UNREADABLE, code(root.resolve("missing.jsonl")))
        assertEquals(JournalBackup.UNREADABLE, code(root.resolve("binary.jsonl").also { Files.write(it, byteArrayOf(0xC3.toByte(), 0x28)) }))
    }

    @Test
    fun `an I4a journal export without a header is restored for its single peer`() {
        val source = dataSource()
        val peerId = LocalPeerIdentity.loadOrCreate(source)
        appendEvents(JdbcDomainEventStore(source), peerId, 2)
        val file = root.resolve("legacy.jsonl")
        JdbcDomainEventStore(source).exportJournal(file)
        // An I4a file is the rows alone, with the columns in table order.
        file.writeLines(file.readLines().drop(1))

        val backup = assertIs<BackupInspection.Valid>(JournalBackup.inspect(file)).backup
        assertTrue(backup.legacy)
        assertEquals(peerId.value, backup.header.peerId)
        val target = dataSource()
        assertIs<JournalRestore.Restored>(JournalBackup.restore(backup, target, Instant.now()))
        assertEquals(JournalBackup.checksum(source), JournalBackup.checksum(target))
        assertEquals(peerId, LocalPeerIdentity.loadOrCreate(target))
    }

    @Test
    fun `the backup directory lists newest first, deletes only its own files and records failures`() {
        val source = dataSource()
        val peerId = LocalPeerIdentity.loadOrCreate(source)
        appendEvents(JdbcDomainEventStore(source), peerId, 1)
        var clock = createdAt
        val backups = JournalBackups(root.resolve("backups"), source) { clock }

        val first = backups.create(JournalBackups.BEFORE_IMPORT)
        val same = backups.create(JournalBackups.BEFORE_IMPORT)
        clock = clock.plusSeconds(60)
        val second = backups.create(JournalBackups.AFTER_SESSION)

        assertEquals(listOf(second, same, first).map { it.fileName.toString() }, backups.list().map(BackupEntry::name))
        assertEquals("20261108-101500-123-before-import-2.jsonl", same.fileName.toString())
        assertEquals(JournalBackups.AFTER_SESSION, backups.list().first().header!!.reason)
        assertEquals(second, backups.status.value.lastPath)
        assertNull(backups.status.value.failure)
        val outside = root.resolve("elsewhere.jsonl").also { it.writeText("{}") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { backups.delete(outside) }
        assertTrue(backups.delete(first))
        assertEquals(2, backups.list().size)
        val copy = root.resolve("usb/copy.jsonl")
        backups.copy(second, copy)
        assertIs<BackupInspection.Valid>(JournalBackup.inspect(copy))

        val broken = JournalBackups(root.resolve("backups"), JdbcDataSource().apply { setURL("jdbc:h2:mem:missing;IFEXISTS=TRUE") })
        kotlin.test.assertFails { broken.create(JournalBackups.MANUAL) }
        assertTrue(broken.status.value.failure!!.isNotBlank())
    }

    private fun appendEvents(store: JdbcDomainEventStore, peerId: PeerId, count: Int, payload: String = "{}") {
        repeat(count) { store.appendPeerEvent(event(peerId, "test_event_$it", payload)) }
    }

    private fun event(peerId: PeerId, type: String, payload: String = "{}") = JdbcDomainEventStore.PeerScopedEvent(
        eventId = EventId(UUID.randomUUID().toString()),
        peerId = peerId,
        deviceId = null,
        source = EventSource("operator"),
        author = "operator",
        occurredAt = createdAt,
        type = type,
        payload = payload,
    )

    private fun dataSource() = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:backup-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }
}
