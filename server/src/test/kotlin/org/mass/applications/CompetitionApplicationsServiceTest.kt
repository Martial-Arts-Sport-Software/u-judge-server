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

/** Scenario of I4a: import, duplicate re-import, confirmed replacement, restart and rejection (ADR-005). */
class CompetitionApplicationsServiceTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val date = LocalDate.of(2025, 11, 15)
    private val backups: Path = Files.createTempDirectory("u-judge-backups")

    @Test
    fun `operator imports, re-imports safely and finds the competition after a restart`() {
        val store = JdbcDomainEventStore(dataSource("applications-scenario"))
        val service = service(store)
        assertNull(service.current.value)

        // A broken selection is only a report: nothing is written, no backup is taken.
        val invalid = service.prepare("Первенство", date, listOf(file("broken.xlsx", AthleteRow(coaches = "Петров П.П."))))
        assertEquals("L (ФИО тренера)", assertIs<ImportPreparation.Invalid>(invalid).errors.single().column)
        assertNull(service.current.value)
        assertEquals(emptyList(), Files.list(backups).use { it.toList() })

        // Happy path: backup first, then one journal event.
        val first = valid(service, file("a.xlsx", AthleteRow()))
        val imported = assertIs<ImportOutcome.Imported>(service.import(first, replaceConfirmed = false))
        assertEquals(1, imported.competition.importCount)
        assertEquals(first, service.current.value?.applications)
        assertEquals(emptyList(), imported.backup!!.readLines())

        // The same files again change nothing; other files need confirmation and can be cancelled safely.
        assertEquals(ImportOutcome.AlreadyImported, service.import(first, replaceConfirmed = false))
        val second = valid(service, file("a.xlsx", AthleteRow()), file("b.xlsx", AthleteRow(fullName = "Петров Пётр")))
        val confirmation = assertIs<ImportOutcome.ReplaceNeedsConfirmation>(service.import(second, replaceConfirmed = false))
        assertEquals(imported.competition, confirmation.current)
        assertEquals(first, service.current.value?.applications)

        // Confirmed replacement keeps the competition ID and the history; its backup holds the first import.
        val replaced = assertIs<ImportOutcome.Imported>(service.import(second, replaceConfirmed = true))
        assertEquals(imported.competition.competitionId, replaced.competition.competitionId)
        assertEquals(2, replaced.competition.importCount)
        val backupRows = replaced.backup!!.readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("competition_applications_imported"), backupRows.map { it.getValue("event_type").jsonPrimitive.content })
        assertEquals(imported.competition.eventId, backupRows.single().getValue("event_id").jsonPrimitive.content)

        // Restart: a new service over the same journal restores the latest import.
        val restarted = service(JdbcDomainEventStore(dataSource("applications-scenario")))
        assertEquals(replaced.competition, restarted.current.value)
    }

    @Test
    fun `a failed backup cancels the import and keeps the previous competition`() {
        val journal = CompetitionApplicationsJournal.InMemory()
        val service = CompetitionApplicationsService(journal, backup = { error("disk full") })

        val outcome = service.import(valid(service, file("a.xlsx", AthleteRow())), replaceConfirmed = false)

        assertEquals(ImportOutcome.Failed("Резервная копия не создана, импорт отменён: disk full"), outcome)
        assertEquals(emptyList(), journal.imports())
        assertNull(service.current.value)
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
