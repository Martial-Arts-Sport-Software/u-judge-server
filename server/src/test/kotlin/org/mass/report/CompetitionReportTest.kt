package org.mass.report

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.h2.jdbcx.JdbcDataSource
import org.mass.PairingRequests
import org.mass.RealtimeHub
import org.mass.applications.CompetitionApplicationsService
import org.mass.applications.JdbcCompetitionApplicationsJournal
import org.mass.bout.CourtBouts
import org.mass.bout.JdbcBoutEventJournal
import org.mass.bout.KerugiBoutEvents
import org.mass.bout.KerugiBoutService
import org.mass.domain.PeerId
import org.mass.domain.SessionState
import org.mass.draw.CategoryProgress
import org.mass.draw.CompetitionCourtsService
import org.mass.draw.CompetitionDrawService
import org.mass.draw.JdbcCompetitionDrawJournal
import org.mass.performance.TechnicalPerformanceEvents
import org.mass.performance.TechnicalPerformanceService
import org.mass.persistence.BackupInspection
import org.mass.persistence.JdbcDomainEventStore
import org.mass.persistence.JournalBackup
import org.mass.persistence.JournalRestore
import org.mass.persistence.LocalPeerIdentity
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The report, history, XLSX and CSV of ADR-012 over a competition judged through the production services. */
class CompetitionReportTest {
    private val generatedAt = Instant.parse("2026-11-08T15:30:00Z")
    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun `places, sessions and the history explain every result of the competition`() {
        val report = judgedCompetition(h2()).report()

        val places = report.categories.associate { it.category.id to it.places.map { placed -> placed.place to placed.entry.athletes.single().fullName } }
        assertTrue(report.categories.all { it.progress == CategoryProgress.COMPLETED }, report.categories.map { it.progress }.toString())
        assertEquals(listOf(1, 2, 3, 3), places.getValue("kerugi").map { it.first })
        assertEquals(listOf(1, 2), places.getValue("tanbon").map { it.first })
        // The trimmed mean keeps the middle total 60+n of performance n, so the last performance wins.
        assertEquals(listOf(1, 2, 3), places.getValue("hosinsool").map { it.first })
        val hosinsool = report.sessions.filter { it.kind == SessionKind.TECHNICAL }
        assertEquals(listOf(61, 62, 63), hosinsool.map { it.totalTenths })
        assertEquals(hosinsool.maxBy { it.totalTenths!! }.blue.substringBefore(" "), places.getValue("hosinsool").first().second.substringBefore(" "))

        assertEquals(listOf(SessionKind.KERUGI, SessionKind.KERUGI, SessionKind.KERUGI, SessionKind.TANBON), report.sessions.filter { it.kind != SessionKind.TECHNICAL }.map { it.kind }.sorted())
        assertTrue(report.sessions.all { it.state == SessionState.COMPLETED && it.completedAt != null })
        val kerugi = report.sessions.first { it.kind == SessionKind.KERUGI }
        // Blue: one awarded BODY from the coinciding taps; red nothing (the throw was corrected); Gamjeom against red scores for blue.
        assertEquals(2, kerugi.blueScore)
        assertEquals(0, kerugi.redScore)
        assertEquals(1, kerugi.gamjeomRed)
        assertEquals(kerugi.blue, kerugi.winnerLabel)
        assertEquals("final_score", kerugi.reason)
        assertEquals(listOf("1. Судья1", "2. Судья2", "3. Судья3"), kerugi.judges)

        // Every input says why it counted or not (`KER-009`, ADR-012 §1.2), the correction points at its target (`AUD-002`).
        val bout = report.history(HistoryFilter(sessionId = kerugi.sessionId))
        assertTrue(bout.all { it.sessionId == kerugi.sessionId })
        val taps = bout.filter { it.action == KerugiBoutEvents.SCORE_CANDIDATE }
        assertEquals(listOf(HistoryStatus.COUNTED, HistoryStatus.COUNTED, HistoryStatus.NO_QUORUM), taps.map { it.status })
        assertEquals(listOf("Судья1", "Судья2", "Судья3"), taps.map { it.judge })
        assertTrue(taps.all { it.clientTimestamp != null && it.source == KerugiBoutEvents.JUDGE_SOURCE })
        val actions = bout.filter { it.action == KerugiBoutEvents.OPERATOR_ACTION }
        assertEquals(listOf(HistoryStatus.CORRECTED, HistoryStatus.APPLIED), actions.map { it.status })
        assertEquals(listOf("THROW:3", "GAMJEOM:1"), actions.map { it.value })
        assertEquals(actions.first().eventId, bout.single { it.action == KerugiBoutEvents.SCORE_CORRECTION }.referenceEventId)
        assertEquals("final_score", bout.single { it.action == KerugiBoutEvents.SESSION_COMPLETED }.value)

        val tanbon = report.history(HistoryFilter(categoryId = "tanbon")).filter { it.action == KerugiBoutEvents.TANBON_SCORE_CANDIDATE }
        assertEquals(listOf("HEAD", "BODY", "CROSS"), tanbon.map { it.value })
        assertEquals(HistoryStatus.AUDIT_ONLY, tanbon.last().status)
        assertEquals(null, tanbon.last().side)

        val sends = report.history(HistoryFilter(sessionId = hosinsool.first().sessionId)).filter { it.action == TechnicalPerformanceEvents.SCORE_SENT }
        assertEquals(listOf(HistoryStatus.TRIMMED, HistoryStatus.COUNTED, HistoryStatus.TRIMMED), sends.map { it.status })
        assertEquals("T:10,10,10,10;P:8,1,1,1;D:0;J:51", sends.first().value)
        val adjustments = report.history(HistoryFilter(sessionId = hosinsool.first().sessionId)).filter { it.action.startsWith("technical_adjustment") }
        assertEquals(listOf(HistoryStatus.CORRECTED, HistoryStatus.RECORDED), adjustments.map { it.status })
        assertEquals(adjustments.first().eventId, adjustments.last().referenceEventId)
        assertEquals("Ошибка арбитра, повтор", adjustments.last().reason)

        // Filters combine with AND (`AUD-003`).
        val third = report.history(HistoryFilter(judge = "Судья3", court = 1))
        assertTrue(third.isNotEmpty() && third.all { it.judge == "Судья3" && it.court == 1 })
        assertEquals(3, third.count { it.status == HistoryStatus.NO_QUORUM })
        assertEquals(1, report.history(HistoryFilter(judge = "Судья3", action = KerugiBoutEvents.TANBON_SCORE_CANDIDATE)).size)
        assertEquals(emptyList(), report.history(HistoryFilter(court = 2)))
        assertEquals(report.history.size, report.history(HistoryFilter()).size)
        assertEquals(report.history.sortedBy { it.sequence }, report.history)
    }

