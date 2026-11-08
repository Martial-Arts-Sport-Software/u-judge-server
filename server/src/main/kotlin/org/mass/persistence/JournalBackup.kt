package org.mass.persistence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.mass.replication.JournalSchema
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Types
import java.time.Instant
import javax.sql.DataSource

/** Line 1 of a backup file (ADR-012 §6): what the rows are, whose they are and how to check them. */
@Serializable
data class BackupHeader(
    val format: String = JournalBackup.FORMAT,
    val version: Int = JournalBackup.VERSION,
    /** The highest migration of the source journal; a newer one cannot be restored by this build. */
    val schema: Int,
    @SerialName("peer_id") val peerId: String,
    @SerialName("created_at") val createdAt: String,
    val reason: String,
    val events: Int,
    /** Hex SHA-256 of the row lines, each followed by `\n`. */
    val sha256: String,
)

/** A checked backup file ready to restore; [legacy] is an I4a journal export without a header or checksum. */
data class BackupFile(val path: Path, val header: BackupHeader, val legacy: Boolean, internal val rows: List<JsonObject>)

sealed interface BackupInspection {
    data class Valid(val backup: BackupFile) : BackupInspection

    /** [code] is one of the `JournalBackup` rejection codes; the file is not restored. */
    data class Invalid(val code: String) : BackupInspection
}

sealed interface JournalRestore {
    data class Restored(val events: Int, val sha256: String) : JournalRestore

    data class Failed(val code: String, val diagnostic: String = code) : JournalRestore
}

/**
 * The logical backup of a peer (ADR-012 §6-§7): every `domain_events` row in journal order with the peer identity, as JSON
 * Lines under a header with the row count and a SHA-256. Since every projection is rebuilt from the journal, restoring the
 * rows into an empty database restores the competition.
 */
object JournalBackup {
    const val FORMAT = "u-judge-backup"
    const val VERSION = 1

    const val UNREADABLE = "unreadable"
    const val EMPTY = "empty"
    const val UNKNOWN_FORMAT = "unknown_format"
    const val UNSUPPORTED_VERSION = "unsupported_version"
    const val NEWER_SCHEMA = "newer_schema"
    const val INVALID_ROW = "invalid_row"
    const val COUNT_MISMATCH = "count_mismatch"
    const val CHECKSUM_MISMATCH = "checksum_mismatch"
    const val DUPLICATE_EVENT = "duplicate_event"
    const val UNKNOWN_PEER = "unknown_peer"
    const val TARGET_NOT_EMPTY = "target_not_empty"
    const val VERIFICATION_FAILED = "verification_failed"
    const val WRITE_FAILED = "write_failed"

    /** The `domain_events` columns in a fixed order, so the same rows always give the same lines and checksum. */
    val COLUMNS = listOf(
        "event_id", "owner_peer_id", "sequence", "competition_id", "court_id", "bracket_id", "session_id", "judge_id",
        "device_id", "source", "author", "occurred_at", "event_type", "payload",
    )
    private const val NUMERIC_COLUMN = "sequence"

    private val json = Json { encodeDefaults = true }

