package org.mass.protocol

import org.mass.applications.ApplicationCategory
import org.mass.applications.ApplicationDiscipline
import org.mass.applications.CategoryEntry
import org.mass.applications.CompetitionApplications
import org.mass.draw.CategoryDraw
import org.mass.draw.CompetitionOfficials
import org.mass.draw.CourtsBoard
import org.mass.draw.DrawFormat
import org.mass.draw.ItemRef
import java.time.LocalDate

/** One category of «Протокол хода соревнований»: its confirmed draw, its court and the numbers of its bouts. */
data class ProtocolPage(
    val category: ApplicationCategory,
    val draw: CategoryDraw,
    val court: Int,
    val numbers: Map<ItemRef, Int>,
) {
    /** «<section>, <weight>» for Kerugi, «<discipline>, <weapon>, <section>» for the technical disciplines. */
    val title: String
        get() = if (category.discipline == ApplicationDiscipline.KERUGI) {
            listOfNotNull(category.section.title, category.weight).joinToString(", ")
        } else {
            listOfNotNull(category.discipline.sheetName, category.weapon?.title, category.section.title).joinToString(", ")
        }
}

/**
 * «Протокол хода соревнований» of one or several categories (ADR-008 §8): what the renderer prints, taken from the courts
 * board and the current import. Scores, winners and places come with I2b, seal and signature images with I6.
 */
data class ProtocolDocument(
    val competitionName: String,
    val competitionDate: LocalDate,
    val officials: CompetitionOfficials,
    /** Not every category is confirmed: every page says «нумерация предварительная». */
    val preliminary: Boolean,
    /** The number of courts; with more than one every page names its court. */
    val courts: Int,
    val pages: List<ProtocolPage>,
) {
    companion object {
        /**
         * The protocol of the confirmed categories among [categoryIds] (all of them when null), courts in order and the
         * categories of a court in the operator's order; null when none of them has a confirmed draw with bouts or
         * performances.
         */
        fun of(board: CourtsBoard, applications: CompetitionApplications, categoryIds: Collection<String>? = null): ProtocolDocument? {
            val pages = board.ordered
                .filter { categoryIds == null || it.category.id in categoryIds }
                .mapNotNull { state ->
                    val draw = state.numbered?.takeIf { it.format != DrawFormat.NO_BOUTS } ?: return@mapNotNull null
                    val categoryId = state.category.id
                    ProtocolPage(state.category, draw, board.courtOf(categoryId), board.numbers.filterKeys { it.categoryId == categoryId })
                }
            if (pages.isEmpty()) return null
            return ProtocolDocument(
                competitionName = applications.competitionName,
                competitionDate = applications.competitionDate,
                officials = board.officials,
                preliminary = board.preliminary,
                courts = board.plan.count,
                pages = pages,
            )
        }

        /** «Фамилия И. О. (город)»; a pair or a team lists every athlete and their distinct cities. */
        fun entryName(entry: CategoryEntry): String {
            val names = entry.athletes.joinToString(", ") { shortName(it.fullName) }
            val cities = entry.athletes.map { it.city.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
            return if (cities.isEmpty()) names else "$names ($cities)"
        }

        /** «Иванов Иван Иванович» → «Иванов И. И.». */
        fun shortName(fullName: String): String {
            val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return ""
            return (listOf(parts.first()) + parts.drop(1).map { "${it.first().uppercaseChar()}." }).joinToString(" ")
        }
    }
}
