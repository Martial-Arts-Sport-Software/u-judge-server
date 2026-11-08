package org.mass.domain

/**
 * The seven technical disciplines of FHR 2024 (ADR-011 §4) with their wire codes. [presentationPriority] is the tie-break
 * part (`TEC-013`): technique for Hosinsool, presentation for the freestyle disciplines; [bonusAllowed] marks the two
 * paired weapons that may get the arbiter's `+0.2` for two items.
 */
enum class TechnicalDiscipline(
    val code: String,
    val presentationPriority: Boolean,
    val bonusAllowed: Boolean = false,
    /** Hosinsool and Pair use 4 or 6 technical rounds by age; the others always 6 (`TEC-003`, `TEC-005`). */
    val ageDependent: Boolean = false,
) {
    HOSINSOOL("hosinsool", presentationPriority = false, ageDependent = true),
    PAIR_FREESTYLE("pair_freestyle", presentationPriority = true, ageDependent = true),
    GROUP_FREESTYLE("group_freestyle", presentationPriority = true),
    SWORD("sword", presentationPriority = true),
    POLE("pole", presentationPriority = true),
    PAIRED_NUNCHAKU("paired_nunchaku", presentationPriority = true, bonusAllowed = true),
    PAIRED_FANS("paired_fans", presentationPriority = true, bonusAllowed = true);

    /** The number of technique criteria; [younger] is true for «Младшие юноши» and «Юноши» (4 rounds). */
    fun techniqueCriteria(younger: Boolean): Int = if (ageDependent && younger) 4 else 6

    companion object {
        const val PRESENTATION_CRITERIA = 4

        fun ofCode(code: String): TechnicalDiscipline? = entries.firstOrNull { it.code == code }
    }
}

/** How the judges' totals become the performance score (`TEC-010`-`TEC-012`). */
enum class AggregationFormula(val code: String, val minimumScores: Int) {
    /** Drop one lowest and one highest total, average the rest (FHR default, `TEC-011`). */
    TRIMMED_MEAN("trimmed_mean", minimumScores = 3),

    /** Average every total. */
    MEAN("mean", minimumScores = 1);

    fun divisor(count: Int): Int = if (this == TRIMMED_MEAN) count - 2 else count

    companion object {
        fun ofCode(code: String): AggregationFormula? = entries.firstOrNull { it.code == code }
    }
}

/** One judge's final vector in tenths: criteria `1..10` each and the judge's own deduction (ADR-011 §7). */
data class JudgeVector(val technique: List<Int>, val presentation: List<Int>, val deductionTenths: Int) {
    val techniqueTenths: Int
        get() = technique.sum()

    val presentationTenths: Int
        get() = presentation.sum()

    /** `J = max(0, ΣT + ΣP − D)`. */
    val totalTenths: Int
        get() = maxOf(0, techniqueTenths + presentationTenths - deductionTenths)

    /** True when the vector fits [techniqueCount] technique and 4 presentation criteria of `0.1..1.0`. */
    fun fits(techniqueCount: Int): Boolean {
        val maximum = (techniqueCount + TechnicalDiscipline.PRESENTATION_CRITERIA) * CRITERION_MAX
        return technique.size == techniqueCount &&
            presentation.size == TechnicalDiscipline.PRESENTATION_CRITERIA &&
            (technique + presentation).all { it in CRITERION_MIN..CRITERION_MAX } &&
            deductionTenths in 0..maximum && deductionTenths % DEDUCTION_STEP == 0
    }

    companion object {
        const val CRITERION_MIN = 1
        const val CRITERION_MAX = 10
        const val DEDUCTION_STEP = 3
    }
}

/** An aggregate in tenths and the divisor it was computed with. */
data class TechnicalAggregate(val valueTenths: Int, val divisor: Int)

/** The score of one performance (ADR-011 §8); [priorityTenths] and [prioritySumTenths] are the tie-break keys. */
data class TechnicalResult(
    val totalTenths: Int,
    val aggregateTenths: Int,
    val adjustmentTenths: Int,
    val divisor: Int,
    val formula: AggregationFormula,
    val priorityTenths: Int,
    val prioritySumTenths: Int,
)

/** A place in the category; [panelVote] marks entries equal in every key, decided by the panel's vote (not automated). */
data class TechnicalPlace<K>(val key: K, val place: Int, val result: TechnicalResult, val panelVote: Boolean)

/**
 * The normative calculation of the technical disciplines in integer tenths (ADR-011 §8, `TEC-007`): the client uses the
 * same arithmetic, so the same payload gives the same total on both sides.
 */
object TechnicalScoring {
    const val BONUS_TENTHS = 2
    const val PENALTY_TENTHS = -3

    /** `A = round(ΣJ / d)`, half up, over the totals the formula keeps. */
    fun aggregate(totals: List<Int>, formula: AggregationFormula): TechnicalAggregate {
        require(totals.size >= formula.minimumScores) { "${formula.code} needs ${formula.minimumScores} scores" }
        require(totals.all { it >= 0 }) { "Judge totals are never negative" }
        val kept = if (formula == AggregationFormula.TRIMMED_MEAN) totals.sorted().drop(1).dropLast(1) else totals
        val divisor = formula.divisor(totals.size)
        return TechnicalAggregate((2 * kept.sum() + divisor) / (2 * divisor), divisor)
    }

    fun result(
        discipline: TechnicalDiscipline,
        vectors: List<JudgeVector>,
        formula: AggregationFormula,
        adjustmentsTenths: List<Int>,
    ): TechnicalResult {
        val aggregate = aggregate(vectors.map(JudgeVector::totalTenths), formula)
        val adjustment = adjustmentsTenths.sum()
        val priority = vectors.map { if (discipline.presentationPriority) it.presentationTenths else it.techniqueTenths }
        return TechnicalResult(
            totalTenths = maxOf(0, aggregate.valueTenths + adjustment),
            aggregateTenths = aggregate.valueTenths,
            adjustmentTenths = adjustment,
            divisor = aggregate.divisor,
            formula = formula,
            priorityTenths = aggregate(priority, formula).valueTenths,
            prioritySumTenths = priority.sum(),
        )
    }

    /** Places by total, then the priority aggregate, then the priority sum; full ties share the place (`TEC-013`). */
    fun <K> rank(results: List<Pair<K, TechnicalResult>>): List<TechnicalPlace<K>> {
        val sorted = results.sortedWith(
            compareByDescending<Pair<K, TechnicalResult>> { it.second.totalTenths }
                .thenByDescending { it.second.priorityTenths }
                .thenByDescending { it.second.prioritySumTenths },
        )
        fun keys(result: TechnicalResult) = Triple(result.totalTenths, result.priorityTenths, result.prioritySumTenths)
        return sorted.mapIndexed { index, (key, result) ->
            val first = sorted.indexOfFirst { keys(it.second) == keys(result) }
            val tied = sorted.count { keys(it.second) == keys(result) } > 1
            TechnicalPlace(key, first + 1, result, tied)
        }
    }
}
