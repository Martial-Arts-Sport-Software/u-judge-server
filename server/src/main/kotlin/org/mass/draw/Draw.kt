package org.mass.draw

import kotlinx.serialization.Serializable
import org.mass.applications.ApplicationCategory
import org.mass.applications.ApplicationDiscipline
import org.mass.applications.CategoryEntry
import kotlin.random.Random

/** Bracket systems the operator can choose for a Kerugi category (ADR-007 §3-4). */
enum class DrawSystem { SINGLE_ELIMINATION, ROUND_ROBIN }

/** What the draw of one category produced. */
enum class DrawFormat {
    /** [CategoryDraw.positions] are the first-round slots of a power-of-two bracket, null for a bye. */
    ELIMINATION,

    /** [CategoryDraw.positions] is the circle-method order of the entries. */
    ROUND_ROBIN,

    /** Technical disciplines: [CategoryDraw.positions] is the performance order. */
    PERFORMANCE_ORDER,

    /** A single entry: first place without a bout. */
    NO_BOUTS,
}

/** The value entries are separated by (FHR 2024 §1.4, ADR-007 §5). */
enum class SeparationCriterion { REGION, CITY, ORGANISATION, COACH }

/**
 * The method of the whole competition: random when [criterion] is null, otherwise separation by [criterion], optionally
 * only among entries of the same belt (`гып`), as HapkiPRO's «пояс + тренер» (ADR-008 §2).
 */
@Serializable
data class DrawMethod(val criterion: SeparationCriterion? = null, val withBelt: Boolean = false) {
    init {
        require(criterion != null || !withBelt) { "The belt only narrows a separation criterion" }
    }
}

/** A confirmed or previewed draw of one category; entries are referenced by [drawKey]. */
@Serializable
data class CategoryDraw(val format: DrawFormat, val positions: List<String?>) {
    val entryKeys: List<String>
        get() = positions.filterNotNull()

    /** The draw with the entries [first] and [second] in each other's positions (ADR-007 §7). */
    fun swap(first: String, second: String): CategoryDraw {
        val firstIndex = positions.indexOf(first)
        val secondIndex = positions.indexOf(second)
        require(firstIndex >= 0 && secondIndex >= 0) { "Both entries must be in the draw" }
        val swapped = positions.toMutableList()
        swapped[firstIndex] = second
        swapped[secondIndex] = first
        return copy(positions = swapped)
    }
}

/**
 * Two entries of the same origin that can meet in the first bout of either of them before the final (elimination) or
 * perform one after another (performance order): separation could not avoid it, so the preview marks it (ADR-007 §5).
 */
data class Clash(val first: String, val second: String)

/**
 * The stable identity of an entry across imports: its athletes' names and birth dates. Entry IDs change when a file is
 * re-imported; this key only changes when the athletes do, so a draw stays valid for an unchanged category.
 */
val CategoryEntry.drawKey: String
    get() = athletes.joinToString(" + ") { "${it.fullName.trim()} ${it.birthDate}" }

/**
 * The draw by FHR 2024 §1.2 and §1.4 (ADR-007): a pure function of the category, the system, the method and the seed, so a
 * journaled seed replays the same draw.
 */
object Draw {
    fun draw(category: ApplicationCategory, system: DrawSystem, method: DrawMethod, seed: Long): CategoryDraw {
        val random = Random(seed)
        val keys = category.entries.map { it.drawKey }
        val groups = groups(category, method)
        return when {
            category.discipline != ApplicationDiscipline.KERUGI ->
                CategoryDraw(DrawFormat.PERFORMANCE_ORDER, performanceOrder(keys, groups, random))
            keys.size == 1 -> CategoryDraw(DrawFormat.NO_BOUTS, keys)
            keys.size == 2 || system == DrawSystem.SINGLE_ELIMINATION ->
                CategoryDraw(DrawFormat.ELIMINATION, elimination(keys, groups, random))
            else -> CategoryDraw(DrawFormat.ROUND_ROBIN, keys.shuffled(random))
        }
    }

    /** The system a category gets unless the operator changes it: round robin for 3-5 entries (ADR-008 §3). */
    fun defaultSystem(category: ApplicationCategory): DrawSystem =
        if (category.entries.size in 3..5) DrawSystem.ROUND_ROBIN else DrawSystem.SINGLE_ELIMINATION