    /**
     * Writes the journal of [dataSource] to [target] (a temporary file moved into place, so a crash leaves no half file) and
     * returns its header. All rows are read in one transaction, so the file is one consistent state of the journal.
     */
    fun write(dataSource: DataSource, target: Path, reason: String, createdAt: Instant): BackupHeader {
        val (peerId, schema, lines) = dataSource.connection.use { connection ->
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.autoCommit = false
            try {
                Triple(localPeer(connection), schema(connection), rowLines(connection)).also { connection.commit() }
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
        val header = BackupHeader(
            schema = schema,
            peerId = peerId ?: lines.firstOrNull()?.let { ownerOf(it) }.orEmpty(),
            createdAt = createdAt.toString(),
            reason = reason,
            events = lines.size,
            sha256 = sha256(lines),
        )
        Files.createDirectories(target.toAbsolutePath().parent)
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        Files.newBufferedWriter(temporary).use { writer ->
            writer.write(json.encodeToString(BackupHeader.serializer(), header))
            writer.write("\n")
            lines.forEach { line ->
                writer.write(line)
                writer.write("\n")
            }
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return header
    }

    /** Reads and checks [file] completely without touching any database (ADR-012 §7.1). */
    fun inspect(file: Path): BackupInspection {
        val lines = try {
            Files.readAllLines(file).filter(String::isNotBlank)
        } catch (_: IOException) {
            // Also a file that is not UTF-8 text (`MalformedInputException`).
            return BackupInspection.Invalid(UNREADABLE)
        }
        if (lines.isEmpty()) return BackupInspection.Invalid(EMPTY)
        val first = parse(lines.first()) ?: return BackupInspection.Invalid(UNKNOWN_FORMAT)
        val legacy = "format" !in first
        val rowLines = if (legacy) lines else lines.drop(1)
        val rows = rowLines.map { line -> parse(line)?.takeIf(::validRow) ?: return BackupInspection.Invalid(INVALID_ROW) }
        val canonical = rows.map(::canonical)
        if (rows.map { it.string("event_id") }.toSet().size != rows.size ||
            rows.map { it.string("owner_peer_id") to it.long() }.toSet().size != rows.size
        ) {
            return BackupInspection.Invalid(DUPLICATE_EVENT)
        }
        val header = if (legacy) {
            val owner = rows.map { it.string("owner_peer_id") }.distinct().singleOrNull()
                ?: return BackupInspection.Invalid(if (rows.isEmpty()) EMPTY else UNKNOWN_PEER)
            BackupHeader(
                schema = JournalSchema.latestVersion,
                peerId = owner.orEmpty(),
                createdAt = runCatching { Files.getLastModifiedTime(file).toInstant().toString() }.getOrDefault(""),
                reason = "legacy",
                events = rows.size,
                sha256 = sha256(canonical),
            )
        } else {
            val header = runCatching { json.decodeFromString(BackupHeader.serializer(), lines.first()) }.getOrNull()
                ?: return BackupInspection.Invalid(UNKNOWN_FORMAT)
            when {
                header.format != FORMAT -> return BackupInspection.Invalid(UNKNOWN_FORMAT)
                header.version != VERSION -> return BackupInspection.Invalid(UNSUPPORTED_VERSION)
                header.schema > JournalSchema.latestVersion -> return BackupInspection.Invalid(NEWER_SCHEMA)
                header.events != rows.size -> return BackupInspection.Invalid(COUNT_MISMATCH)
                header.sha256 != sha256(rowLines) -> return BackupInspection.Invalid(CHECKSUM_MISMATCH)
                header.peerId.isBlank() && rows.isNotEmpty() -> return BackupInspection.Invalid(UNKNOWN_PEER)
            }
            header
        }
        return BackupInspection.Valid(BackupFile(file, header, legacy, rows))
    }

    /**
     * Restores [backup] into the empty journal of [target]: migrates it, inserts the peer identity and every row in one
     * transaction and then reads the rows back with a new connection; they must give the backup's checksum (ADR-012 §7.2).
     * A non-empty target is refused, so a restore never overwrites or deletes journal rows (`AUD-001`).
     */
    fun restore(backup: BackupFile, target: DataSource, restoredAt: Instant): JournalRestore {
        try {
            JournalSchema.migrate(target)
            target.connection.use { connection ->
                if (count(connection, "domain_events") > 0 || count(connection, "local_peer") > 0) {
                    return JournalRestore.Failed(TARGET_NOT_EMPTY)
                }
                connection.autoCommit = false
                try {
                    // An empty journal without a peer leaves the identity to the next start (`LocalPeerIdentity`).
                    if (backup.header.peerId.isNotBlank()) {
                        connection.prepareStatement("INSERT INTO local_peer (peer_id, created_at) VALUES (?, ?)").use { statement ->
                            statement.setString(1, backup.header.peerId)
                            statement.setString(2, restoredAt.toString())
                            statement.executeUpdate()
                        }
                    }
                    connection.prepareStatement(
                        "INSERT INTO domain_events (${COLUMNS.joinToString()}) VALUES (${COLUMNS.joinToString { "?" }})",
                    ).use { statement ->
                        backup.rows.forEach { row ->
                            COLUMNS.forEachIndexed { index, column ->
                                val value = row[column]?.jsonPrimitive
                                when {
                                    value == null || value is JsonNull ->
                                        statement.setNull(index + 1, if (column == NUMERIC_COLUMN) Types.BIGINT else Types.VARCHAR)
                                    column == NUMERIC_COLUMN -> statement.setLong(index + 1, checkNotNull(value.longOrNull))
                                    else -> statement.setString(index + 1, value.content)
                                }
                            }
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    connection.commit()
                } catch (exception: Exception) {
                    connection.rollback()
                    throw exception
                } finally {
                    connection.autoCommit = true
                }
            }
        } catch (exception: Exception) {
            return JournalRestore.Failed(WRITE_FAILED, "Backup rows could not be written: ${exception.message}")
        }
        val (peer, lines) = target.connection.use { connection -> localPeer(connection) to rowLines(connection) }
        val expected = sha256(backup.rows.map(::canonical))
        val actual = sha256(lines)
        return if (lines.size == backup.header.events && actual == expected && peer == backup.header.peerId.ifBlank { null }) {
            JournalRestore.Restored(lines.size, actual)
        } else {
            JournalRestore.Failed(VERIFICATION_FAILED, "Restored journal differs from the backup")
        }
    }

    /** The checksum of the journal as it is now, the same value a backup of it carries. */
    fun checksum(dataSource: DataSource): String = dataSource.connection.use { sha256(rowLines(it)) }

    private fun rowLines(connection: Connection): List<String> = connection.prepareStatement(
        "SELECT ${COLUMNS.joinToString()} FROM domain_events ORDER BY owner_peer_id, sequence",
    ).use { statement ->
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) {
                    val row = buildJsonObject {
                        COLUMNS.forEach { column ->
                            put(column, if (column == NUMERIC_COLUMN) JsonPrimitive(result.getLong(column)) else JsonPrimitive(result.getString(column)))
                        }
                    }
                    add(row.toString())
                }
            }
        }
    }

    /** A row in the canonical column order, so a legacy row with another order gives the same line as after a restore. */
    private fun canonical(row: JsonObject): String = buildJsonObject {
        COLUMNS.forEach { column -> put(column, row.getValue(column)) }
    }.toString()

    private fun validRow(row: JsonObject): Boolean =
        row.keys == COLUMNS.toSet() &&
            row.string("event_id") != null && row.string("owner_peer_id") != null && (row.long() ?: 0) > 0 &&
            listOf("source", "author", "occurred_at", "event_type", "payload").all { row.string(it) != null }

    private fun JsonObject.string(column: String): String? = (this[column] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(): Long? = (this[NUMERIC_COLUMN] as? JsonPrimitive)?.longOrNull

    private fun parse(line: String): JsonObject? = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()

    private fun ownerOf(line: String): String? = parse(line)?.string("owner_peer_id")

    private fun localPeer(connection: Connection): String? =
        connection.prepareStatement("SELECT peer_id FROM local_peer").use { statement ->
            statement.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    private fun schema(connection: Connection): Int =
        connection.prepareStatement("SELECT MAX(version) FROM u_judge_schema_migrations").use { statement ->
            statement.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
        }

    private fun count(connection: Connection, table: String): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM $table").use { statement ->
            statement.executeQuery().use { it.next(); it.getLong(1) }
        }

    private fun sha256(lines: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        lines.forEach { line -> digest.update("$line\n".toByteArray(Charsets.UTF_8)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
