package org.mass.applications

import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** A filled application row; the defaults satisfy every field rule. */
data class AthleteRow(
    val fullName: String = "Иванов Иван Иванович",
    val birthDate: LocalDate? = LocalDate.of(2012, 5, 10),
    val birthDateText: String? = null,
    val weight: String = "до 45 кг",
    val sportQualification: String = "1юн",
    val technicalQualification: String = "5 гып",
    val city: String = "Санкт-Петербург",
    val region: String = "Санкт-Петербург",
    val federalDistrict: String = "СЗФО",
    val society: String = "мин. обр.",
    val school: String = "СК Хапкидо",
    val coaches: String = "Петров П.П., Сидоров С.С.",
    val doctorVisa: String? = null,
)

/**
 * Builds workbooks with the layout of the federation template `df-template-v1`: merged headers, merged section rows, pairs
 * numbered in a merged cell of two rows and group teams closed by a coloured empty row.
 */
class ApplicationWorkbookFixture {
    private val workbook = XSSFWorkbook()
    private val dateStyle = workbook.createCellStyle().apply {
        dataFormat = workbook.createDataFormat().getFormat("dd.mm.yyyy")
    }
    private val separatorStyle = workbook.createCellStyle().apply {
        fillForegroundColor = IndexedColors.LIGHT_GREEN.index
        fillPattern = FillPatternType.SOLID_FOREGROUND
    }
    private val sheets = ApplicationDiscipline.entries.associateWith { discipline ->
        SheetFixture(workbook.createSheet(discipline.sheetName), discipline)
    }

    fun sheet(discipline: ApplicationDiscipline, block: SheetFixture.() -> Unit) = apply { sheets.getValue(discipline).block() }

    fun bytes(): ByteArray = ByteArrayOutputStream().also { workbook.write(it) }.toByteArray()

    inner class SheetFixture(private val sheet: XSSFSheet, private val discipline: ApplicationDiscipline) {
        private val headers = buildList {
            addAll(listOf("№ ", "ФИО спортсмена", "Дата рождения"))
            if (discipline.hasWeight) add("Весовая категория")
            addAll(
                listOf(
                    "Спортивная квалификация", "Техническая квалификация", "Город", "Субъект РФ", "Федеральный округ",
                    "ДСО (ведомство)", "ДЮСШ", "ФИО тренера", "Виза врача",
                ),
            )
        }
        private var nextRow = 2
        private var number = 1

        init {
            val header = sheet.createRow(0)
            sheet.createRow(1)
            headers.forEachIndexed { index, title ->
                header.createCell(index).setCellValue(title)
                sheet.addMergedRegion(CellRangeAddress(0, 1, index, index))
            }
        }

        fun header(column: Int, title: String) {
            sheet.getRow(0).getCell(column).setCellValue(title)
        }

        fun section(title: String) {
            sheet.createRow(nextRow).createCell(0).setCellValue(title)
            sheet.addMergedRegion(CellRangeAddress(nextRow, nextRow, 0, headers.lastIndex))
            nextRow++
        }

        fun athlete(row: AthleteRow = AthleteRow()) = writeAthlete(row, numbered = true)

        /** Two rows sharing one number in a merged cell of column A; [mergeNumber] false breaks the template layout. */
        fun pair(first: AthleteRow = AthleteRow(), second: AthleteRow = AthleteRow("Петров Пётр Петрович"), mergeNumber: Boolean = true) {
            if (mergeNumber) sheet.addMergedRegion(CellRangeAddress(nextRow, nextRow + 1, 0, 0))
            writeAthlete(first, numbered = true)
            writeAthlete(second, numbered = false)
        }

        fun team(members: List<AthleteRow>) {
            members.forEach { writeAthlete(it, numbered = true) }
            separator()
        }

        fun separator() {
            val row = sheet.createRow(nextRow++)
            headers.indices.forEach { row.createCell(it).cellStyle = separatorStyle }
        }

        fun blankRow() {
            sheet.createRow(nextRow++)
        }

        private fun writeAthlete(athlete: AthleteRow, numbered: Boolean) {
            val row = sheet.createRow(nextRow++)
            var column = 0
            fun next(value: String?) {
                val cell = row.createCell(column++)
                if (value != null) cell.setCellValue(value)
            }
            next(if (numbered) (number++).toString() else null)
            next(athlete.fullName)
            val dateCell = row.createCell(column++)
            when {
                athlete.birthDate != null -> {
                    dateCell.setCellValue(athlete.birthDate)
                    dateCell.cellStyle = dateStyle
                }
                athlete.birthDateText != null -> dateCell.setCellValue(athlete.birthDateText)
            }
            if (discipline.hasWeight) next(athlete.weight)
            next(athlete.sportQualification)
            next(athlete.technicalQualification)
            next(athlete.city)
            next(athlete.region)
            next(athlete.federalDistrict)
            next(athlete.society)
            next(athlete.school)
            next(athlete.coaches)
            next(athlete.doctorVisa)
        }
    }

    companion object {
        /** Distinct valid names for generated data sets. */
        fun name(index: Int): String {
            val surnames = listOf("Иванов", "Петров", "Сидоров", "Смирнов", "Кузнецов", "Попов", "Васильев", "Соколов")
            val names = listOf("Иван", "Пётр", "Сергей", "Алексей", "Дмитрий", "Андрей", "Михаил", "Никита")
            val suffix = buildString {
                var rest = index
                do {
                    append("абвгдежзиклмнопрстуфхцчшэюя"[rest % 27])
                    rest /= 27
                } while (rest > 0)
            }
            return "${surnames[index % surnames.size]}-${suffix.replaceFirstChar(Char::uppercaseChar)}ов ${names[index / 8 % names.size]}"
        }
    }
}