    /** The entries that separation could not keep apart; empty for a random draw. */
    fun clashes(category: ApplicationCategory, draw: CategoryDraw, method: DrawMethod): List<Clash> {
        val groups = groups(category, method)
        if (groups.isEmpty()) return emptyList()
        fun same(first: String, second: String) = groups[first] != null && groups[first] == groups[second]
        return when (draw.format) {
            DrawFormat.ELIMINATION -> {
                val final = Integer.numberOfTrailingZeros(draw.positions.size)
                firstOpponents(draw.positions)
                    .filter { (pair, round) -> round < final && same(pair.first, pair.second) }
                    .map { (pair, _) -> Clash(pair.first, pair.second) }
            }
            DrawFormat.PERFORMANCE_ORDER -> draw.entryKeys.zipWithNext()
                .filter { (first, second) -> same(first, second) }
                .map { (first, second) -> Clash(first, second) }
            DrawFormat.ROUND_ROBIN, DrawFormat.NO_BOUTS -> emptyList()
        }
    }

    /** The separation value of every entry with one; entries without a value are never separated. */
    private fun groups(category: ApplicationCategory, method: DrawMethod): Map<String, String> {
        val criterion = method.criterion ?: return emptyMap()
        return category.entries.mapNotNull { entry ->
            val athlete = entry.athletes.first()
            val value = when (criterion) {
                SeparationCriterion.REGION -> athlete.region
                SeparationCriterion.CITY -> athlete.city
                // The club or school; the sports society when the school is not filled in.
                SeparationCriterion.ORGANISATION -> athlete.school.ifBlank { athlete.society }
                // The first coach listed (decision of 2026-09-27).
                SeparationCriterion.COACH -> athlete.coaches.firstOrNull().orEmpty()
            }.normalised()
            if (value.isEmpty()) return@mapNotNull null
            val key = if (method.withBelt) "$value|${athlete.technicalQualification.normalised()}" else value
            entry.drawKey to key
        }.toMap()
    }

    private fun String.normalised() = trim().lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ")

    /**
     * Single elimination: the bracket is the next power of two; entries take the slots of seeds 1..n in the standard seed
     * order, so byes (the slots of the missing seeds) never meet each other and spread evenly over halves and quarters.
     * Separation places the largest groups first, each entry where it meets its own group as late as possible and, among
     * equally good slots, as far as possible from entries of other groups, so the later groups still find free sections.
     */
    private fun elimination(keys: List<String>, groups: Map<String, String>, random: Random): List<String?> {
        val size = Integer.highestOneBit(maxOf(2, keys.size) * 2 - 1)
        val seedSlots = seedOrder(size).withIndex().sortedBy { it.value }.map { it.index }
        val free = seedSlots.take(keys.size).toMutableList()
        val slots = arrayOfNulls<String>(size)

        val grouped = groups.entries.groupBy({ it.value }, { it.key }).values
            .shuffled(random)
            .sortedByDescending { it.size }
        val placedGrouped = mutableListOf<Int>()
        grouped.forEach { members ->
            val placed = mutableListOf<Int>()
            members.shuffled(random).forEach { key ->
                fun latest(slot: Int, others: List<Int>) = others.minOfOrNull { meetingRound(slot, it) } ?: Int.MAX_VALUE
                val own = free.maxOf { latest(it, placed) }
                val candidates = free.filter { latest(it, placed) == own }
                val spread = candidates.maxOf { latest(it, placedGrouped) }
                val slot = candidates.filter { latest(it, placedGrouped) == spread }.random(random)
                slots[slot] = key
                placed += slot
                placedGrouped += slot
                free -= slot
            }
        }
        keys.filter { it !in groups }.shuffled(random).forEach { key ->
            val slot = free.random(random)
            slots[slot] = key
            free -= slot
        }
        if (groups.isNotEmpty()) improve(slots, groups)
        return slots.toList()
    }

