package org.mass.protocol

import org.mass.applications.ApplicationCategory
import org.mass.applications.ApplicationDiscipline
import org.mass.applications.CategoryEntry
import org.mass.applications.CompetitionApplications
import org.mass.domain.SessionState
import org.mass.draw.BracketProgress
import org.mass.draw.CategoryDraw
import org.mass.draw.CompetitionOfficials
import org.mass.draw.CourtsBoard
import org.mass.draw.DrawFormat
import org.mass.draw.ItemRef
import org.mass.draw.ProtocolImages
import org.mass.report.CompetitionReport
import org.mass.report.PlacedEntry
import org.mass.report.ReportText
import org.mass.report.SessionKind
import org.mass.report.SessionReport
import java.time.LocalDate

/**
 * One category of a protocol: its confirmed draw, its court and the numbers of its bouts, and with results the decided bouts
 * ([progress]), the score of every judged bout or performance ([scores]) and the places (ADR-012 §5).
 */
data class ProtocolPage(
    val category: ApplicationCategory,
    val draw: CategoryDraw,
    val court: Int,
    val numbers: Map<ItemRef, Int>,
    val progress: BracketProgress? = null,
    val scores: Map<ItemRef, String> = emptyMap(),
    val places: List<PlacedEntry> = emptyList(),
) {
    /**
     * «<section>, <weight>» for Kerugi, «Танбон, <section>, <weight>» for Tanbon, «<discipline>, <weapon>, <section>» for the
     * technical disciplines.
     */
    val title: String
        get() = when {
            category.discipline == ApplicationDiscipline.KERUGI -> listOfNotNull(category.section.title, category.weight).joinToString(", ")
            category.discipline.isBout ->
                listOfNotNull(category.discipline.sheetName, category.section.title, category.weight).joinToString(", ")
            else -> listOfNotNull(category.discipline.sheetName, category.weapon?.title, category.section.title).joinToString(", ")
        }
}

/** «Протокол хода соревнований» (ADR-008 §8) or «Протокол результатов» with the places (ADR-012 §5). */
enum class ProtocolKind { PROGRESS, RESULTS }

/**
 * A protocol of one or several categories (ADR-008 §8, ADR-012 §5): what the renderer prints, taken from the courts board,
 * the current import and, with a report, the scores and places; the seal and signatures are competition settings.
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
    val kind: ProtocolKind = ProtocolKind.PROGRESS,
    val images: ProtocolImages = ProtocolImages(),
) {
    companion object {
        /**
         * The protocol of the confirmed categories among [categoryIds] (all of them when null), courts in order and the
         * categories of a court in the operator's order; null when none of them has a confirmed draw with bouts or
         * performances.
         */
        fun of(
            board: CourtsBoard,
            applications: CompetitionApplications,
            categoryIds: Collection<String>? = null,
            report: CompetitionReport? = null,
            kind: ProtocolKind = ProtocolKind.PROGRESS,
        ): ProtocolDocument? {
            val sessions = report?.sessions.orEmpty().filter { it.state == SessionState.COMPLETED }
            val places = report?.categories.orEmpty().associate { it.category.id to it.places }
            val pages = board.ordered
                .filter { categoryIds == null || it.category.id in categoryIds }
                .mapNotNull { state ->
                    val draw = state.numbered?.takeIf { it.format != DrawFormat.NO_BOUTS } ?: return@mapNotNull null
                    val categoryId = state.category.id
                    ProtocolPage(
                        category = state.category,
                        draw = draw,
                        court = board.courtOf(categoryId),
                        numbers = board.numbers.filterKeys { it.categoryId == categoryId },
                        progress = board.results[categoryId],
                        scores = sessions.filter { it.categoryId == categoryId }.associate { session ->
                            ItemRef(categoryId, session.round, session.index) to scoreText(session)
                        },
                        places = places[categoryId].orEmpty(),
                    )
                }
            if (pages.isEmpty()) return null
            return ProtocolDocument(
                competitionName = applications.competitionName,
                competitionDate = applications.competitionDate,
                officials = board.officials,
                preliminary = board.preliminary,
                courts = board.plan.count,
                pages = pages,
                kind = kind,
                images = board.images,
            )
        }

        /** «2:0» for a bout, «6.2» for a performance. */
        fun scoreText(session: SessionReport): String = when (session.kind) {
            SessionKind.TECHNICAL -> ReportText.tenths(session.totalTenths)
            else -> "${session.blueScore ?: 0}:${session.redScore ?: 0}"
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
