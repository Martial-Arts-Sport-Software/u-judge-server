package org.mass.applications

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Generated applications for `SYS-003` and the manual demo: one file per age class of a competition on 15.11.2025, every
 * athlete in exactly one start, so the athlete count equals [files]'s `participants`.
 */
object SyntheticApplications {
    private val sections = listOf(
        Triple("2015-2013 г.р.", AgeClass.YOUNGER, 2014),
        Triple("2013-2011 г.р.", AgeClass.YOUTH, 2012),
        Triple("2010-2008 г.р.", AgeClass.JUNIORS, 2009),
        Triple("2007 г.р. и старше", AgeClass.ADULTS, 1995),
    )
    private val regions = listOf(
        Triple("Санкт-Петербург", "Санкт-Петербург", "Санкт-Петербург"),
        Triple("Москва", "Москва", "Москва"),
        Triple("Казань", "Республика Татарстан", "ПФО"),
        Triple("Екатеринбург", "Свердловская область", "УФО"),
        Triple("Новосибирск", "Новосибирская область", "СФО"),
    )
    private val weights = listOf("до 30 кг", "до 35 кг", "до 40 кг", "до 45 кг", "до 50 кг", "свыше 50 кг")

    /** [participants] is a multiple of 500: 125 athletes in each of the four age class files. */
    fun files(participants: Int): List<ApplicationFileInput> {
        require(participants % (sections.size * 125) == 0)
        var index = 0
        fun row(birthYear: Int): AthleteRow {
            val (city, region, district) = regions[index % regions.size]
            return AthleteRow(
                fullName = ApplicationWorkbookFixture.name(index++),
                birthDate = LocalDate.of(birthYear, 1 + index % 12, 1 + index % 28),
                weight = weights[index % weights.size],
                city = city,
                region = region,
                federalDistrict = district,
                coaches = listOf("Петров П.П., Сидоров С.С.", "Кузнецов К.К., Кузнецов К.К.")[index % 2],
            )
        }
        val repeats = participants / (sections.size * 125)
        return sections.map { (years, ageClass, birthYear) ->
            val fixture = ApplicationWorkbookFixture()
            repeat(repeats) {
                fixture.sheet(ApplicationDiscipline.KERUGI) {
                    section("$years ${ageClass.male}")
                    repeat(30) { athlete(row(birthYear)) }
                    section("$years ${ageClass.female}")
                    repeat(30) { athlete(row(birthYear)) }
                }
                fixture.sheet(ApplicationDiscipline.FREE_FORM) {
                    Weapon.entries.forEach { weapon ->
                        section(weapon.title)
                        section("$years ${ageClass.male}")
                        repeat(5) { athlete(row(birthYear)) }
                    }
                }
                fixture.sheet(ApplicationDiscipline.STAGED_PAIR) {
                    section("$years ${ageClass.male} и ${ageClass.female.lowercase().removePrefix("младшие ")}")
                    repeat(10) { pair(row(birthYear), row(birthYear)) }
                }
                fixture.sheet(ApplicationDiscipline.STAGED_GROUP) {
                    section("$years ${ageClass.male} и ${ageClass.female.lowercase().removePrefix("младшие ")}")
                    repeat(5) { team(List(5) { row(birthYear) }) }
                }
            }
            ApplicationFileInput("synthetic-${ageClass.name.lowercase()}.xlsx", fixture.bytes())
        }
    }
}

class SyntheticApplicationsTest {
    /** Leaves demo files in `server/build/synthetic-applications`: a valid 500-participant set and one broken file. */
    @Test
    fun `synthetic application files are written for the manual demo`() {
        val directory = Path.of("build", "synthetic-applications").also(Files::createDirectories)
        val files = SyntheticApplications.files(participants = 500)
        files.forEach { Files.write(directory.resolve(it.name), it.bytes) }
        val broken = ApplicationWorkbookFixture().sheet(ApplicationDiscipline.KERUGI) {
            section("2013-2011 г.р. Юноши")
            athlete(AthleteRow(fullName = "Иванов И.И.", weight = "45кг", technicalQualification = "5гып"))
            athlete(AthleteRow(fullName = "Петров Пётр", coaches = "Петров П.П."))
        }.bytes()
        Files.write(directory.resolve("broken-application.xlsx"), broken)

        val valid = ApplicationImport().prepare("Синтетика", LocalDate.of(2025, 11, 15), files)
        assertEquals(500, assertIs<ImportPreparation.Valid>(valid).applications.athleteCount)
    }
}
