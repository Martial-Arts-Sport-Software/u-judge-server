package org.mass.applications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import org.mass.domain.PeerId
import org.mass.persistence.JdbcDomainEventStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scenario of I4a: import, duplicate re-import, confirmed replacement, restore, reset, restart and rejection (ADR-005). */
class CompetitionApplicationsServiceTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val date = LocalDate.of(2025, 11, 15)
    private val backups: Path = Files.createTempDirectory("u-judge-backups")

    @Test
    fun `operator imports, re-imports safely and finds the competition after a restart`() {
        val store = JdbcDomainEventStore(dataSource("applications-scenario"))
        val service = service(store)
        assertNull(service.history.value.current)

        // A broken selection is only a report: nothing is written, no backup is taken.
        val invalid = service.prepare("Первенство", date, listOf(file("broken.xlsx", AthleteRow(coaches = "Петров П.П."))))
        assertEquals("L (ФИО тренера)", assertIs<ImportPreparation.Invalid>(invalid).errors.single().column)
        assertEquals(emptyList(), service.history.value.imports)
        assertEquals(emptyList(), Files.list(backups).use { it.toList() })

        // Happy path: backup first, then one journal event.
        val first = valid(service, file("a.xlsx", AthleteRow()))
        val imported = assertIs<ImportOutcome.Imported>(service.import(first, replaceConfirmed = false))
        assertEquals(first, service.history.value.current?.applications)
        assertEquals(emptyList(), imported.backup!!.readLines())

        // The same files again change nothing; other files need confirmation and can be cancelled safely.
        assertEquals(ImportOutcome.AlreadyImported, service.import(first, replaceConfirmed = false))
        val second = valid(service, file("a.xlsx", AthleteRow()), file("b.xlsx", AthleteRow(fullName = "Петров Пётр")))
        val confirmation = assertIs<ImportOutcome.ReplaceNeedsConfirmation>(service.import(second, replaceConfirmed = false))
        assertEquals(imported.record, confirmation.current)
        assertEquals(1, service.history.value.imports.size)

        // Confirmed replacement keeps the competition ID and the history; its backup holds the first import.
        val replaced = assertIs<ImportOutcome.Imported>(service.import(second, replaceConfirmed = true))
        assertEquals(imported.record.competitionId, replaced.record.competitionId)
        assertEquals(listOf(imported.record, replaced.record), service.history.value.imports)
        val backupRows = replaced.backup!!.readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("competition_applications_imported"), backupRows.map { it.getValue("event_type").jsonPrimitive.content })
        assertEquals(imported.record.eventId, backupRows.single().getValue("event_id").jsonPrimitive.content)

        // Restoring the first import is a new history entry that records its origin.
        val restored = assertIs<ImportOutcome.Imported>(service.restore(imported.record.eventId, replaceConfirmed = true))
        assertEquals(first, restored.record.applications)
        assertEquals(imported.record.eventId, restored.record.restoredFromEventId)
        assertEquals(3, service.history.value.imports.size)

        // Restart: a new service over the same journal restores the history and the current import.
        val restarted = service(JdbcDomainEventStore(dataSource("applications-scenario")))
        assertEquals(service.history.value, restarted.history.value)

        // Reset: nothing is current, the history starts over (the backup keeps it), and the next import is a new competition.
        val reset = assertIs<ClearOutcome.Cleared>(restarted.clear())
        assertEquals(ApplicationsHistory(emptyList(), null), restarted.history.value)
        assertEquals(3, reset.backup!!.readLines().size)
        assertEquals(ClearOutcome.NothingToClear, restarted.clear())
        val afterReset = assertIs<ImportOutcome.Imported>(restarted.import(second, replaceConfirmed = false))
        assertTrue(afterReset.record.competitionId != imported.record.competitionId)
        assertEquals(listOf(afterReset.record), restarted.history.value.imports)
        assertEquals(restarted.history.value, service(JdbcDomainEventStore(dataSource("applications-scenario"))).history.value)
    }

    @Test
    fun `a failed backup cancels the import and keeps the previous competition`() {
        val journal = CompetitionApplicationsJournal.InMemory()
        val service = CompetitionApplicationsService(journal, backup = { error("disk full") })

        val outcome = service.import(valid(service, file("a.xlsx", AthleteRow())), replaceConfirmed = false)

        assertEquals(ImportOutcome.Failed("Резервная копия не создана, загрузка отменена: disk full"), outcome)
        assertEquals(emptyList(), journal.events())
        assertNull(service.history.value.current)
    }

    @Test
    fun `backup file names sort by import time`() {
        val path = CompetitionApplicationsService.backupPath(backups, Instant.parse("2026-09-27T10:15:00.123Z"))
        assertEquals("20260927-101500-123-before-import.jsonl", path.fileName.toString())
        assertTrue(path.startsWith(backups))
    }

    private fun service(store: JdbcDomainEventStore) = CompetitionApplicationsService(
        journal = JdbcCompetitionApplicationsJournal(store, peerId),
        backup = { CompetitionApplicationsService.backupPath(backups, Instant.now()).also(store::exportJournal) },
    )

    private fun valid(service: CompetitionApplicationsService, vararg files: ApplicationFileInput): CompetitionApplications =
        assertIs<ImportPreparation.Valid>(service.prepare("Первенство", date, files.toList())).applications

    private fun file(name: String, athlete: AthleteRow) = ApplicationFileInput(
        name,
        ApplicationWorkbookFixture().sheet(ApplicationDiscipline.KERUGI) {
            section("2013-2011 г.р. Юноши")
            athlete(athlete)
        }.bytes(),
    )

    private fun dataSource(name: String) = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }
}
