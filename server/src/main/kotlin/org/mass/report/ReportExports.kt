package org.mass.report

import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.mass.applications.Athlete
import org.mass.protocol.ProtocolDocument
import java.io.ByteArrayOutputStream

/**
 * «Отчёт для организаторов» as XLSX (ADR-012 §3, `REP-001`, `REP-004`): sheets «Соревнование», «Места», «Поединки» and
 * «Оценки», every value a text or a number cell, built offline (`REP-003`).
 */
object XlsxReport {
    val SHEETS = listOf("Соревнование", "Места", "Поединки", "Оценки")

    val PLACES = listOf("Площадка", "Категория", "Система", "Статус", "Место", "Участник", "Город", "Субъект РФ", "Организация", "Тренеры")
    val SESSIONS = listOf(
        "№", "Вид", "Площадка", "Категория", "Круг", "Синий / участник", "Красный", "Состояние", "Счёт синего", "Счёт красного",
        "Gamjeom синего", "Gamjeom красного", "Победитель", "Причина", "Итог", "Оценка панели", "Решения арбитра", "Делитель",
        "Формула", "Судьи", "Кворум", "Окно, мс", "Начало (UTC)", "Завершение (UTC)", "ID сессии",
    )
    val INPUTS = listOf(
        "№", "Вид", "Площадка", "Категория", "ID события", "Последовательность", "Время (UTC)", "Местное время", "Время нажатия (UTC)",
        "Источник", "Автор", "Место судьи", "Судья", "Действие", "Сторона", "Значение", "Статус", "Ссылка", "Причина", "ID сессии",
    )

    fun render(report: CompetitionReport): ByteArray = XSSFWorkbook().use { workbook ->
        write(workbook, report)
        ByteArrayOutputStream().also(workbook::write).toByteArray()
    }

    private fun write(workbook: Workbook, report: CompetitionReport) {
        val competition = report.competition
        val zone = competition.zone
        workbook.sheet(SHEETS[0], listOf("Поле", "Значение"), listOf(
            listOf("ID соревнования", competition.id),
            listOf("Название", competition.name),
            listOf("Дата", ReportText.date(competition.date)),
            listOf("Место проведения", competition.officials.place),
            listOf("Главный судья", competition.officials.chiefJudge),
            listOf("Главный секретарь", competition.officials.chiefSecretary),
            listOf("Сформировано (UTC)", ReportText.utc(competition.generatedAt)),
            listOf("Сформировано (местное время)", ReportText.local(competition.generatedAt, zone)),
            listOf("Часовой пояс", zone.id),
            listOf("Формат", CompetitionReport.FORMAT),
        ))
        workbook.sheet(SHEETS[1], PLACES, report.categories.flatMap { category ->
            val prefix = listOf(category.court, category.category.title, ReportText.format(category.format), ReportText.progress(category.progress))
            if (category.places.isEmpty()) {
                listOf(prefix + listOf("", "", "", "", "", ""))
            } else {
                category.places.map { placed ->
                    val athletes = placed.entry.athletes
                    prefix + listOf(
                        placed.place ?: if (placed.panelDecision) "решение главного судьи" else "",
                        ProtocolDocument.entryName(placed.entry),
                        athletes.distinctJoin(Athlete::city),
                        athletes.distinctJoin(Athlete::region),
                        athletes.distinctJoin { listOf(it.society, it.school).filter(String::isNotBlank).joinToString(", ") },
                        athletes.flatMap(Athlete::coaches).distinct().joinToString(", "),
                    )
                }
            }
        })
        workbook.sheet(SHEETS[2], SESSIONS, report.sessions.map { session ->
            listOf(
                session.number, ReportText.kind(session.kind), session.court, session.categoryTitle, session.round,
                session.blue, session.red.orEmpty(), ReportText.state(session.state),
                session.blueScore ?: "", session.redScore ?: "", session.gamjeomBlue ?: "", session.gamjeomRed ?: "",
                session.winnerLabel.orEmpty(), ReportText.reason(session.reason),
                ReportText.tenths(session.totalTenths), ReportText.tenths(session.aggregateTenths), ReportText.tenths(session.adjustmentTenths),
                session.divisor ?: "", session.formula.orEmpty(), session.judges.joinToString(", "), session.quorum ?: "",
                session.windowMillis ?: "", ReportText.utc(session.startedAt), ReportText.utc(session.completedAt), session.sessionId,
            )
        })
        workbook.sheet(SHEETS[3], INPUTS, report.history.map { row ->
            listOf(
                row.number, ReportText.kind(row.kind), row.court, row.categoryTitle, row.eventId, row.sequence,
                ReportText.utc(row.occurredAt), ReportText.local(row.occurredAt, zone), ReportText.utc(row.clientTimestamp),
                ReportText.source(row.source), row.author, row.seat ?: "", row.judge.orEmpty(), ReportText.action(row.action),
                ReportText.side(row.side), ReportText.value(row), ReportText.status(row.status), row.referenceEventId.orEmpty(),
                row.reason.orEmpty(), row.sessionId,
            )
        })
    }

