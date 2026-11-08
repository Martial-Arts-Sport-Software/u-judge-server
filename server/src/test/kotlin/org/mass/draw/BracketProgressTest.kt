package org.mass.draw

import org.mass.domain.EventId
import org.mass.domain.KerugiCompetitor.BLUE
import org.mass.domain.KerugiCompetitor.RED
import org.mass.domain.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Winner advancement of ADR-010 §6 (`BRK-004`): elimination slots, round-robin standings and «Победитель». */
class BracketProgressTest {
    private fun decision(winner: org.mass.domain.KerugiCompetitor) = BoutDecision(winner, "final_score", SessionId(EventId.new().value))

    @Test
    fun `elimination winners fill the next round, a bye passes straight on and the final names the winner`() {
        val draw = CategoryDraw(DrawFormat.ELIMINATION, listOf("a", "b", "c", null, "e", "f", "g", "h"))
        val empty = BracketProgress(draw, emptyMap())
        assertEquals(listOf(1 to 0, 1 to 1, 1 to 2, 2 to 0, 2 to 1, 3 to 0), empty.bouts.map { it.round to it.index })
        val semifinal = empty.bouts.single { it.round == 2 && it.index == 0 }
        assertNull(empty.key(semifinal.first))
        assertEquals("c", empty.key(semifinal.second))

        val decisions = mapOf(
            (1 to 0) to decision(RED),
            (1 to 1) to decision(BLUE),
            (1 to 2) to decision(RED),
            (2 to 0) to decision(BLUE),
        )
        val progress = BracketProgress(draw, decisions)
        assertEquals("b", progress.key(semifinal.first))
        val other = progress.bouts.single { it.round == 2 && it.index == 1 }
        assertEquals(listOf("e", "h"), listOf(other.first, other.second).map(progress::key))
        val final = progress.bouts.single { it.round == 3 }
        assertEquals(listOf("b", null), listOf(final.first, final.second).map(progress::key))
        assertNull(progress.champion)
        assertFalse(progress.decided)

        val finished = BracketProgress(draw, decisions + ((2 to 1) to decision(RED)) + ((3 to 0) to decision(RED)))
        assertEquals("h", finished.champion)
        assertTrue(finished.decided)
    }

    @Test
    fun `round robin ranks by wins, two tied entries by their bout, three tied entries stay undecided`() {
        val draw = CategoryDraw(DrawFormat.ROUND_ROBIN, listOf("a", "b", "c"))
        val bouts = BracketProgress(draw, emptyMap()).bouts
        fun winning(winners: Map<Set<String>, String>) = BracketProgress(
            draw,
            bouts.associate { bout ->
                val sides = listOf((bout.first as BoutSide.Entry).key, (bout.second as BoutSide.Entry).key)
                (bout.round to bout.index) to decision(if (winners.getValue(sides.toSet()) == sides[0]) BLUE else RED)
            },
        )

        val clear = winning(mapOf(setOf("a", "b") to "a", setOf("a", "c") to "a", setOf("b", "c") to "c"))
        assertEquals(listOf(Standing("a", 2, 0), Standing("c", 1, 1), Standing("b", 0, 2)), clear.standings)
        assertEquals("a", clear.champion)

        val circle = winning(mapOf(setOf("a", "b") to "a", setOf("b", "c") to "b", setOf("a", "c") to "c"))
        assertTrue(circle.decided)
        assertNull(circle.champion)

        val partial = BracketProgress(draw, mapOf((bouts.first().round to bouts.first().index) to decision(BLUE)))
        assertNull(partial.champion)
        assertEquals(1, partial.standings.first().wins)
    }

    @Test
    fun `two round robin leaders are separated by the bout between them`() {
        val draw = CategoryDraw(DrawFormat.ROUND_ROBIN, listOf("a", "b", "c", "d"))
        val bouts = BracketProgress(draw, emptyMap()).bouts
        // a beats b, c and d beat nobody else but a loses to c: a and c have two wins each.
        val winners = mapOf(
            setOf("a", "b") to "a", setOf("a", "c") to "c", setOf("a", "d") to "a",
            setOf("b", "c") to "c", setOf("b", "d") to "b", setOf("c", "d") to "d",
        )
        val progress = BracketProgress(
            draw,
            bouts.associate { bout ->
                val first = (bout.first as BoutSide.Entry).key
                val second = (bout.second as BoutSide.Entry).key
                (bout.round to bout.index) to decision(if (winners.getValue(setOf(first, second)) == first) BLUE else RED)
            },
        )
        assertEquals(setOf("a", "c"), progress.standings.filter { it.wins == 2 }.map(Standing::key).toSet())
        assertEquals("c", progress.champion)
    }
}
