package org.mass.draw

import org.mass.applications.ApplicationDiscipline
import org.mass.applications.CategoryEntry
import org.mass.draw.DrawFixtures.category
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DrawTest {
    private val byCoach = DrawMethod(SeparationCriterion.COACH)

    @Test
    fun `the same seed replays the same draw and another seed gives another`() {
        val category = category(coaches = List(12) { "Тренер ${it % 3}" })
        val draws = (1L..20L).map { Draw.draw(category, DrawSystem.SINGLE_ELIMINATION, byCoach, it) }
        (1L..20L).forEach { seed ->
            assertEquals(draws[seed.toInt() - 1], Draw.draw(category, DrawSystem.SINGLE_ELIMINATION, byCoach, seed))
        }
        assertTrue(draws.distinct().size > 1)
    }

    @Test
    fun `every entry takes one slot of the next power of two and byes never meet each other`() {
        (2..40).forEach { count ->
            val category = category(coaches = List(count) { "Тренер $it" })
            repeat(10) { seed ->
                val draw = Draw.draw(category, DrawSystem.SINGLE_ELIMINATION, DrawMethod(), seed.toLong())
                val size = draw.positions.size
                assertEquals(DrawFormat.ELIMINATION, draw.format)
                assertEquals(Integer.highestOneBit(size), size)
                assertTrue(size >= count && size < count * 2)
                assertEquals(category.entries.map { it.drawKey }.toSet(), draw.entryKeys.toSet())
                assertEquals(count, draw.entryKeys.size)
                draw.positions.chunked(2).forEach { pair -> assertTrue(pair.any { it != null }, "bye meets bye: $draw") }
            }
        }
    }

    @Test
    fun `byes spread evenly over halves and quarters`() {
        (3..40).forEach { count ->
            val draw = Draw.draw(category(coaches = List(count) { "Тренер $it" }), DrawSystem.SINGLE_ELIMINATION, DrawMethod(), 7)
            listOf(2, 4).filter { draw.positions.size >= it * 2 }.forEach { sections ->
                val byes = draw.positions.chunked(draw.positions.size / sections).map { section -> section.count { it == null } }
                assertTrue(byes.max() - byes.min() <= 1, "$count entries, byes per section $byes")
            }
        }
    }

    @Test
    fun `the byes of twelve entries open each quarter as in the organiser's protocol`() {
        val draw = Draw.draw(category(coaches = List(12) { "Тренер $it" }), DrawSystem.SINGLE_ELIMINATION, DrawMethod(), 1)
        assertEquals(listOf(1, 5, 9, 13), draw.positions.indices.filter { draw.positions[it] == null })
    }

    @Test
    fun `two entries of one coach meet only in the final`() {
        (3..32).forEach { count ->
            val coaches = List(count) { if (it < 2) "Общий тренер" else "Тренер $it" }
            val category = category(coaches = coaches)
            repeat(20) { seed ->
                val draw = Draw.draw(category, DrawSystem.SINGLE_ELIMINATION, byCoach, seed.toLong())
                val (first, second) = slotsOf(draw, category.entries.take(2))
                assertEquals(Integer.numberOfTrailingZeros(draw.positions.size), Draw.meetingRound(first, second))
                assertTrue(Draw.clashes(category, draw, byCoach).isEmpty())
            }
        }
    }

    @Test
    fun `three or four entries of one coach go to different quarters`() {
        (8..32).forEach { count ->
            listOf(3, 4).forEach { same ->
                val category = category(coaches = List(count) { if (it < same) "Общий тренер" else "Тренер $it" })
                repeat(20) { seed ->
                    val draw = Draw.draw(category, DrawSystem.SINGLE_ELIMINATION, byCoach, seed.toLong())
                    val quarter = draw.positions.size / 4
                    val quarters = slotsOf(draw, category.entries.take(same)).map { it / quarter }
                    assertEquals(same, quarters.distinct().size, "$count entries, seed $seed: $draw")
                }
            }
        }
    }

    @Test
    fun `an unavoidable clash is marked`() {
        val category = category(coaches = List(4) { "Общий тренер" })
        val draw = Draw.draw(category, DrawSystem.SINGLE_ELIMINATION, byCoach, 3)
        assertEquals(2, Draw.clashes(category, draw, byCoach).size)
        assertTrue(Draw.clashes(category, draw, DrawMethod()).isEmpty())
    }

    @Test
    fun `a bye moves the first bout and its clash to the second round`() {
        val positions = listOf("a", null, "b", "c", "d", "e", "f", "g")
        assertEquals(2, Draw.firstOpponents(positions).getValue("a" to "b"))
        assertEquals(1, Draw.firstOpponents(positions).getValue("b" to "c"))
        assertEquals(null, Draw.firstOpponents(positions)["a" to "d"])
    }

    @Test
    fun `the belt narrows separation to entries of the same belt`() {
        val category = category(coaches = List(8) { if (it < 2) "Общий тренер" else "Тренер $it" }, belts = List(8) { "${it % 2 + 5} гып" })
        val method = DrawMethod(SeparationCriterion.COACH, withBelt = true)
        repeat(20) { seed ->
            val draw = Draw.draw(category, DrawSystem.SINGLE_ELIMINATION, method, seed.toLong())
            // Different belts: nothing to separate, so no clash is reported whatever the positions.
            assertTrue(Draw.clashes(category, draw, method).isEmpty())
        }
        assertFailsWith<IllegalArgumentException> { DrawMethod(null, withBelt = true) }
    }

    @Test
    fun `small categories and technical disciplines get their own formats`() {
        assertEquals(DrawFormat.NO_BOUTS, Draw.draw(category(listOf("A")), DrawSystem.ROUND_ROBIN, DrawMethod(), 1).format)
        val pair = Draw.draw(category(listOf("A", "B")), DrawSystem.ROUND_ROBIN, DrawMethod(), 1)
        assertEquals(DrawFormat.ELIMINATION, pair.format)
        assertEquals(2, pair.positions.size)
        assertEquals(DrawSystem.ROUND_ROBIN, Draw.defaultSystem(category(List(5) { "T$it" })))
        assertEquals(DrawSystem.SINGLE_ELIMINATION, Draw.defaultSystem(category(List(6) { "T$it" })))
        val technical = category(List(7) { "T$it" }, discipline = ApplicationDiscipline.FREE_FORM)
        assertEquals(DrawFormat.PERFORMANCE_ORDER, Draw.draw(technical, DrawSystem.SINGLE_ELIMINATION, DrawMethod(), 1).format)
    }

    @Test
    fun `one origin performs back to back only when it has more than half of the entries`() {
        repeat(200) { seed ->
            val random = Random(seed)
            val count = random.nextInt(2, 20)
            val coaches = List(count) { "Тренер ${random.nextInt(0, 4)}" }
            val category = category(coaches, discipline = ApplicationDiscipline.FREE_FORM)
            val draw = Draw.draw(category, DrawSystem.SINGLE_ELIMINATION, byCoach, seed.toLong())
            val largest = coaches.groupingBy { it }.eachCount().values.max()
            if (largest <= (count + 1) / 2) assertTrue(Draw.clashes(category, draw, byCoach).isEmpty(), "$coaches → $draw")
            assertEquals(category.entries.map { it.drawKey }.toSet(), draw.entryKeys.toSet())
        }
    }

    @Test
    fun `a swap exchanges two entries and keeps the byes`() {
        val draw = CategoryDraw(DrawFormat.ELIMINATION, listOf("a", null, "b", "c"))
        assertEquals(listOf("c", null, "b", "a"), draw.swap("a", "c").positions)
        assertFailsWith<IllegalArgumentException> { draw.swap("a", "x") }
    }

    @Test
    fun `elimination bouts skip byes and link winners`() {
        val bouts = Bouts.of(CategoryDraw(DrawFormat.ELIMINATION, listOf("a", null, "b", "c", "d", "e", null, "f")))
        assertEquals(
            listOf(
                Bout(1, 0, BoutSide.Entry("b"), BoutSide.Entry("c")),
                Bout(1, 1, BoutSide.Entry("d"), BoutSide.Entry("e")),
                Bout(2, 0, BoutSide.Entry("a"), BoutSide.WinnerOf(1, 0)),
                Bout(2, 1, BoutSide.WinnerOf(1, 1), BoutSide.Entry("f")),
                Bout(3, 0, BoutSide.WinnerOf(2, 0), BoutSide.WinnerOf(2, 1)),
            ),
            bouts,
        )
    }

    @Test
    fun `round robin pairs every two entries once and nobody twice in a round`() {
        (3..8).forEach { count ->
            val keys = List(count) { "e$it" }
            val draw = CategoryDraw(DrawFormat.ROUND_ROBIN, keys)
            val bouts = Bouts.of(draw)
            val pairs = bouts.map { setOf((it.first as BoutSide.Entry).key, (it.second as BoutSide.Entry).key) }
            assertEquals(count * (count - 1) / 2, pairs.distinct().size)
            assertEquals(pairs.size, pairs.distinct().size)
            bouts.groupBy { it.round }.values.forEach { round ->
                val entries = round.flatMap { listOf(it.first, it.second) }
                assertEquals(entries.size, entries.distinct().size)
            }
            assertEquals(Bouts.rounds(draw), bouts.maxOf { it.round })
        }
    }

    @Test
    fun `the draw key survives a re-import that changes entry IDs`() {
        val entry = category(listOf("A")).entries.single()
        assertEquals(entry.drawKey, entry.copy(id = "other").drawKey)
        assertNotEquals(entry.drawKey, entry.copy(athletes = listOf(entry.athletes.single().copy(fullName = "Другой"))).drawKey)
    }

    private fun slotsOf(draw: CategoryDraw, entries: List<CategoryEntry>) = entries.map { draw.positions.indexOf(it.drawKey) }
}
