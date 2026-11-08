package org.mass.persistence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

/** One backup file of the backup directory as the desktop lists it; [header] is null for an I4a file without one. */
data class BackupEntry(val path: Path, val header: BackupHeader?, val sizeBytes: Long) {
    val name: String
        get() = path.fileName.toString()
}

/** The latest automatic or manual backup, or why it failed; the operator sees a failure (`UI-007`). */
data class BackupStatus(val lastPath: Path? = null, val lastAt: Instant? = null, val failure: String? = null)

/**
 * The backup directory of a peer (ADR-012 §6): `<backups>/<UTC timestamp>-<reason>.jsonl`, written before imports and
 * restores, after completed bouts and on demand, and kept until the operator deletes one (`BAK-003`).
 */
class JournalBackups(
    val directory: Path,
    private val dataSource: DataSource,
    private val now: () -> Instant = Instant::now,
) {
    private val mutableStatus = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = mutableStatus.asStateFlow()

    /** Writes a backup of the whole journal; throws when it cannot be written, so the change it guards does not happen. */
    @Synchronized
    fun create(reason: String): Path {
        val createdAt = now()
        var target = directory.resolve(fileName(createdAt, reason))
        var attempt = 1
        while (Files.exists(target)) target = directory.resolve(fileName(createdAt, "$reason-${++attempt}"))
        return try {
            JournalBackup.write(dataSource, target, reason, createdAt)
            mutableStatus.value = BackupStatus(target, createdAt, failure = null)
            target
        } catch (exception: Exception) {
            mutableStatus.value = mutableStatus.value.copy(failure = exception.message ?: exception::class.simpleName.orEmpty())
            throw exception
        }
    }

    /** Every backup file of the directory, newest first; the header line is read, the rows are not. */
    fun list(): List<BackupEntry> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }.toList()
        }.map { path ->
            val header = runCatching {
                Files.newBufferedReader(path).use { it.readLine() }?.let { line ->
                    json.decodeFromString(BackupHeader.serializer(), line).takeIf { it.format == JournalBackup.FORMAT }
                }
            }.getOrNull()
            BackupEntry(path, header, runCatching { Files.size(path) }.getOrDefault(0))
        }.sortedByDescending { it.name.removeSuffix(".jsonl") }
    }

    /** Deletes a backup of this directory after the operator confirmed it (`BAK-003`); other paths are refused. */
    fun delete(path: Path): Boolean {
        val normalised = path.toAbsolutePath().normalize()
        require(normalised.parent == directory.toAbsolutePath().normalize()) { "Only a file of the backup directory is deleted" }
        return Files.deleteIfExists(normalised)
    }

    /** Copies a backup to a path the operator chose, for example a USB stick for a clean computer (ADR-012 §6). */
    fun copy(path: Path, target: Path) {
        Files.createDirectories(target.toAbsolutePath().parent)
        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Records a failure of an automatic backup that was not thrown to a caller. */
    fun failed(diagnostic: String) {
        mutableStatus.value = mutableStatus.value.copy(failure = diagnostic)
    }

    companion object {
        const val BEFORE_IMPORT = "before-import"
        const val BEFORE_RESTORE = "before-restore"
        const val AFTER_SESSION = "after-session"
        const val MANUAL = "manual"

        private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
        private val json = Json { ignoreUnknownKeys = true }

        /** `20261108-101500-123-after-session.jsonl`: sorts by time and names the reason. */
        fun fileName(instant: Instant, reason: String) = "${timestamp.format(instant)}-$reason.jsonl"
    }
}