    /**
     * Greedy placement can leave a later group without free sections, so pairs of entries swap while that lowers the
     * separation penalty. Deterministic for the placement it starts from, so the seed still replays the draw.
     */
    private fun improve(slots: Array<String?>, groups: Map<String, String>) {
        val occupied = slots.indices.filter { slots[it] != null }
        var cost = separationPenalty(slots, groups)
        var improved = true
        while (improved && cost > 0) {
            improved = false
            for (first in occupied.indices) {
                for (second in first + 1 until occupied.size) {
                    val a = occupied[first]
                    val b = occupied[second]
                    if (groups[slots[a]] == groups[slots[b]]) continue
                    slots[a] = slots[b].also { slots[b] = slots[a] }
                    val swapped = separationPenalty(slots, groups)
                    if (swapped < cost) {
                        cost = swapped
                        improved = true
                    } else {
                        slots[a] = slots[b].also { slots[b] = slots[a] }
                    }
                }
            }
        }
    }

    /**
     * For every pair of one group: how much earlier than possible for the group's size they can meet. A group of two can be
     * kept apart until the final, three or four until the semifinals, and so on; each round earlier weighs eight times more.
     */
    internal fun separationPenalty(slots: Array<String?>, groups: Map<String, String>): Long {
        val rounds = Integer.numberOfTrailingZeros(slots.size)
        return slots.indices.filter { slots[it] in groups }.groupBy { groups.getValue(slots[it]!!) }.values.sumOf { members ->
            if (members.size < 2) return@sumOf 0L
            val sections = Integer.highestOneBit(members.size * 2 - 1)
            val ideal = maxOf(1, rounds - Integer.numberOfTrailingZeros(sections) + 1)
            var penalty = 0L
            for (i in members.indices) for (j in i + 1 until members.size) {
                val earlier = ideal - meetingRound(members[i], members[j])
                if (earlier > 0) penalty += 1L shl (3 * earlier)
            }
            penalty
        }
    }

    /** Slot → seed number of a standard bracket of [size]: `1, 8, 4, 5, 2, 7, 3, 6` for 8. */
    internal fun seedOrder(size: Int): List<Int> {
        var order = listOf(1)
        while (order.size < size) {
            val next = order.size * 2
            order = order.flatMap { listOf(it, next + 1 - it) }
        }
        return order
    }

    /** The round in which the entries of two slots can meet: 1 for a first-round pair, `log2(size)` for the final. */
    internal fun meetingRound(first: Int, second: Int): Int = 32 - Integer.numberOfLeadingZeros(first xor second)

    /**
     * Pairs of entries that can meet in the first bout of either of them, with the round of that bout; a bye moves the first
     * bout to a later round.
     */
    internal fun firstOpponents(positions: List<String?>): Map<Pair<String, String>, Int> {
        val pairs = mutableMapOf<Pair<String, String>, Int>()
        positions.forEachIndexed { slot, key ->
            if (key == null) return@forEachIndexed
            var block = 1
            while (block < positions.size) {
                val sibling = ((slot / block) xor 1) * block
                val opponents = positions.subList(sibling, sibling + block).filterNotNull()
                if (opponents.isNotEmpty()) {
                    val round = Integer.numberOfTrailingZeros(block) + 1
                    opponents.forEach { pairs[if (key < it) key to it else it to key] = round }
                    break
                }
                block *= 2
            }
        }
        return pairs
    }

    /**
     * A random performance order; with separation the next entry comes from the largest remaining group other than the
     * previous entry's, so one origin performs back to back only when it has more than half of the entries.
     */
    private fun performanceOrder(keys: List<String>, groups: Map<String, String>, random: Random): List<String> {
        if (groups.isEmpty()) return keys.shuffled(random)
        val remaining = keys.shuffled(random).groupBy { groups[it] ?: "\u0000$it" }.mapValues { it.value.toMutableList() }
        val order = mutableListOf<String>()
        var previous: String? = null
        while (order.size < keys.size) {
            val open = remaining.filterValues { it.isNotEmpty() }
            val candidates = open.keys.filter { it != previous }.ifEmpty { open.keys.toList() }
            val largest = candidates.maxOf { open.getValue(it).size }
            val group = candidates.filter { open.getValue(it).size == largest }.random(random)
            order += remaining.getValue(group).removeLast()
            previous = group
        }
        return order
    }
}
