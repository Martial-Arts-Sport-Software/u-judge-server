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

/** Journal payloads of the applications history (ADR-005); the journal only grows, a reset is an event too. */
sealed interface ApplicationsEvent {
    val competitionId: String

    /**
     * An import. [replacesEventId] links a re-import to the import it replaces (`IMP-007`); [restoredFromEventId] marks an
     * earlier import the operator made current again.
     */
    @Serializable
    data class Imported(
        override val competitionId: String,
        val replacesEventId: String?,
        val applications: CompetitionApplications,
        val restoredFromEventId: String? = null,
    ) : ApplicationsEvent

    /** The operator reset all imports: no competition is current until the next import. */
    @Serializable
    data class Cleared(override val competitionId: String, val clearedEventId: String) : ApplicationsEvent
}

data class StoredApplicationsEvent(val eventId: String, val occurredAt: Instant, val event: ApplicationsEvent)

/** One press of «Загрузить» (or a restore) that changed the competition. */
data class ImportRecord(
    val eventId: String,
    val importedAt: Instant,
    val competitionId: String,
    val applications: CompetitionApplications,
    val restoredFromEventId: String?,
)

/** The imports in journal order, newest last, and the one that is current (null after a reset or before any import). */
data class ApplicationsHistory(val imports: List<ImportRecord>, val currentEventId: String?) {
    val current: ImportRecord?
        get() = imports.firstOrNull { it.eventId == currentEventId }

    companion object {
        fun of(events: List<StoredApplicationsEvent>): ApplicationsHistory {
            val imports = mutableListOf<ImportRecord>()
            var currentEventId: String? = null
            events.forEach { stored ->
                when (val event = stored.event) {
                    is ApplicationsEvent.Imported -> {
                        imports += ImportRecord(
                            stored.eventId,
                            stored.occurredAt,
                            event.competitionId,
                            event.applications,
                            event.restoredFromEventId,
                        )
                        currentEventId = stored.eventId
                    }
                    is ApplicationsEvent.Cleared -> currentEventId = null
                }
            }
            return ApplicationsHistory(imports, currentEventId)
        }
    }
}

interface CompetitionApplicationsJournal {
    fun append(eventId: String, occurredAt: Instant, event: ApplicationsEvent)

    fun events(): List<StoredApplicationsEvent>

    class InMemory : CompetitionApplicationsJournal {
        private val stored = mutableListOf<StoredApplicationsEvent>()

        override fun append(eventId: String, occurredAt: Instant, event: ApplicationsEvent) {
            stored += StoredApplicationsEvent(eventId, occurredAt, event)
        }

        override fun events(): List<StoredApplicationsEvent> = stored.toList()
    }
}

class JdbcCompetitionApplicationsJournal(
    private val store: JdbcDomainEventStore,
    private val peerId: PeerId,
) : CompetitionApplicationsJournal {
    override fun append(eventId: String, occurredAt: Instant, event: ApplicationsEvent) {
        val (type, payload) = when (event) {
            is ApplicationsEvent.Imported -> when {
                event.restoredFromEventId != null -> RESTORED
                event.replacesEventId != null -> REPLACED
                else -> IMPORTED
            } to json.encodeToString(event)
            is ApplicationsEvent.Cleared -> CLEARED to json.encodeToString(event)
        }
        store.appendPeerEvent(
            JdbcDomainEventStore.PeerScopedEvent(
                eventId = EventId(eventId),
                peerId = peerId,
                deviceId = null,
                source = EventSource("operator"),
                author = "operator",
                occurredAt = occurredAt,
                type = type,
                payload = payload,
                competitionId = CompetitionId(event.competitionId),
            ),
        )
    }

    override fun events(): List<StoredApplicationsEvent> =
        store.peerEvents(setOf(IMPORTED, REPLACED, RESTORED, CLEARED)).map { stored ->
            val event: ApplicationsEvent = if (stored.type == CLEARED) {
                json.decodeFromString<ApplicationsEvent.Cleared>(stored.payload)
            } else {
                json.decodeFromString<ApplicationsEvent.Imported>(stored.payload)
            }
            StoredApplicationsEvent(stored.eventId.value, stored.occurredAt, event)
        }

    private companion object {
        const val IMPORTED = "competition_applications_imported"
        const val REPLACED = "competition_applications_replaced"
        const val RESTORED = "competition_applications_restored"
        const val CLEARED = "competition_applications_cleared"
        val json = Json { explicitNulls = false }
    }
}

sealed interface ImportOutcome {
    data class Imported(val record: ImportRecord, val backup: Path?) : ImportOutcome

