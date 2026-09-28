package org.mass.applications

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.Period

/** Athletes read from one sheet section that start together: one athlete, a pair or a group team. */
data class ParsedEntry(
    val discipline: ApplicationDiscipline,
    val weapon: Weapon?,
    val section: AgeSection,
    val weight: String?,
    val athletes: List<Athlete>,
    val sources: List<SourceRow>,
)

data class FileReadResult(
    val file: ApplicationFile,
    val sections: List<AgeSection>,
    val entries: List<ParsedEntry>,
    val errors: List<ApplicationError>,
)

/**
 * Reads one application file of the federation template `df-template-v1` (ADR-005). Any layout other than the template is
 * rejected rather than guessed; every field rule violation becomes an [ApplicationError] with its cell.
 */
class ApplicationTemplateV1Reader {
    fun read(fileName: String, bytes: ByteArray, competitionDate: LocalDate): FileReadResult {
        val file = ApplicationFile(fileName, sha256(bytes))
        val workbook = try {
            XSSFWorkbook(ByteArrayInputStream(bytes))
        } catch (exception: Exception) {
            return FileReadResult(file, emptyList(), emptyList(), listOf(ApplicationError(fileName, reason = NOT_XLSX)))
        }
        return workbook.use { SheetsReader(file, it, competitionDate).read() }
    }

    private class SheetsReader(val file: ApplicationFile, val workbook: XSSFWorkbook, val competitionDate: LocalDate) {
        val errors = mutableListOf<ApplicationError>()
        val entries = mutableListOf<ParsedEntry>()
        val sections = mutableListOf<AgeSection>()
        val formatter = DataFormatter(RUSSIAN)

        fun read(): FileReadResult {
            val sheetNames = (0 until workbook.numberOfSheets).map(workbook::getSheetName)
            sheetNames.filter { name -> ApplicationDiscipline.entries.none { it.sheetName.sameAs(name) } }.forEach {
                errors += ApplicationError(file.name, sheet = it, reason = "Лишний лист: в заявке 5 листов по дисциплинам шаблона")
            }
            ApplicationDiscipline.entries.forEach { discipline ->
                val name = sheetNames.firstOrNull { discipline.sheetName.sameAs(it) }
                if (name == null) {
                    errors += ApplicationError(file.name, reason = "Нет листа «${discipline.sheetName}» из шаблона")
                } else {
                    SheetReader(this, discipline, workbook.getSheet(name)).read()
                }
            }
            val classes = sections.map { it.ageClass to (it.oldestBirthYear to it.youngestBirthYear) }.distinct()
            if (classes.size > 1) {
                errors += ApplicationError(
                    file.name,
                    value = sections.map { it.title }.distinct().joinToString("; "),
                    reason = "В одном файле может быть только одна возрастная категория",
                )
            }
            return FileReadResult(file, sections.distinctBy { it.title }, entries, errors)
        }
    }

    private class SheetReader(val parent: SheetsReader, val discipline: ApplicationDiscipline, val sheet: XSSFSheet) {
        val headers = if (discipline.hasWeight) KERUGI_HEADERS else HEADERS
        val column = Columns(discipline.hasWeight)
        val fullWidthRows = sheet.mergedRegions
            .filter { it.firstRow == it.lastRow && it.firstColumn == 0 && it.lastColumn >= headers.lastIndex }
            .map { it.firstRow }
            .toSet()
        val numberRegions = sheet.mergedRegions.filter { it.firstColumn == 0 && it.lastColumn == 0 && it.firstRow >= 2 }

        var weapon: Weapon? = null
        var section: AgeSection? = null
        /** Members read so far; a null athlete is a row with errors, which keeps the count right but drops the entry. */
        val pairs = linkedMapOf<CellRangeAddress, MutableList<Pair<Athlete?, SourceRow>>>()
        val team = mutableListOf<Pair<Athlete?, SourceRow>>()

        fun read() {
            if (!headersMatch()) return
            for (rowIndex in 2..sheet.lastRowNum) {
                when {
                    rowIndex in fullWidthRows -> {
                        closeTeam()
                        readSectionHeader(rowIndex)
                    }
                    isBlank(rowIndex) -> if (discipline.entryKind == EntryKind.GROUP && isColoured(rowIndex)) closeTeam()
                    else -> readParticipant(rowIndex)
                }
            }
            closeTeam()
            closePairs()
        }

