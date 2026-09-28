package org.mass.applications

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

class ApplicationImportTest {
    private val applicationImport = ApplicationImport()
    private val date = LocalDate.of(2025, 11, 15)

    @Test
    fun `files of several coaches fill the same categories in template order`() {
        val first = ApplicationWorkbookFixture().sheet(ApplicationDiscipline.KERUGI) {
            section("2013-2011 г.р. Юноши")
            athlete(AthleteRow(fullName = "Иванов Иван", weight = "свыше 60 кг"))
            athlete(AthleteRow(fullName = "Петров Пётр", weight = "до 45 кг"))
        }.bytes()
        val second = ApplicationWorkbookFixture().sheet(ApplicationDiscipline.KERUGI) {
            section("2013-2011 г.р. Юноши")
            athlete(AthleteRow(fullName = "Сидоров Сидор", weight = "до 45 кг", region = "Московская область", federalDistrict = "ЦФО"))
        }.sheet(ApplicationDiscipline.SELF_DEFENSE) {
            section("2013-2011 г.р. Юноши и девушки")
            pair()
        }.bytes()

        val valid = assertIs<ImportPreparation.Valid>(
            applicationImport.prepare(" Первенство СПб ", date, listOf(ApplicationFileInput("a.xlsx", first), ApplicationFileInput("b.xlsx", second))),
        ).applications

        assertEquals("Первенство СПб", valid.competitionName)
        assertEquals(ApplicationTemplateV1Reader.VERSION, valid.readerVersion)
        assertEquals(listOf("a.xlsx", "b.xlsx"), valid.files.map { it.name })
        assertEquals(
            listOf(
                "Весовая категория · 2013-2011 г.р. Юноши · до 45 кг",
                "Весовая категория · 2013-2011 г.р. Юноши · свыше 60 кг",
                "Приёмы самообороны · 2013-2011 г.р. Юноши и девушки",
            ),
            valid.categories.map { it.title },
        )
        assertEquals(listOf("Петров Пётр", "Сидоров Сидор"), valid.categories[0].entries.map { it.athletes.single().fullName })
        assertEquals(5, valid.athleteCount)
    }

    @Test
    fun `cross-file errors block the whole selection`() {
        val file = ApplicationWorkbookFixture().sheet(ApplicationDiscipline.KERUGI) {
            section("2013-2011 г.р. Юноши")
            athlete()
        }.bytes()
        val sameAthlete = ApplicationWorkbookFixture().sheet(ApplicationDiscipline.KERUGI) {
            section("2013-2011 г.р. Юноши")
            athlete(AthleteRow(society = "Динамо"))
        }.bytes()
        val staleYears = ApplicationWorkbookFixture().sheet(ApplicationDiscipline.KERUGI) {
            section("2012-2010 г.р. Юноши")
            athlete(AthleteRow(fullName = "Петров Пётр"))
        }.bytes()

        val errors = assertIs<ImportPreparation.Invalid>(
            applicationImport.prepare(
                "",
                date,
                listOf(
                    ApplicationFileInput("a.xlsx", file),
                    ApplicationFileInput("copy.xlsx", file),
                    ApplicationFileInput("b.xlsx", sameAthlete),
                    ApplicationFileInput("old.xlsx", staleYears),
                ),
            ),
        ).errors

        assertEquals(
            listOf(
                "Укажите название соревнования",
                "Один и тот же файл выбран дважды",
                "Годы рождения одной возрастной категории различаются между файлами",
                "Спортсмен заявлен в категории несколько раз: строки a.xlsx 4, copy.xlsx 4, b.xlsx 4",
            ),
            errors.map { it.reason },
        )
        assertEquals("a.xlsx, copy.xlsx", errors[1].file)
    }

    @Test
    fun `500 participants are validated and merged within seconds`() {
        val files = SyntheticApplications.files(participants = 500)

        val (preparation, duration) = measureTimedValue { applicationImport.prepare("Синтетика", date, files) }

        val valid = assertIs<ImportPreparation.Valid>(preparation).applications
        assertEquals(500, valid.athleteCount)
        assertTrue(duration.inWholeSeconds < 10, "took $duration")
    }
}
