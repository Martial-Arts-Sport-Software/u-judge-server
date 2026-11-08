package org.mass.draw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** ADR-008 §6: one numbering across all courts, round by round, without byes; technical performances in the same pass. */
class BoutNumberingTest {
    private fun elimination(vararg positions: String?) = CategoryDraw(DrawFormat.ELIMINATION, positions.toList())

    private fun keys(prefix: String, count: Int) = List(count) { "$prefix$it" }

    @Test
    fun `round 1 of every category on every court comes before round 2`() {
        val four = elimination(*keys("a", 4).toTypedArray())
        val eight = elimination(*keys("b", 8).toTypedArray())
        // Three entries in a bracket of four: c0 has a bye and meets the winner of c1-c2 in round 2.
        val three = elimination("c0", null, "c1", "c2")
        val numbers = BoutNumbering.number(listOf(listOf("A" to four, "B" to eight), listOf("C" to three)))

        fun of(category: String, round: Int) = numbers.filterKeys { it.categoryId == category && it.round == round }.values.toList()
        assertEquals(listOf(1, 2), of("A", 1))
        assertEquals(listOf(3, 4, 5, 6), of("B", 1))
        assertEquals(listOf(7), of("C", 1))
        assertEquals(listOf(8), of("A", 2))
        assertEquals(listOf(9, 10), of("B", 2))
        assertEquals(listOf(11), of("C", 2))
        assertEquals(listOf(12), of("B", 3))
        assertEquals((1..12).toList(), numbers.values.sorted())
    }

    @Test
    fun `byes get no number`() {
        // Five entries in a bracket of eight: three byes, so four bouts in all.
        val five = elimination("e0", null, "e1", "e2", "e3", null, "e4", null)
        val numbers = BoutNumbering.number(listOf(listOf("E" to five)))
        assertEquals(
            mapOf(ItemRef("E", 1, 0) to 1, ItemRef("E", 2, 0) to 2, ItemRef("E", 2, 1) to 3, ItemRef("E", 3, 0) to 4),
            numbers,
        )
    }

    @Test
    fun `round robin rounds interleave across categories`() {
        val three = CategoryDraw(DrawFormat.ROUND_ROBIN, keys("r", 3))
        val four = CategoryDraw(DrawFormat.ROUND_ROBIN, keys("s", 4))
        val numbers = BoutNumbering.number(listOf(listOf("R" to three, "S" to four)))

        fun of(category: String, round: Int) = numbers.filterKeys { it.categoryId == category && it.round == round }.values.toList()
        // With three entries one rests every round: one bout per round, two for four entries.
        assertEquals(listOf(1), of("R", 1))
        assertEquals(listOf(2, 3), of("S", 1))
        assertEquals(listOf(4), of("R", 2))
        assertEquals(listOf(5, 6), of("S", 2))
        assertEquals(listOf(7), of("R", 3))
        assertEquals(listOf(8, 9), of("S", 3))
    }

    @Test
    fun `technical performances take numbers in round 1 of the same pass`() {
        val kerugi = elimination(*keys("k", 4).toTypedArray())
        val forms = CategoryDraw(DrawFormat.PERFORMANCE_ORDER, listOf("f2", "f0", "f1"))
        val single = CategoryDraw(DrawFormat.NO_BOUTS, listOf("n0"))
        val numbers = BoutNumbering.number(listOf(listOf("K" to kerugi, "F" to forms, "N" to single)))
        assertEquals(
            mapOf(
                ItemRef("K", 1, 0) to 1,
                ItemRef("K", 1, 1) to 2,
                ItemRef("F", 1, 0) to 3,
                ItemRef("F", 1, 1) to 4,
                ItemRef("F", 1, 2) to 5,
                ItemRef("K", 2, 0) to 6,
            ),
            numbers,
        )
    }

    @Test
    fun `frozen numbers stay and later bouts follow the highest one`() {
        val four = elimination(*keys("a", 4).toTypedArray())
        val frozen = BoutNumbering.number(listOf(listOf("A" to four)))
        // Another category is drawn before A after the start: A keeps 1-3, the new bouts take 4 on in the usual order.
        val two = elimination("d0", "d1")
        val numbers = BoutNumbering.number(listOf(listOf("D" to two, "A" to four)), frozen)
        assertEquals(frozen, numbers.filterKeys { it.categoryId == "A" })
        assertEquals(mapOf(ItemRef("D", 1, 0) to 4), numbers.filterKeys { it.categoryId == "D" })
        // The same plan without the freeze numbers D first.
        assertEquals(1, BoutNumbering.number(listOf(listOf("D" to two, "A" to four)))[ItemRef("D", 1, 0)])
    }

    @Test
    fun `the court plan keeps every category on exactly one court`() {
        val plan = CourtPlan.single(listOf("a", "b", "c"))
        assertEquals(CourtPlan(listOf(listOf("a", "b", "c"), emptyList())), plan.withCount(2))
        val split = plan.withCount(3).place("b", 2, 0).place("c", 3, 0)
        assertEquals(CourtPlan(listOf(listOf("a"), listOf("b"), listOf("c"))), split)
        // Removed courts move to the end of the last remaining one.
        assertEquals(CourtPlan(listOf(listOf("a", "b", "c"))), split.withCount(1))
        assertEquals(CourtPlan(listOf(listOf("a"), listOf("b", "c"))), split.withCount(2))
        // Order within a court, clamped.
        assertEquals(CourtPlan(listOf(listOf("c", "a", "b"))), plan.place("c", 1, 0))
        assertEquals(CourtPlan(listOf(listOf("b", "c", "a"))), plan.place("a", 1, 99))
        // A new import: gone categories drop out, new ones join court 1 in import order.
        assertEquals(
            CourtPlan(listOf(listOf("a", "d"), listOf("c"))),
            CourtPlan(listOf(listOf("a", "b"), listOf("c"))).resolve(listOf("a", "c", "d")),
        )
        assertFailsWith<IllegalArgumentException> { CourtPlan(listOf(listOf("a"), listOf("a"))) }
        assertFailsWith<IllegalArgumentException> { plan.withCount(11) }
        assertFailsWith<IllegalArgumentException> { CourtPlan(emptyList()) }
    }
}