        private fun headersMatch(): Boolean {
            val mismatches = headers.mapIndexedNotNull { index, expected ->
                val actual = text(0, index)
                if (actual.sameAs(expected)) null else index to actual
            }
            mismatches.forEach { (index, actual) ->
                error(0, index, actual, "Заголовок не совпадает с шаблоном: ожидается «${headers[index]}»")
            }
            return mismatches.isEmpty()
        }

        private fun readSectionHeader(rowIndex: Int) {
            val title = text(rowIndex, 0)
            val sectionWeapon = Weapon.entries.firstOrNull { it.title.sameAs(title) }
            if (sectionWeapon != null) {
                if (discipline == ApplicationDiscipline.FREE_FORM) {
                    weapon = sectionWeapon
                    section = null
                } else {
                    error(rowIndex, 0, title, "Отсечка по оружию есть только на листе «Комплекс свободный»")
                }
                return
            }
            val parsed = parseSection(title)
            section = when {
                parsed == null -> null.also {
                    error(rowIndex, 0, title, "Возрастно-половая категория указывается как в шаблоне: «2013-2011 г.р. Юноши»")
                }
                parsed.sex !in allowedSexes() -> null.also {
                    error(rowIndex, 0, title, "На листе «${discipline.sheetName}» нет такой категории по полу")
                }
                discipline == ApplicationDiscipline.FREE_FORM && weapon == null -> null.also {
                    error(rowIndex, 0, title, "На листе «Комплекс свободный» категория указывается под отсечкой оружия")
                }
                else -> parsed.also { parent.sections += it }
            }
        }

        private fun allowedSexes(): Set<CategorySex> = when (discipline.entryKind) {
            EntryKind.SINGLE -> setOf(CategorySex.MALE, CategorySex.FEMALE)
            EntryKind.PAIR -> CategorySex.entries.toSet()
            EntryKind.GROUP -> setOf(CategorySex.MIXED)
        }

        private fun readParticipant(rowIndex: Int) {
            val currentSection = section
            if (currentSection == null) {
                error(rowIndex, 1, text(rowIndex, 1), "Спортсмен указан вне возрастно-половой категории")
                return
            }
            val athlete = readAthlete(rowIndex, currentSection)
            val source = SourceRow(parent.file.name, parent.file.sha256, sheet.sheetName, rowIndex + 1, text(rowIndex, 0).ifEmpty { null })
            when (discipline.entryKind) {
                EntryKind.SINGLE -> if (athlete != null) {
                    parent.entries += ParsedEntry(discipline, weapon, currentSection, athlete.weight, listOf(athlete), listOf(source))
                }
                EntryKind.PAIR -> {
                    val region = numberRegions.firstOrNull { it.firstRow <= rowIndex && rowIndex <= it.lastRow }
                    if (region == null || region.lastRow - region.firstRow != 1) {
                        error(rowIndex, 0, text(rowIndex, 0), "Пара нумеруется объединённой ячейкой № на 2 строки")
                    } else {
                        pairs.getOrPut(region) { mutableListOf() } += athlete to source
                    }
                }
                EntryKind.GROUP -> team += athlete to source
            }
        }

        private fun closePairs() {
            pairs.forEach { (region, members) ->
                if (members.size != 2) {
                    error(region.firstRow, 0, text(region.firstRow, 0), "В паре должно быть 2 спортсмена, указано ${members.size}")
                } else {
                    val athletes = members.mapNotNull { it.first }
                    val pairSection = sectionOf(region.firstRow)
                    if (pairSection != null && athletes.size == 2) {
                        parent.entries += ParsedEntry(discipline, null, pairSection, null, athletes, members.map { it.second })
                    }
                }
            }
        }

        private fun closeTeam() {
            if (team.isEmpty()) return
            val first = team.first().second
            if (team.size !in 5..15) {
                error(first.row - 1, 1, text(first.row - 1, 1), "В команде должно быть от 5 до 15 спортсменов, указано ${team.size}")
            } else {
                val athletes = team.mapNotNull { it.first }
                val teamSection = sectionOf(first.row - 1)
                if (teamSection != null && athletes.size == team.size) {
                    parent.entries += ParsedEntry(discipline, null, teamSection, null, athletes, team.map { it.second })
                }
            }
            team.clear()
        }

