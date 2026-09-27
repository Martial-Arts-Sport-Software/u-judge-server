package org.mass.applications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mass.domain.CompetitionId
import org.mass.domain.EventId
import org.mass.domain.EventSource
import org.mass.domain.PeerId
import org.mass.persistence.JdbcDomainEventStore
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The journal payload of an import; [replacesEventId] links a re-import to the import it replaces (`IMP-007`). */
@Serializable
data class ApplicationsImported(
    val competitionId: String,
    val replacesEventId: String?,
    val applications: CompetitionApplications,
)

data class StoredApplicationsImport(val eventId: String, val occurredAt: Instant, val payload: ApplicationsImported)

/** The imported competition shown to the operator: the latest import and how many imports the history keeps. */
data class ImportedCompetition(
    val competitionId: String,
    val eventId: String,
    val importedAt: Instant,
    val applications: CompetitionApplications,
    val importCount: Int,
)

interface CompetitionApplicationsJournal {
    fun append(eventId: String, occurredAt: Instant, payload: ApplicationsImported)

    fun imports(): List<StoredApplicationsImport>

    class InMemory : CompetitionApplicationsJournal {
        private val stored = mutableListOf<StoredApplicationsImport>()

        override fun append(eventId: String, occurredAt: Instant, payload: ApplicationsImported) {
            stored += StoredApplicationsImport(eventId, occurredAt, payload)
        }

        override fun imports(): List<StoredApplicationsImport> = stored.toList()
    }
}

class JdbcCompetitionApplicationsJournal(
    private val store: JdbcDomainEventStore,
    private val peerId: PeerId,
) : CompetitionApplicationsJournal {
    override fun append(eventId: String, occurredAt: Instant, payload: ApplicationsImported) {
        store.appendPeerEvent(
            JdbcDomainEventStore.PeerScopedEvent(
                eventId = EventId(eventId),
                peerId = peerId,
                deviceId = null,
                source = EventSource("operator"),
                author = "operator",
                occurredAt = occurredAt,
                type = if (payload.replacesEventId == null) IMPORTED else REPLACED,
                payload = json.encodeToString(payload),
                competitionId = CompetitionId(payload.competitionId),
            ),
        )
    }

    override fun imports(): List<StoredApplicationsImport> = store.peerEvents(setOf(IMPORTED, REPLACED)).map { stored ->
        StoredApplicationsImport(stored.eventId.value, stored.occurredAt, json.decodeFromString(stored.payload))
    }

    private companion object {
        const val IMPORTED = "competition_applications_imported"
        const val REPLACED = "competition_applications_replaced"
        val json = Json { explicitNulls = false }
    }
}

sealed interface ImportOutcome {
    data class Imported(val competition: ImportedCompetition, val backup: Path?) : ImportOutcome

    /** The same files with the same content are already the current import; nothing was written (`IMP-007`). */
    data object AlreadyImported : ImportOutcome

    /** A competition is already imported; the operator must confirm the replacement or cancel it safely (`IMP-007`). */
    data class ReplaceNeedsConfirmation(val current: ImportedCompetition) : ImportOutcome

    /** The backup or the journal write failed; the previous competition stays current (`IMP-006`, `IMP-008`). */
    data class Failed(val diagnostic: String) : ImportOutcome
}

/**
 * Operator import of competition applications (ADR-005): validates the selected files, backs up the journal and appends the
 * whole competition as one event. The current competition is always rebuilt from the latest import in the journal.
 */
class CompetitionApplicationsService(
    private val journal: CompetitionApplicationsJournal,
    /** Writes the journal backup before an import and returns its path; null when no backup is kept (tests). */
    private val backup: (() -> Path)?,
    private val applicationImport: ApplicationImport = ApplicationImport(),
    private val now: () -> Instant = Instant::now,
) {
    private val mutableCurrent = MutableStateFlow(project(journal.imports()))
    val current: StateFlow<ImportedCompetition?> = mutableCurrent.asStateFlow()

    fun prepare(competitionName: String, competitionDate: LocalDate, files: List<ApplicationFileInput>): ImportPreparation =
        applicationImport.prepare(competitionName, competitionDate, files)

    @Synchronized
    fun import(applications: CompetitionApplications, replaceConfirmed: Boolean): ImportOutcome {
        val current = mutableCurrent.value
        if (current != null) {
            if (current.applications == applications) return ImportOutcome.AlreadyImported
            if (!replaceConfirmed) return ImportOutcome.ReplaceNeedsConfirmation(current)
        }
        val backupPath = try {
            backup?.invoke()
        } catch (exception: Exception) {
            return ImportOutcome.Failed("Резервная копия не создана, импорт отменён: ${exception.message}")
        }
        val payload = ApplicationsImported(
            competitionId = current?.competitionId ?: CompetitionId.new().value,
            replacesEventId = current?.eventId,
            applications = applications,
        )
        try {
            journal.append(UUID.randomUUID().toString(), now(), payload)
        } catch (exception: Exception) {
            return ImportOutcome.Failed("Импорт не сохранён: ${exception.message}")
        }
        val imported = checkNotNull(project(journal.imports()))
        mutableCurrent.value = imported
        return ImportOutcome.Imported(imported, backupPath)
    }

    private fun project(imports: List<StoredApplicationsImport>): ImportedCompetition? = imports.lastOrNull()?.let { latest ->
        ImportedCompetition(
            competitionId = latest.payload.competitionId,
            eventId = latest.eventId,
            importedAt = latest.occurredAt,
            applications = latest.payload.applications,
            importCount = imports.size,
        )
    }

    companion object {
        private val backupTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

        /** The backup file of an import started at [instant]: `<backups>/20260927-101500-123-before-import.jsonl`. */
        fun backupPath(directory: Path, instant: Instant): Path =
            directory.resolve("${backupTimestamp.format(instant)}-before-import.jsonl")
    }
}
