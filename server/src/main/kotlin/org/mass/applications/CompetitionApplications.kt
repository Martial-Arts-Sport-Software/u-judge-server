package org.mass.applications

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate

/** How the athletes of one sheet form an entry: one athlete, a pair numbered in a merged cell, or a group team. */
enum class EntryKind { SINGLE, PAIR, GROUP }

/** The five application sheets (ADR-005); the sheet name is the template's exact spelling. */
enum class ApplicationDiscipline(val sheetName: String, val entryKind: EntryKind, val hasWeight: Boolean = false) {
    KERUGI("Весовая категория", EntryKind.SINGLE, hasWeight = true),
    SELF_DEFENSE("Приёмы самообороны", EntryKind.PAIR),
    STAGED_PAIR("Поединок постановочный - пара", EntryKind.PAIR),
    FREE_FORM("Комплекс свободный", EntryKind.SINGLE),
    STAGED_GROUP("Поединок постановочный - группа", EntryKind.GROUP),
}

/** Weapon sections of «Комплекс свободный» (FHR 2024 §1.1). */
enum class Weapon(val title: String) {
    SWORD("МЕЧ"),
    STAFF("ШЕСТ"),
    DOUBLE_NUNCHAKU("ПАРНЫЕ ДВОЙНЫЕ ЦЕПЫ"),
    FANS("ПАРНЫЕ ВЕЕРА"),
}

enum class CategorySex { MALE, FEMALE, MIXED }

/** Age classes of the federation templates, one per application file; the names are the template's section words. */
enum class AgeClass(val male: String, val female: String) {
    YOUNGER("Младшие юноши", "Младшие девушки"),
    YOUTH("Юноши", "Девушки"),
    JUNIORS("Юниоры", "Юниорки"),
    ADULTS("Мужчины", "Женщины"),
}

/**
 * An age/sex section header such as `2013-2011 г.р. Юноши` or `2007 г.р. и старше Мужчины и женщины`:
 * athletes born from [oldestBirthYear] (null: any earlier year) to [youngestBirthYear].
 */
@Serializable
data class AgeSection(
    val title: String,
    val ageClass: AgeClass,
    val sex: CategorySex,
    val oldestBirthYear: Int?,
    val youngestBirthYear: Int,
)

@Serializable
data class Athlete(
    val fullName: String,
    @Serializable(with = LocalDateIsoSerializer::class)
    val birthDate: LocalDate,
    /** `до 24 кг` or `свыше 24 кг`, only on the Kerugi sheet. */
    val weight: String?,
    val sportQualification: String,
    val technicalQualification: String,
    val city: String,
    val region: String,
    val federalDistrict: String,
    val society: String,
    val school: String,
    val coaches: List<String>,
    val doctorVisa: String?,
)

/** Where an athlete row came from; together with the file hash it is the source identifier (`IMP-002`). */
@Serializable
data class SourceRow(val fileName: String, val fileSha256: String, val sheet: String, val row: Int, val number: String?)

/** One start in a category: an athlete, a pair or a group team. */
@Serializable
data class CategoryEntry(val id: String, val athletes: List<Athlete>, val sources: List<SourceRow>)

@Serializable
data class ApplicationCategory(
    val id: String,
    val discipline: ApplicationDiscipline,
    val weapon: Weapon?,
    val section: AgeSection,
    val weight: String?,
    val entries: List<CategoryEntry>,
) {
    /** A readable name for lists: `Весовая категория · 2013-2011 г.р. Юноши · до 45 кг`. */
    val title: String
        get() = listOfNotNull(discipline.sheetName, weapon?.title, section.title, weight).joinToString(" · ")
}

@Serializable
data class ApplicationFile(val name: String, val sha256: String)

/** The normalised result of one import: the whole competition, replaced as a unit by a later import (ADR-005). */
@Serializable
data class CompetitionApplications(
    val competitionName: String,
    @Serializable(with = LocalDateIsoSerializer::class)
    val competitionDate: LocalDate,
    val readerVersion: String,
    val files: List<ApplicationFile>,
    val categories: List<ApplicationCategory>,
) {
    val athleteCount: Int
        get() = categories.flatMap { category -> category.entries.flatMap { it.athletes } }
            .map { it.fullName to it.birthDate }
            .distinct()
            .size
}

/**
 * One blocking validation error (`IMP-005`). [column] is the letter and header, for example `C (Дата рождения)`; file-level
 * errors leave the cell fields empty.
 */
@Serializable
data class ApplicationError(
    val file: String,
    val sheet: String? = null,
    val row: Int? = null,
    val column: String? = null,
    val value: String? = null,
    val reason: String,
)

object LocalDateIsoSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("org.mass.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