        /** The section a row belongs to, found again because pairs close after the whole sheet has been read. */
        private fun sectionOf(rowIndex: Int): AgeSection? = fullWidthRows.filter { it < rowIndex }.maxOrNull()
            ?.let { parseSection(text(it, 0)) }

        private fun readAthlete(rowIndex: Int, section: AgeSection): Athlete? {
            val errorsBefore = parent.errors.size
            val fullName = required(rowIndex, column.name)?.also { check(rowIndex, column.name, it, ApplicationFieldRules.fullName(it)) }
            val birthDate = birthDate(rowIndex, section)
            val weight = if (discipline.hasWeight) {
                required(rowIndex, column.weight)?.also { check(rowIndex, column.weight, it, ApplicationFieldRules.weight(it)) }
            } else {
                null
            }
            val sportQualification = required(rowIndex, column.sportQualification)?.let { value ->
                ApplicationFieldRules.sportQualification(value).also {
                    if (it == null) error(rowIndex, column.sportQualification, value, "Разряд или звание: ${ApplicationFieldRules.sportQualifications.joinToString(", ")}")
                }
            }
            val technicalQualification = required(rowIndex, column.technicalQualification)?.also {
                check(rowIndex, column.technicalQualification, it, ApplicationFieldRules.technicalQualification(it))
            }
            val city = required(rowIndex, column.city)
            val region = required(rowIndex, column.region)?.let { value ->
                ApplicationFieldRules.region(value).also {
                    if (it == null) error(rowIndex, column.region, value, "Субъект РФ указывается полностью, без сокращений")
                }
            }
            val federalDistrict = required(rowIndex, column.federalDistrict)?.let { value ->
                ApplicationFieldRules.federalDistrict(value).also {
                    if (it == null) error(rowIndex, column.federalDistrict, value, "Аббревиатура округа: ${ApplicationFieldRules.federalDistricts.joinToString(", ")}")
                }
            }
            val society = required(rowIndex, column.society)
            val school = required(rowIndex, column.school)
            val coaches = required(rowIndex, column.coaches)?.also { check(rowIndex, column.coaches, it, ApplicationFieldRules.coaches(it)) }
            if (parent.errors.size != errorsBefore) return null
            return Athlete(
                fullName = fullName!!.split(' ').filter(String::isNotEmpty).joinToString(" "),
                birthDate = birthDate!!,
                weight = weight,
                sportQualification = sportQualification!!,
                technicalQualification = technicalQualification!!.lowercase(),
                city = city!!,
                region = region!!,
                federalDistrict = federalDistrict!!,
                society = society!!,
                school = school!!,
                coaches = coaches!!.split(", "),
                doctorVisa = text(rowIndex, column.doctorVisa).ifEmpty { null },
            )
        }

        private fun birthDate(rowIndex: Int, section: AgeSection): LocalDate? {
            val cell = cell(rowIndex, column.birthDate)
            val value = text(rowIndex, column.birthDate)
            if (value.isEmpty()) return null.also { error(rowIndex, column.birthDate, value, REQUIRED) }
            if (cell == null || cell.cellType != CellType.NUMERIC || !DateUtil.isCellDateFormatted(cell)) {
                return null.also { error(rowIndex, column.birthDate, value, "Ячейка даты рождения должна иметь формат даты") }
            }
            val date = cell.localDateTimeCellValue.toLocalDate()
            val year = date.year
            val oldest = section.oldestBirthYear
            val ageReason = when {
                year > section.youngestBirthYear || (oldest != null && year < oldest) ->
                    "Год рождения не входит в категорию «${section.title}»"
                section.ageClass == AgeClass.YOUTH && Period.between(date, parent.competitionDate).years < 12 ->
                    "Для категории 12-14 лет спортсмену должно исполниться 12 лет на момент соревнований"
                else -> null
            }
            ageReason?.let { error(rowIndex, column.birthDate, value, it) }
            return date
        }

        private fun required(rowIndex: Int, columnIndex: Int): String? =
            text(rowIndex, columnIndex).ifEmpty { null }.also { if (it == null) error(rowIndex, columnIndex, "", REQUIRED) }

        private fun check(rowIndex: Int, columnIndex: Int, value: String, reason: String?) {
            if (reason != null) error(rowIndex, columnIndex, value, reason)
        }

