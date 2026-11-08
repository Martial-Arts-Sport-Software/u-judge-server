package org.mass.report

import org.mass.applications.ApplicationCategory
import org.mass.applications.CategoryEntry
import org.mass.domain.SessionState
import org.mass.domain.TechnicalScoring
import org.mass.draw.BracketProgress
import org.mass.draw.CategoryDraw
import org.mass.draw.DrawFormat
import org.mass.draw.drawKey
import org.mass.performance.TechnicalPerformance

/**
 * The places of one category (ADR-012 §2), only once every bout or performance is decided:
 *
 * - elimination (FHR 2024 §1.2.2, ADR-007 §3): 1 the winner of the final, 2 its loser, 3 both semifinal losers;
 * - round robin (ADR-007 §4): by wins, two entries with equal wins by their bout; three or more stay the chief judge's
 *   decision;
 * - performances (ADR-011 §8): by the total, then `K1` and `K2`; full ties share the place and are marked for the panel.
 */
object Places {
    fun of(
        category: ApplicationCategory,
        draw: CategoryDraw?,
        progress: BracketProgress?,
        performances: List<TechnicalPerformance>,
    ): List<PlacedEntry> = when (draw?.format) {
        DrawFormat.ELIMINATION -> progress?.takeIf { it.decided }?.let { elimination(category, it) }.orEmpty()
        DrawFormat.ROUND_ROBIN -> progress?.takeIf { it.decided }?.let { roundRobin(category, it) }.orEmpty()
        DrawFormat.PERFORMANCE_ORDER -> performanceOrder(category, draw, performances)
        DrawFormat.NO_BOUTS, null -> emptyList()
    }

    private fun elimination(category: ApplicationCategory, progress: BracketProgress): List<PlacedEntry> {
        val entries = category.entries.associateBy { it.drawKey }
        val final = progress.bouts.maxBy { it.round }
        fun loser(round: Int, index: Int): String? {
            val bout = progress.bouts.single { it.round == round && it.index == index }
            val winner = progress.winner(round, index)
            return listOf(bout.first, bout.second).mapNotNull(progress::key).singleOrNull { it != winner }
        }
        val placed = buildList {
            progress.winner(final.round, final.index)?.let { add(1 to it) }
            loser(final.round, final.index)?.let { add(2 to it) }
            progress.bouts.filter { it.round == final.round - 1 }.forEach { semifinal ->
                loser(semifinal.round, semifinal.index)?.let { add(3 to it) }
            }
        }
        return placed.mapNotNull { (place, key) -> entries[key]?.let { PlacedEntry(place, it) } }
    }

    private fun roundRobin(category: ApplicationCategory, progress: BracketProgress): List<PlacedEntry> {
        val entries = category.entries.associateBy { it.drawKey }
        val standings = progress.standings
        var ahead = 0
        return standings.groupBy { it.wins }.toSortedMap(compareByDescending { it }).values.flatMap { group ->
            val keys = group.map { it.key }
            val placed = when (keys.size) {
                1 -> listOf(PlacedEntry(ahead + 1, entries.getValue(keys.single())))
                2 -> {
                    val bout = progress.bouts.firstOrNull { listOf(it.first, it.second).mapNotNull(progress::key).toSet() == keys.toSet() }
                    val winner = bout?.let { progress.winner(it.round, it.index) }
                    if (winner == null) {
                        keys.map { PlacedEntry(null, entries.getValue(it), panelDecision = true) }
                    } else {
                        listOf(PlacedEntry(ahead + 1, entries.getValue(winner)), PlacedEntry(ahead + 2, entries.getValue(keys.single { it != winner })))
                    }
                }
                else -> keys.map { PlacedEntry(null, entries.getValue(it), panelDecision = true) }
            }
            ahead += keys.size
            placed
        }
    }

    private fun performanceOrder(category: ApplicationCategory, draw: CategoryDraw, performances: List<TechnicalPerformance>): List<PlacedEntry> {
        val entries = category.entries.associateBy(CategoryEntry::id)
        val completed = performances.filter { it.state == SessionState.COMPLETED }
            .associateBy { it.started.entry.entryId }
        if (draw.entryKeys.isEmpty() || completed.size < draw.entryKeys.size) return emptyList()
        val results = completed.mapNotNull { (entryId, performance) -> performance.result()?.let { entryId to it } }
        return TechnicalScoring.rank(results).mapNotNull { place ->
            entries[place.key]?.let { PlacedEntry(place.place, it, panelDecision = place.panelVote) }
        }
    }
}
