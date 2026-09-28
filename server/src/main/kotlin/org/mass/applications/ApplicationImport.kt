package org.mass.applications

import java.time.LocalDate

/** A selected application file before it is read. */
class ApplicationFileInput(val name: String, val bytes: ByteArray)

sealed interface ImportPreparation {
    /** Nothing may be written; the operator fixes the files and checks them again (`IMP-005`, `IMP-006`). */
    data class Invalid(val errors: List<ApplicationError>) : ImportPreparation

    data class Valid(val applications: CompetitionApplications) : ImportPreparation
}

/**
 * Reads and validates all selected files together and merges them into one competition: the files of different coaches and
 * age groups fill the same categories. Any error in any file makes the whole selection invalid (ADR-005).
 */
class ApplicationImport(private val reader: ApplicationTemplateV1Reader = ApplicationTemplateV1Reader()) {
    fun prepare(competitionName: String, competitionDate: LocalDate, files: List<ApplicationFileInput>): ImportPreparation {
        val errors = mutableListOf<ApplicationError>()
        if (competitionName.isBlank()) errors += ApplicationError(file = "", reason = "Укажите название соревнования")
        if (files.isEmpty()) errors += ApplicationError(file = "", reason = "Выберите хотя бы один файл заявки")

        val results = files.map { reader.read(it.name, it.bytes, competitionDate) }
        results.groupBy { it.file.sha256 }.values.filter { it.size > 1 }.forEach { same ->
            errors += ApplicationError(file = same.joinToString(", ") { it.file.name }, reason = "Один и тот же файл выбран дважды")
        }
        results.forEach { errors += it.errors }
        errors += sectionMismatches(results)

        val entries = results.flatMap { it.entries }
        errors += duplicates(entries)
        if (errors.isNotEmpty()) return ImportPreparation.Invalid(errors)

        return ImportPreparation.Valid(
            CompetitionApplications(
                competitionName = competitionName.trim(),
                competitionDate = competitionDate,
                readerVersion = ApplicationTemplateV1Reader.VERSION,
                files = results.map { it.file },
                categories = categories(entries),
            ),
        )
    }

    /** Files of one age class must use the same birth years, otherwise a stale template would split a category silently. */
    private fun sectionMismatches(results: List<FileReadResult>): List<ApplicationError> =
        results.flatMap { result -> result.sections.map { result.file.name to it } }
            .groupBy { (_, section) -> section.ageClass to section.sex }
            .values
            .filter { sections -> sections.map { it.second.title }.distinct().size > 1 }
            .map { sections ->
                ApplicationError(
                    file = sections.map { it.first }.distinct().joinToString(", "),
                    value = sections.map { it.second.title }.distinct().joinToString("; "),
                    reason = "Годы рождения одной возрастной категории различаются между файлами",
                )
            }

    /** The same athlete (full name and birth date) may start only once in a category, across all files. */
    private fun duplicates(entries: List<ParsedEntry>): List<ApplicationError> =
        entries.groupBy(::categoryKey).values.flatMap { categoryEntries ->
            categoryEntries.flatMap { entry -> entry.athletes.zip(entry.sources) }
                .groupBy { (athlete, _) -> athlete.fullName.lowercase() to athlete.birthDate }
                .values
                .filter { it.size > 1 }
                .map { rows ->
                    val (athlete, _) = rows.first()
                    ApplicationError(
                        file = rows.map { it.second.fileName }.distinct().joinToString(", "),
                        sheet = rows.first().second.sheet,
                        row = rows.first().second.row,
                        value = athlete.fullName,
                        reason = "Спортсмен заявлен в категории несколько раз: строки " +
                            rows.joinToString(", ") { (_, source) -> "${source.fileName} ${source.row}" },
                    )
                }
        }

    private fun categories(entries: List<ParsedEntry>): List<ApplicationCategory> =
        entries.groupBy(::categoryKey)
            .map { (key, categoryEntries) ->
                val first = categoryEntries.first()
                ApplicationCategory(
                    id = key,
                    discipline = first.discipline,
                    weapon = first.weapon,
                    section = first.section,
                    weight = first.weight,
                    entries = categoryEntries.map { entry ->
                        val source = entry.sources.first()
                        CategoryEntry(
                            id = "${source.fileSha256.take(12)}:${source.sheet}:${source.row}",
                            athletes = entry.athletes,
                            sources = entry.sources,
                        )
                    },
                )
            }
            .sortedWith(
                compareBy<ApplicationCategory>(
                    { it.discipline.ordinal },
                    { it.weapon?.ordinal ?: -1 },
                    { it.section.ageClass.ordinal },
                    { it.section.sex.ordinal },
                ).thenBy { weightOrder(it.weight) },
            )

    private fun categoryKey(entry: ParsedEntry): String =
        listOfNotNull(entry.discipline.name, entry.weapon?.name, entry.section.title, entry.weight).joinToString("|")

    /** `до 24 кг` < `до 30 кг` < `свыше 30 кг`. */
    private fun weightOrder(weight: String?): Double {
        if (weight == null) return 0.0
        val kilograms = weight.split(' ')[1].replace(',', '.').toDouble()
        return if (weight.startsWith("свыше")) kilograms + 0.5 else kilograms
    }
}
