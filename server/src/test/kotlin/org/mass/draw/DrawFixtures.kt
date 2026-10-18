package org.mass.draw

import org.mass.applications.AgeClass
import org.mass.applications.AgeSection
import org.mass.applications.ApplicationCategory
import org.mass.applications.ApplicationDiscipline
import org.mass.applications.Athlete
import org.mass.applications.CategoryEntry
import org.mass.applications.CategorySex
import java.time.LocalDate

/** Categories for draw tests: one athlete per entry, the coaches (and belts) given per entry. */
object DrawFixtures {
    fun category(
        coaches: List<String>,
        belts: List<String> = coaches.map { "5 гып" },
        discipline: ApplicationDiscipline = ApplicationDiscipline.KERUGI,
        id: String = "category",
        weight: String = "до 33 кг",
        names: List<String> = coaches.indices.map { "Спортсмен $it" },
    ) = ApplicationCategory(
        id = id,
        discipline = discipline,
        weapon = null,
        section = AgeSection("2014-2013 г.р. Младшие юноши", AgeClass.YOUNGER, CategorySex.MALE, 2013, 2014),
        weight = weight.takeIf { discipline == ApplicationDiscipline.KERUGI },
        entries = coaches.mapIndexed { index, coach ->
            CategoryEntry(
                id = "file:sheet:$index",
                athletes = listOf(
                    Athlete(
                        fullName = names[index],
                        birthDate = LocalDate.of(2013, 1, 1).plusDays(index.toLong()),
                        weight = weight.takeIf { discipline == ApplicationDiscipline.KERUGI },
                        sportQualification = "б/р",
                        technicalQualification = belts[index],
                        city = "Город",
                        region = "Регион",
                        federalDistrict = "СФО",
                        society = "",
                        school = "Клуб",
                        coaches = listOf(coach),
                        doctorVisa = null,
                    ),
                ),
                sources = emptyList(),
            )
        },
    )
}
