package org.mass.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `NFR-011`: the normative vectors shared with the client, boundaries and properties of the technical calculation. */
class TechnicalScoringTest {
    @Test
    fun `every normative vector gives the same judge totals, aggregate, divisor, total and tie-break keys`() {
        val vectors = normativeVectors()
        assertTrue(vectors.size >= 10)
        assertEquals(TechnicalDiscipline.entries.map { it.code }.toSet(), vectors.map { it.string("discipline") }.toSet())
        vectors.forEach { vector ->
            val name = vector.string("name")
            val discipline = requireNotNull(TechnicalDiscipline.ofCode(vector.string("discipline")))
            val formula = requireNotNull(AggregationFormula.ofCode(vector.string("formula")))
            val judges = vector.getValue("judges").jsonArray.map { it.jsonObject }
            val vectorsOfJudges = judges.map { judge ->
                JudgeVector(judge.ints("techniqueTenths"), judge.ints("presentationTenths"), judge.int("deductionTenths")).also {
                    assertTrue(it.fits(vector.int("techniqueCriteria")), "$name fits")
                    assertEquals(judge.int("totalTenths"), it.totalTenths, "$name judge total")
                }
            }
            val result = TechnicalScoring.result(discipline, vectorsOfJudges, formula, vector.ints("adjustmentsTenths"))
            assertEquals(vector.int("aggregateTenths"), result.aggregateTenths, "$name aggregate")
            assertEquals(vector.int("divisor"), result.divisor, "$name divisor")
            assertEquals(vector.int("adjustmentTenths"), result.adjustmentTenths, "$name adjustment")
            assertEquals(vector.int("totalTenths"), result.totalTenths, "$name total")
            assertEquals(vector.int("priorityTenths"), result.priorityTenths, "$name priority")
            assertEquals(vector.int("prioritySumTenths"), result.prioritySumTenths, "$name priority sum")
        }
    }

    @Test
    fun `criteria are 0_1 to 1_0, presentation has four and technique four or six by discipline and age`() {
        assertEquals(4, TechnicalDiscipline.HOSINSOOL.techniqueCriteria(younger = true))
        assertEquals(6, TechnicalDiscipline.HOSINSOOL.techniqueCriteria(younger = false))
        assertEquals(4, TechnicalDiscipline.PAIR_FREESTYLE.techniqueCriteria(younger = true))
        listOf(TechnicalDiscipline.GROUP_FREESTYLE, TechnicalDiscipline.SWORD, TechnicalDiscipline.POLE,
            TechnicalDiscipline.PAIRED_NUNCHAKU, TechnicalDiscipline.PAIRED_FANS).forEach {
            assertEquals(6, it.techniqueCriteria(younger = true), it.code)
        }
        val maximum = JudgeVector(List(6) { 10 }, List(4) { 10 }, 0)
        assertTrue(maximum.fits(6))
        assertEquals(60, maximum.techniqueTenths)
        assertEquals(40, maximum.presentationTenths)
        assertEquals(40, JudgeVector(List(4) { 10 }, List(4) { 1 }, 0).techniqueTenths)
        assertTrue(JudgeVector(List(4) { 1 }, List(4) { 1 }, 0).fits(4))
        assertFalse(JudgeVector(listOf(0, 1, 1, 1), List(4) { 1 }, 0).fits(4), "0.0 is not a criterion")
        assertFalse(JudgeVector(listOf(11, 1, 1, 1), List(4) { 1 }, 0).fits(4))
        assertFalse(JudgeVector(List(4) { 5 }, List(4) { 5 }, 0).fits(6), "wrong technique count")
        assertFalse(JudgeVector(List(4) { 5 }, List(3) { 5 }, 0).fits(4), "wrong presentation count")
        assertFalse(JudgeVector(List(4) { 5 }, List(4) { 5 }, 2).fits(4), "deductions are 0.3 steps")
        assertFalse(JudgeVector(List(4) { 5 }, List(4) { 5 }, -3).fits(4))
        assertTrue(JudgeVector(List(4) { 1 }, List(4) { 1 }, 9).fits(4))
        assertEquals(0, JudgeVector(List(4) { 1 }, List(4) { 1 }, 9).totalTenths, "a judge total is never negative")
    }