    /** The same applications are already current; nothing was written, so the history has no duplicates (`IMP-007`). */
    data object AlreadyImported : ImportOutcome

    /** A competition is already current; the operator must confirm the replacement or cancel it safely (`IMP-007`). */
    data class ReplaceNeedsConfirmation(val current: ImportRecord) : ImportOutcome

    /** The backup or the journal write failed; the previous state stays current (`IMP-006`, `IMP-008`). */
    data class Failed(val diagnostic: String) : ImportOutcome
}

sealed interface ClearOutcome {
    data class Cleared(val backup: Path?) : ClearOutcome

    data object NothingToClear : ClearOutcome

    data class Failed(val diagnostic: String) : ClearOutcome
}

/**
 * Operator import of competition applications (ADR-005): validates the selected files, backs up the journal and appends the
 * whole competition as one event. The history keeps every import; the operator can make an earlier one current again or
 * reset them all, each as a new journal event after a backup.
 */
class CompetitionApplicationsService(
    private val journal: CompetitionApplicationsJournal,
    /** Writes the journal backup before a change and returns its path; null when no backup is kept (tests). */
    private val backup: (() -> Path)?,
    private val applicationImport: ApplicationImport = ApplicationImport(),
    private val now: () -> Instant = Instant::now,
) {
    private val mutableHistory = MutableStateFlow(ApplicationsHistory.of(journal.events()))
    val history: StateFlow<ApplicationsHistory> = mutableHistory.asStateFlow()

    fun prepare(competitionName: String, competitionDate: LocalDate, files: List<ApplicationFileInput>): ImportPreparation =
        applicationImport.prepare(competitionName, competitionDate, files)

    @Synchronized
    fun import(applications: CompetitionApplications, replaceConfirmed: Boolean): ImportOutcome =
        append(applications, replaceConfirmed, restoredFromEventId = null)

    /** Makes the import [eventId] of the history current again, as a new import that records where it came from. */
    @Synchronized
    fun restore(eventId: String, replaceConfirmed: Boolean): ImportOutcome {
        val record = mutableHistory.value.imports.firstOrNull { it.eventId == eventId }
            ?: return ImportOutcome.Failed("Загрузка не найдена в истории")
        return append(record.applications, replaceConfirmed, restoredFromEventId = eventId)
    }

    /** Resets all imports: nothing is current afterwards, the history and the backup keep every import. */
    @Synchronized
    fun clear(): ClearOutcome {
        val current = mutableHistory.value.current ?: return ClearOutcome.NothingToClear
        val backupPath = try {
            backup?.invoke()
        } catch (exception: Exception) {
            return ClearOutcome.Failed("Резервная копия не создана, сброс отменён: ${exception.message}")
        }
        return try {
            journal.append(UUID.randomUUID().toString(), now(), ApplicationsEvent.Cleared(current.competitionId, current.eventId))
            mutableHistory.value = ApplicationsHistory.of(journal.events())
            ClearOutcome.Cleared(backupPath)
        } catch (exception: Exception) {
            ClearOutcome.Failed("Сброс не сохранён: ${exception.message}")
        }
    }

    private fun append(applications: CompetitionApplications, replaceConfirmed: Boolean, restoredFromEventId: String?): ImportOutcome {
        val current = mutableHistory.value.current
        if (current != null) {
            if (current.applications == applications) return ImportOutcome.AlreadyImported
            if (!replaceConfirmed) return ImportOutcome.ReplaceNeedsConfirmation(current)
        }
        val backupPath = try {
            backup?.invoke()
        } catch (exception: Exception) {
            return ImportOutcome.Failed("Резервная копия не создана, загрузка отменена: ${exception.message}")
        }
        val eventId = UUID.randomUUID().toString()
        val event = ApplicationsEvent.Imported(
            competitionId = current?.competitionId ?: CompetitionId.new().value,
            replacesEventId = current?.eventId,
            applications = applications,
            restoredFromEventId = restoredFromEventId,
        )
        try {
            journal.append(eventId, now(), event)
        } catch (exception: Exception) {
            return ImportOutcome.Failed("Загрузка не сохранена: ${exception.message}")
        }
        val history = ApplicationsHistory.of(journal.events())
        mutableHistory.value = history
        return ImportOutcome.Imported(checkNotNull(history.current), backupPath)
    }

    companion object {
        private val backupTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

        /** The backup file of a change started at [instant]: `<backups>/20260927-101500-123-before-import.jsonl`. */
        fun backupPath(directory: Path, instant: Instant): Path =
            directory.resolve("${backupTimestamp.format(instant)}-before-import.jsonl")
    }
}