    @Test
    fun `the XLSX has the competition, places, sessions and every input with UTC and local times`() {
        val report = judgedCompetition(h2()).report()
        val sheets = cells(XlsxReport.render(report))

        assertEquals(XlsxReport.SHEETS, sheets.keys.toList())
        val competition = sheets.getValue("Соревнование").associate { it[0] to it.getOrElse(1) { "" } }
        assertEquals(report.competition.id, competition["ID соревнования"])
        assertEquals("2026-11-08T15:30:00Z", competition["Сформировано (UTC)"])
        assertEquals("2026-11-08 18:30:00.000 +03:00", competition["Сформировано (местное время)"])
        assertEquals("Europe/Moscow", competition["Часовой пояс"])
        assertEquals(CompetitionReport.FORMAT, competition["Формат"])
        assertEquals(XlsxReport.PLACES, sheets.getValue("Места").first())
        assertEquals(4 + 2 + 3, sheets.getValue("Места").size - 1)
        assertEquals(XlsxReport.SESSIONS, sheets.getValue("Поединки").first())
        assertEquals(report.sessions.size, sheets.getValue("Поединки").size - 1)
        val inputs = sheets.getValue("Оценки")
        assertEquals(XlsxReport.INPUTS, inputs.first())
        assertEquals(report.history.size, inputs.size - 1)
        val statuses = inputs.drop(1).map { it[XlsxReport.INPUTS.indexOf("Статус")] }.toSet()
        assertTrue(statuses.containsAll(setOf("засчитан", "нет кворума", "отменён", "применён", "только аудит", "отброшена (мин./макс.)")), statuses.toString())
        val values = inputs.drop(1).map { it[XlsxReport.INPUTS.indexOf("Значение")] }.toSet()
        assertTrue(values.containsAll(setOf("голова", "туловище", "крест", "бросок +3", "техника 1.0 1.0 1.0 1.0; презентация 0.8 0.1 0.1 0.1; вычет 0.0; итог 5.1")), values.toString())
    }