        private fun error(rowIndex: Int, columnIndex: Int, value: String, reason: String) {
            parent.errors += ApplicationError(
                file = parent.file.name,
                sheet = sheet.sheetName,
                row = rowIndex + 1,
                column = "${CellReference.convertNumToColString(columnIndex)} (${headers[columnIndex].trim()})",
                value = value.ifEmpty { null },
                reason = reason,
            )
        }

        private fun cell(rowIndex: Int, columnIndex: Int): Cell? = sheet.getRow(rowIndex)?.getCell(columnIndex)

        private fun text(rowIndex: Int, columnIndex: Int): String =
            cell(rowIndex, columnIndex)?.let { parent.formatter.formatCellValue(it).trim() }.orEmpty()

        private fun isBlank(rowIndex: Int): Boolean = (1..headers.lastIndex).all { text(rowIndex, it).isEmpty() }

        /** A group team ends with an empty row filled with a colour other than white (template separator). */
        private fun isColoured(rowIndex: Int): Boolean {
            val row = sheet.getRow(rowIndex) ?: return false
            val style = (row.getCell(1)?.cellStyle ?: row.rowStyle) as? XSSFCellStyle ?: return false
            if (style.fillPattern != FillPatternType.SOLID_FOREGROUND) return false
            val color = style.fillForegroundColorColor ?: return false
            val rgb = color.rgb
            return if (rgb != null) {
                !rgb.all { it == 0xFF.toByte() }
            } else {
                color.indexed != IndexedColors.WHITE.index && color.indexed != IndexedColors.AUTOMATIC.index
            }
        }
    }

    private class Columns(hasWeight: Boolean) {
        private val shift = if (hasWeight) 1 else 0
        val name = 1
        val birthDate = 2
        val weight = 3
        val sportQualification = 3 + shift
        val technicalQualification = 4 + shift
        val city = 5 + shift
        val region = 6 + shift
        val federalDistrict = 7 + shift
        val society = 8 + shift
        val school = 9 + shift
        val coaches = 10 + shift
        val doctorVisa = 11 + shift
    }

    companion object {
        const val VERSION = "df-template-v1"
        const val NOT_XLSX = "Файл не читается как XLSX-заявка по шаблону"
        private const val REQUIRED = "Поле обязательно для заполнения"
        private val RUSSIAN = java.util.Locale.of("ru")

        private val HEADERS = listOf(
            "№", "ФИО спортсмена", "Дата рождения", "Спортивная квалификация", "Техническая квалификация", "Город",
            "Субъект РФ", "Федеральный округ", "ДСО (ведомство)", "ДЮСШ", "ФИО тренера", "Виза врача",
        )
        private val KERUGI_HEADERS = HEADERS.take(3) + "Весовая категория" + HEADERS.drop(3)

        private val sectionPattern = Regex("^(\\d{4})(?:-(\\d{4}))? г\\.р\\.( и старше)? (.+)$")

        /** Parses `2013-2011 г.р. Юноши` or `2007 г.р. и старше Мужчины и женщины`; null when it is not a template section. */
        fun parseSection(title: String): AgeSection? {
            val normalized = title.replace(Regex("\\s+"), " ").trim()
            val match = sectionPattern.matchEntire(normalized) ?: return null
            val (first, second, orOlder, group) = match.destructured
            val years = listOfNotNull(first.toInt(), second.toIntOrNull())
            if (orOlder.isNotEmpty() && second.isNotEmpty()) return null
            if (orOlder.isEmpty() && second.isEmpty()) return null
            AgeClass.entries.forEach { ageClass ->
                val sex = when {
                    group.sameAs(ageClass.male) -> CategorySex.MALE
                    group.sameAs(ageClass.female) -> CategorySex.FEMALE
                    group.sameAs("${ageClass.male} и ${ageClass.female.replaceFirstChar(Char::lowercaseChar).removePrefix("младшие ")}") ->
                        CategorySex.MIXED
                    else -> null
                }
                if (sex != null) {
                    return AgeSection(
                        title = normalized,
                        ageClass = ageClass,
                        sex = sex,
                        oldestBirthYear = if (orOlder.isEmpty()) years.min() else null,
                        youngestBirthYear = years.max(),
                    )
                }
            }
            return null
        }

        private fun String.sameAs(other: String): Boolean =
            trim().replace('ё', 'е').replace('Ё', 'Е').equals(other.trim().replace('ё', 'е').replace('Ё', 'Е'), ignoreCase = true)

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