    @Test
    fun `the trimmed mean needs three scores and shows its divisor`() {
        assertFailsWith<IllegalArgumentException> { TechnicalScoring.aggregate(listOf(50, 60), AggregationFormula.TRIMMED_MEAN) }
        assertEquals(TechnicalAggregate(60, 1), TechnicalScoring.aggregate(listOf(50, 60, 90), AggregationFormula.TRIMMED_MEAN))
        assertEquals(TechnicalAggregate(64, 3), TechnicalScoring.aggregate(listOf(10, 60, 64, 68, 99), AggregationFormula.TRIMMED_MEAN))
        assertEquals(TechnicalAggregate(55, 2), TechnicalScoring.aggregate(listOf(50, 60), AggregationFormula.MEAN))
        // Duplicated extremes drop only one of each.
        assertEquals(TechnicalAggregate(70, 3), TechnicalScoring.aggregate(listOf(70, 70, 70, 70, 70), AggregationFormula.TRIMMED_MEAN))
    }

    @Test
    fun `property - the aggregate is the half-up decimal mean of the kept totals and ignores the judges' order`() {
        val random = Random(20261108)
        repeat(5_000) {
            val formula = AggregationFormula.entries.random(random)
            val count = random.nextInt(formula.minimumScores, 8)
            val totals = List(count) { random.nextInt(0, 101) }
            val aggregate = TechnicalScoring.aggregate(totals, formula)
            val kept = if (formula == AggregationFormula.TRIMMED_MEAN) totals.sorted().drop(1).dropLast(1) else totals
            val expected = BigDecimal(kept.sum()).divide(BigDecimal(kept.size), 0, RoundingMode.HALF_UP).toInt()
            assertEquals(expected, aggregate.valueTenths, "$formula $totals")
            assertEquals(kept.size, aggregate.divisor)
            assertTrue(aggregate.valueTenths in kept.min()..kept.max())
            assertEquals(aggregate, TechnicalScoring.aggregate(totals.shuffled(random), formula))
        }
    }

    @Test
    fun `property - a judge total is the clamped sum of the criteria minus the deduction`() {
        val random = Random(42)
        repeat(5_000) {
            val count = if (random.nextBoolean()) 4 else 6
            val vector = JudgeVector(
                List(count) { random.nextInt(1, 11) },
                List(4) { random.nextInt(1, 11) },
                3 * random.nextInt(0, 6),
            )
            assertTrue(vector.fits(count))
            val decimal = (vector.technique + vector.presentation).fold(BigDecimal.ZERO) { sum, tenths -> sum + BigDecimal(tenths).movePointLeft(1) }
                .subtract(BigDecimal(vector.deductionTenths).movePointLeft(1)).max(BigDecimal.ZERO)
            assertEquals(decimal.movePointRight(1).toInt(), vector.totalTenths)
        }
    }

    @Test
    fun `places follow the total, then the priority part, then its sum, and full ties go to the panel vote`() {
        fun result(total: Int, priority: Int, sum: Int) =
            TechnicalResult(total, total, 0, 3, AggregationFormula.TRIMMED_MEAN, priority, sum)
        val places = TechnicalScoring.rank(
            listOf(
                "a" to result(80, 40, 200),
                "b" to result(85, 30, 150),
                "c" to result(80, 41, 190),
                "d" to result(80, 40, 210),
                "e" to result(70, 35, 175),
                "f" to result(70, 35, 175),
            ),
        )
        assertEquals(listOf("b", "c", "d", "a", "e", "f"), places.map { it.key })
        assertEquals(listOf(1, 2, 3, 4, 5, 5), places.map { it.place })
        assertEquals(listOf(false, false, false, false, true, true), places.map { it.panelVote })
    }

    @Test
    fun `Hosinsool breaks ties by technique and the freestyle disciplines by presentation`() {
        val judges = listOf(
            JudgeVector(listOf(9, 9, 9, 9), listOf(5, 5, 5, 5), 0),
            JudgeVector(listOf(9, 9, 9, 9), listOf(5, 5, 5, 5), 0),
            JudgeVector(listOf(9, 9, 9, 9), listOf(5, 5, 5, 5), 0),
        )
        assertEquals(36, TechnicalScoring.result(TechnicalDiscipline.HOSINSOOL, judges, AggregationFormula.TRIMMED_MEAN, emptyList()).priorityTenths)
        assertEquals(20, TechnicalScoring.result(TechnicalDiscipline.PAIR_FREESTYLE, judges, AggregationFormula.TRIMMED_MEAN, emptyList()).priorityTenths)
        assertTrue(TechnicalDiscipline.PAIRED_FANS.bonusAllowed)
        assertFalse(TechnicalDiscipline.SWORD.bonusAllowed)
    }

    private fun normativeVectors(): List<JsonObject> = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/contract/v1/technical_test_vectors.json")).readText(),
    ).jsonObject.getValue("vectors").jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

    private fun JsonObject.ints(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.int }
}