    @Test
    fun `the CSV is documented UTF-8 with comma, CRLF and RFC 4180 quoting`() {
        val report = judgedCompetition(h2()).report()
        val bytes = CsvReport.render(report)

        assertTrue(bytes.size > 3 && !(bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte()), "no BOM")
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.endsWith("\r\n") && text.lines().first() == CsvReport.COLUMNS.joinToString(","))
        val rows = CsvReport.parse(bytes)
        assertEquals(CsvReport.COLUMNS, rows.first())
        assertEquals(report.history.size, rows.size - 1)
        assertTrue(rows.all { it.size == CsvReport.COLUMNS.size })
        val withdrawal = rows.single { it[CsvReport.COLUMNS.indexOf("action")] == TechnicalPerformanceEvents.ADJUSTMENT_WITHDRAWN && it[CsvReport.COLUMNS.indexOf("number")] == report.sessions.first { s -> s.kind == SessionKind.TECHNICAL }.number.toString() }
        assertEquals("Ошибка арбитра, повтор", withdrawal[CsvReport.COLUMNS.indexOf("reason")])
        assertTrue(text.contains("\"Ошибка арбитра, повтор\""))
        assertEquals("\"a \"\"b\"\", c\"", CsvReport.field("a \"b\", c"))
        val first = rows[1]
        assertEquals(report.competition.id, first[0])
        assertEquals("2026-11-08T15:30:00Z", first[1])
        assertTrue(first[CsvReport.COLUMNS.indexOf("occurred_at")].endsWith("Z"))
        assertTrue(first[CsvReport.COLUMNS.indexOf("occurred_at_local")].endsWith("+03:00"))
        assertTrue(rows.drop(1).map { it[CsvReport.COLUMNS.indexOf("status")] }.toSet().containsAll(setOf("COUNTED", "NO_QUORUM", "CORRECTED", "AUDIT_ONLY", "TRIMMED")))
    }

    @Test
    fun `the exports of a peer restored from its backup equal the exports before the backup`() {
        val source = h2()
        val before = judgedCompetition(source).report()
        val file = createTempDirectory().resolve("backup.jsonl")
        JdbcDomainEventStore(source).exportJournal(file)

        val clean = h2()
        val backup = assertIs<BackupInspection.Valid>(JournalBackup.inspect(file)).backup
        assertIs<JournalRestore.Restored>(JournalBackup.restore(backup, clean, Instant.now()))
        val after = Court(clean).report()

        assertEquals(before, after)
        assertEquals(String(CsvReport.render(before)), String(CsvReport.render(after)))
        assertEquals(cells(XlsxReport.render(before)), cells(XlsxReport.render(after)))
        file.parent.toFile().deleteRecursively()
    }

    /** The services of one desktop over [dataSource], as `ServerRuntime` composes them. */
    private inner class Court(dataSource: DataSource) {
        val pairing = PairingRequests()
        private val peerId: PeerId = LocalPeerIdentity.loadOrCreate(dataSource)
        private val store = JdbcDomainEventStore(dataSource)
        val applications = CompetitionApplicationsService(JdbcCompetitionApplicationsJournal(store, peerId), backup = null)
        val draws = CompetitionDrawService(JdbcCompetitionDrawJournal(store, peerId), applications.history, peerId, seeds = { 7L })
        private val journal = JdbcBoutEventJournal(store)
        val hub = RealtimeHub(KerugiBoutService(journal, peerId), TechnicalPerformanceService(journal, peerId))
        val courts = CompetitionCourtsService(draws, peerId) { CourtBouts.progress(hub) }
        val bouts = CourtBouts(hub, draws, courts, pairing)
        val reports = CompetitionReports(applications, courts, hub) { zone }

        fun report(): CompetitionReport = checkNotNull(reports.report(generatedAt))
    }

    private fun judgedCompetition(dataSource: DataSource): Court = Court(dataSource).also { court ->
        CompetitionScenario(court.applications, court.draws, court.bouts, court.pairing).apply {
            importAndDraw()
            connectJudges()
            judgeEverything()
        }
    }

    private fun h2(): DataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:report-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }

    companion object {
        /** Every sheet as rows of cell texts, for comparing two workbooks by content. */
        fun cells(bytes: ByteArray): Map<String, List<List<String>>> = XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
            (0 until workbook.numberOfSheets).associate { index ->
                val sheet = workbook.getSheetAt(index)
                sheet.sheetName to (0..sheet.lastRowNum).map { rowIndex ->
                    val row = sheet.getRow(rowIndex)
                    (0 until (row?.lastCellNum?.toInt() ?: 0)).map { column ->
                        val cell = row.getCell(column)
                        when (cell?.cellType) {
                            CellType.NUMERIC -> cell.numericCellValue.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
                            null -> ""
                            else -> cell.stringCellValue
                        }
                    }
                }
            }
        }
    }
}
