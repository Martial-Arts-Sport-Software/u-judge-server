package org.mass.applications

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplicationTemplateV1ReaderTest {
    private val reader = ApplicationTemplateV1Reader()
    private val competitionDate = LocalDate.of(2025, 11, 15)

    @Test
    fun `federation templates are recognised with their sections and no errors`() {
        val expected = mapOf(
            "df.xlsx" to AgeClass.YOUNGER,
            "template-10-11.xlsx" to AgeClass.YOUNGER,
            "template-12-14.xlsx" to AgeClass.YOUTH,
            "template-15-17.xlsx" to AgeClass.JUNIORS,
            "template-18-plus.xlsx" to AgeClass.ADULTS,
        )
        expected.forEach { (name, ageClass) ->
            val result = reader.read(name, resource(name), competitionDate)

            assertEquals(emptyList(), result.errors, name)
            assertEquals(emptyList(), result.entries, name)
            assertEquals(setOf(ageClass), result.sections.map { it.ageClass }.toSet(), name)
            assertEquals(setOf(CategorySex.MALE, CategorySex.FEMALE, CategorySex.MIXED), result.sections.map { it.sex }.toSet(), name)
        }
    }

    @Test
    fun `a filled federation template yields athletes, pairs and a group team`() {
        val workbook = XSSFWorkbook(resource("template-12-14.xlsx").inputStream())
        val dateStyle = workbook.createCellStyle().apply { dataFormat = workbook.createDataFormat().getFormat("dd.mm.yyyy") }
        fun write(sheetName: String, rowIndex: Int, row: AthleteRow, hasWeight: Boolean = false) {
            val sheet = workbook.getSheet(sheetName)
            val sheetRow = sheet.getRow(rowIndex) ?: sheet.createRow(rowIndex)
            val values = listOfNotNull(
                row.fullName, "", row.weight.takeIf { hasWeight }, row.sportQualification, row.technicalQualification,
                row.city, row.region, row.federalDistrict, row.society, row.school, row.coaches,
            )
            values.forEachIndexed { index, value ->
                val cell = sheetRow.getCell(index + 1) ?: sheetRow.createCell(index + 1)
                if (index == 1) {
                    cell.setCellValue(row.birthDate)
                    cell.cellStyle = dateStyle
                } else {
                    cell.setCellValue(value)
                }
            }
        }
        // Kerugi: rows 4-5 under «2013-2011 г.р. Юноши»
        write("Весовая категория", 3, AthleteRow(weight = "до 45 кг").copy(fullName = "Иванов Иван"), hasWeight = true)
        write("Весовая категория", 4, AthleteRow(fullName = "Петров Пётр", weight = "свыше 60 кг"), hasWeight = true)
        // Pair: merged A4:A5
        write("Поединок постановочный - пара", 3, AthleteRow(fullName = "Иванов Иван"))
        write("Поединок постановочный - пара", 4, AthleteRow(fullName = "Петров Пётр"))
        // Group: rows 4-8, then the coloured row 9 of the template
        (3..7).forEach { write("Поединок постановочный - группа", it, AthleteRow(fullName = ApplicationWorkbookFixture.name(it))) }
        val bytes = ByteArrayOutputStream().also { workbook.write(it) }.toByteArray()

        val result = reader.read("filled.xlsx", bytes, competitionDate)

        assertEquals(emptyList(), result.errors)
        val byDiscipline = result.entries.groupBy { it.discipline }
        assertEquals(listOf("до 45 кг", "свыше 60 кг"), byDiscipline.getValue(ApplicationDiscipline.KERUGI).map { it.weight })
        assertEquals(listOf(2), byDiscipline.getValue(ApplicationDiscipline.STAGED_PAIR).map { it.athletes.size })
        assertEquals(listOf(5), byDiscipline.getValue(ApplicationDiscipline.STAGED_GROUP).map { it.athletes.size })
        assertEquals(CategorySex.MIXED, byDiscipline.getValue(ApplicationDiscipline.STAGED_GROUP).single().section.sex)
        val kerugi = byDiscipline.getValue(ApplicationDiscipline.KERUGI).first()
        assertEquals(SourceRow("filled.xlsx", result.file.sha256, "Весовая категория", 4, null), kerugi.sources.single())
        assertEquals(listOf("Петров П.П.", "Сидоров С.С."), kerugi.athletes.single().coaches)
    }

    @Test
    fun `weapon sections of the free form sheet set the weapon of their athletes`() {
        val bytes = ApplicationWorkbookFixture()
            .sheet(ApplicationDiscipline.FREE_FORM) {
                section("МЕЧ")
                section("2013-2011 г.р. Юноши")
                athlete()
                section("ПАРНЫЕ ВЕЕРА")
                section("2013-2011 г.р. Юноши")
                athlete()
            }
            .bytes()

        val result = reader.read("weapons.xlsx", bytes, competitionDate)

        assertEquals(emptyList(), result.errors)
        assertEquals(listOf(Weapon.SWORD, Weapon.FANS), result.entries.map { it.weapon })
    }

    @Test
    fun `every field rule violation is reported with sheet, row, column and value`() {
        val bytes = ApplicationWorkbookFixture()
            .sheet(ApplicationDiscipline.KERUGI) {
                section("2013-2011 г.р. Юноши")
                athlete(
                    AthleteRow(
                        fullName = "Иванов И.И.",
                        birthDate = null,
                        birthDateText = "10.05.2012",
                        weight = "45кг",
                        sportQualification = "1 разряд",
                        technicalQualification = "5гып",
                        city = "",
                        region = "СПб",
                        federalDistrict = "Северо-Западный",
                        society = "",
                        school = "",
                        coaches = "Петров П.П.",
                    ),
                )
            }
            .bytes()

        val errors = reader.read("broken.xlsx", bytes, competitionDate).errors

        assertEquals(
            listOf(
                "B (ФИО спортсмена)", "C (Дата рождения)", "D (Весовая категория)", "E (Спортивная квалификация)",
                "F (Техническая квалификация)", "G (Город)", "H (Субъект РФ)", "I (Федеральный округ)", "J (ДСО (ведомство))",
                "K (ДЮСШ)", "L (ФИО тренера)",
            ),
            errors.map { it.column },
        )
        assertTrue(errors.all { it.file == "broken.xlsx" && it.sheet == "Весовая категория" && it.row == 4 })
        assertEquals("10.05.2012", errors[1].value)
        assertEquals("Ячейка даты рождения должна иметь формат даты", errors[1].reason)
        assertNull(errors[5].value)
    }

    @Test
    fun `age outside the section and under 12 for 12-14 are rejected`() {
        val bytes = ApplicationWorkbookFixture()
            .sheet(ApplicationDiscipline.KERUGI) {
                section("2013-2011 г.р. Юноши")
                athlete(AthleteRow(birthDate = LocalDate.of(2010, 1, 1)))
                athlete(AthleteRow(fullName = "Петров Пётр", birthDate = LocalDate.of(2013, 12, 1)))
                athlete(AthleteRow(fullName = "Сидоров Сидор", birthDate = LocalDate.of(2013, 11, 15)))
            }
            .bytes()

        val errors = reader.read("ages.xlsx", bytes, competitionDate).errors

        assertEquals(listOf(4, 5), errors.map { it.row })
        assertEquals("Год рождения не входит в категорию «2013-2011 г.р. Юноши»", errors[0].reason)
        assertEquals("Для категории 12-14 лет спортсмену должно исполниться 12 лет на момент соревнований", errors[1].reason)
    }

    @Test
    fun `pairs need a merged number of two rows with two athletes`() {
        val bytes = ApplicationWorkbookFixture()
            .sheet(ApplicationDiscipline.SELF_DEFENSE) {
                section("2013-2011 г.р. Юноши и девушки")
                pair(mergeNumber = false)
                pair()
                pair(second = AthleteRow(fullName = "Иванов И."))
            }
            .bytes()

        val result = reader.read("pairs.xlsx", bytes, competitionDate)

        assertEquals(
            listOf(
                4 to "Пара нумеруется объединённой ячейкой № на 2 строки",
                5 to "Пара нумеруется объединённой ячейкой № на 2 строки",
                9 to "ФИО указывается полностью, без инициалов и сокращений",
            ),
            result.errors.map { it.row to it.reason },
        )
        assertEquals(1, result.entries.size)
    }

    @Test
    fun `group teams are split by the coloured row and need 5 to 15 athletes`() {
        val bytes = ApplicationWorkbookFixture()
            .sheet(ApplicationDiscipline.STAGED_GROUP) {
                section("2013-2011 г.р. Юноши и девушки")
                team((0 until 5).map { AthleteRow(fullName = ApplicationWorkbookFixture.name(it)) })
                team((5 until 9).map { AthleteRow(fullName = ApplicationWorkbookFixture.name(it)) })
            }
            .bytes()

        val result = reader.read("groups.xlsx", bytes, competitionDate)

        assertEquals(listOf(5), result.entries.map { it.athletes.size })
        assertEquals("В команде должно быть от 5 до 15 спортсменов, указано 4", result.errors.single().reason)
        assertEquals(10, result.errors.single().row)
    }

    @Test
    fun `layout outside the template is rejected instead of guessed`() {
        val broken = ApplicationWorkbookFixture()
            .sheet(ApplicationDiscipline.KERUGI) {
                header(2, "ДР")
                section("2013-2011 г.р. Юноши")
                athlete()
            }
            .sheet(ApplicationDiscipline.STAGED_PAIR) {
                athlete()
                section("Юноши 12-14 лет")
                section("2013-2011 г.р. Юноши и девушки")
            }
            .sheet(ApplicationDiscipline.STAGED_GROUP) { section("2013-2011 г.р. Юноши") }
            .sheet(ApplicationDiscipline.FREE_FORM) {
                section("2010-2008 г.р. Юниоры")
                section("МЕЧ")
                section("2010-2008 г.р. Юниоры")
            }
            .bytes()

        val errors = reader.read("layout.xlsx", broken, competitionDate).errors.map { it.sheet to it.reason }

        assertEquals(
            listOf(
                "Весовая категория" to "Заголовок не совпадает с шаблоном: ожидается «Дата рождения»",
                "Поединок постановочный - пара" to "Спортсмен указан вне возрастно-половой категории",
                "Поединок постановочный - пара" to
                    "Возрастно-половая категория указывается как в шаблоне: «2013-2011 г.р. Юноши»",
                "Комплекс свободный" to "На листе «Комплекс свободный» категория указывается под отсечкой оружия",
                "Поединок постановочный - группа" to "На листе «Поединок постановочный - группа» нет такой категории по полу",
                null to "В одном файле может быть только одна возрастная категория",
            ),
            errors,
        )
        assertEquals(
            listOf(ApplicationError("notes.txt", reason = ApplicationTemplateV1Reader.NOT_XLSX)),
            reader.read("notes.txt", "hello".toByteArray(), competitionDate).errors,
        )
    }

    @Test
    fun `sheets must be the five template sheets`() {
        val workbook = XSSFWorkbook(resource("template-12-14.xlsx").inputStream())
        workbook.removeSheetAt(workbook.getSheetIndex("Комплекс свободный"))
        workbook.createSheet("Итоги")
        val bytes = ByteArrayOutputStream().also { workbook.write(it) }.toByteArray()

        val reasons = reader.read("sheets.xlsx", bytes, competitionDate).errors.map { it.reason }

        assertEquals(
            listOf(
                "Лишний лист: в заявке 5 листов по дисциплинам шаблона",
                "Нет листа «Комплекс свободный» из шаблона",
            ),
            reasons,
        )
    }

    @Test
    fun `section titles follow the template forms`() {
        val adults = ApplicationTemplateV1Reader.parseSection("2007 г.р. и старше Мужчины и женщины")!!
        assertEquals(AgeSection("2007 г.р. и старше Мужчины и женщины", AgeClass.ADULTS, CategorySex.MIXED, null, 2007), adults)
        val younger = ApplicationTemplateV1Reader.parseSection("2015-2013 г.р. Младшие девушки")!!
        assertEquals(AgeSection("2015-2013 г.р. Младшие девушки", AgeClass.YOUNGER, CategorySex.FEMALE, 2013, 2015), younger)
        assertNull(ApplicationTemplateV1Reader.parseSection("2013 г.р. Юноши"))
        assertNull(ApplicationTemplateV1Reader.parseSection("2013-2011 г.р. и старше Юноши"))
        assertNull(ApplicationTemplateV1Reader.parseSection("2013-2011 г.р. Мальчики"))
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/applications/$name")) { name }.use { it.readBytes() }
}