    private fun List<Athlete>.distinctJoin(field: (Athlete) -> String) =
        map(field).map(String::trim).filter(String::isNotEmpty).distinct().joinToString(", ")

    private fun Workbook.sheet(name: String, header: List<String>, rows: List<List<Any>>) {
        val sheet = createSheet(name)
        val bold = createCellStyle().apply { setFont(createFont().apply { bold = true }) }
        (listOf(header) + rows).forEachIndexed { rowIndex, values ->
            val row = sheet.createRow(rowIndex)
            values.forEachIndexed { column, value ->
                val cell = row.createCell(column)
                when (value) {
                    is Int -> cell.setCellValue(value.toDouble())
                    is Long -> cell.setCellValue(value.toDouble())
                    else -> cell.setCellValue(value.toString())
                }
                if (rowIndex == 0) cell.cellStyle = bold
            }
        }
        sheet.createFreezePane(0, 1)
        header.indices.forEach { sheet.setColumnWidth(it, 18 * 256) }
    }
}

/**
 * The documented CSV of ADR-012 §4 (`REP-002`): UTF-8 without BOM, comma, CRLF, RFC 4180 quoting, a header line; one row
 * per journaled session input with the session context and result; codes instead of words.
 */
object CsvReport {
    val COLUMNS = listOf(
        "competition_id", "generated_at", "court", "category_id", "category", "kind", "number", "round", "session_id", "blue",
        "red", "session_state", "blue_score", "red_score", "winner", "result_reason", "total_tenths", "event_id", "sequence",
        "occurred_at", "occurred_at_local", "client_timestamp", "source", "author", "seat", "judge", "action", "side", "value",
        "status", "reference_event_id", "reason",
    )

    fun render(report: CompetitionReport): ByteArray {
        val competition = report.competition
        val sessions = report.sessions.associateBy(SessionReport::sessionId)
        val lines = listOf(COLUMNS) + report.history.map { row ->
            val session = sessions[row.sessionId]
            listOf(
                competition.id, competition.generatedAt.toString(), row.court.toString(), row.categoryId, row.categoryTitle,
                row.kind.name, row.number.toString(), session?.round?.toString().orEmpty(), row.sessionId, session?.blue.orEmpty(),
                session?.red.orEmpty(), session?.state?.name.orEmpty(), session?.blueScore?.toString().orEmpty(),
                session?.redScore?.toString().orEmpty(), session?.winner?.name.orEmpty(), session?.reason.orEmpty(),
                session?.totalTenths?.toString().orEmpty(), row.eventId, row.sequence.toString(), row.occurredAt.toString(),
                row.occurredAt.atZone(competition.zone).toOffsetDateTime().toString(), row.clientTimestamp?.toString().orEmpty(),
                row.source, row.author, row.seat?.toString().orEmpty(), row.judge.orEmpty(), row.action, row.side?.name.orEmpty(),
                row.value, row.status.name, row.referenceEventId.orEmpty(), row.reason.orEmpty(),
            )
        }
        return lines.joinToString("") { fields -> fields.joinToString(",", transform = ::field) + "\r\n" }.toByteArray(Charsets.UTF_8)
    }

    /** RFC 4180: a field with a comma, a quote, CR or LF is quoted and its quotes doubled. */
    fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\"" else value

    /** Parses a file of [render] back into its rows, for tests and for documentation of the dialect. */
    fun parse(bytes: ByteArray): List<List<String>> {
        val text = String(bytes, Charsets.UTF_8)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                quoted && char == '"' && text.getOrNull(index + 1) == '"' -> field.append('"').also { index++ }
                quoted && char == '"' -> quoted = false
                quoted -> field.append(char)
                char == '"' -> quoted = true
                char == ',' -> row += field.toString().also { field.clear() }
                char == '\r' && text.getOrNull(index + 1) == '\n' -> {
                    row += field.toString().also { field.clear() }
                    rows += row
                    row = mutableListOf()
                    index++
                }
                else -> field.append(char)
            }
            index++
        }
        return rows
    }
}
