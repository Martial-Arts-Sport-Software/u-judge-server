package org.mass.report

import org.mass.domain.KerugiCompetitor
import org.mass.domain.SessionId
import org.mass.draw.BoutDecision
import org.mass.draw.BoutSide
import org.mass.draw.Bouts
import org.mass.draw.BracketProgress
import org.mass.draw.CategoryDraw
import org.mass.draw.DrawFixtures.category
import org.mass.draw.DrawFormat
import org.mass.draw.drawKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** ADR-012 §2: places by the rules where they decide, the chief judge's decision where they do not. */
class PlacesTest {
    @Test
    fun `round robin ranks by wins and two equal entries by their bout`() {
        val (names, places) = roundRobin(4, "0>1", "0>2", "3>0", "1>2", "1>3", "2>3")
        assertEquals(listOf(1 to names[0], 2 to names[1], 3 to names[2], 4 to names[3]), places.map { it.place to it.entry.drawKey })
    }

    @Test
    fun `three entries with equal wins get no place from the system`() {
        val (_, places) = roundRobin(3, "0>1", "1>2", "2>0")
        assertEquals(listOf(null, null, null), places.map { it.place })
        assertEquals(listOf(true, true, true), places.map { it.panelDecision })
    }

    @Test
    fun `an undecided bracket has no places yet and a single entry gets none`() {
        val category = category(List(3) { "Тренер $it" })
        val draw = CategoryDraw(DrawFormat.ROUND_ROBIN, category.entries.map { it.drawKey })
        assertEquals(emptyList(), Places.of(category, draw, BracketProgress(draw, emptyMap()), emptyList()))
        val single = category(listOf("Тренер"))
        assertEquals(emptyList(), Places.of(single, CategoryDraw(DrawFormat.NO_BOUTS, single.entries.map { it.drawKey }), null, emptyList()))
    }

    /** A round robin of [size] entries where each result `"a>b"` makes entry a beat entry b. */
    private fun roundRobin(size: Int, vararg results: String): Pair<List<String>, List<PlacedEntry>> {
        val category = category(List(size) { "Тренер $it" })
        val keys = category.entries.map { it.drawKey }
        val draw = CategoryDraw(DrawFormat.ROUND_ROBIN, keys)
        val bouts = Bouts.of(draw)
        val decisions = results.associate { result ->
            val (winner, loser) = result.split(">").map { keys[it.toInt()] }
            val bout = bouts.single { setOf((it.first as BoutSide.Entry).key, (it.second as BoutSide.Entry).key) == setOf(winner, loser) }
            val side = if ((bout.first as BoutSide.Entry).key == winner) KerugiCompetitor.BLUE else KerugiCompetitor.RED
            (bout.round to bout.index) to BoutDecision(side, "final_score", SessionId("00000000-0000-4000-8000-00000000000$size"))
        }
        return keys to Places.of(category, draw, BracketProgress(draw, decisions), emptyList())
    }
}
