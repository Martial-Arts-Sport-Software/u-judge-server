package org.mass.draw

import kotlinx.serialization.Serializable

/**
 * The courts of a competition (ADR-008 §1): [courts] holds the category IDs of every court in the operator's order, court 1
 * first. A category is on exactly one court.
 */
@Serializable
data class CourtPlan(val courts: List<List<String>>) {
    init {
        require(courts.size in 1..MAX_COURTS) { "A competition has 1..$MAX_COURTS courts" }
        val all = courts.flatten()
        require(all.size == all.toSet().size) { "A category is on exactly one court" }
    }

    val count: Int
        get() = courts.size

    /** The court of [categoryId], counted from 1, or null for a category the plan does not know. */
    fun courtOf(categoryId: String): Int? = courts.indexOfFirst { categoryId in it }.takeIf { it >= 0 }?.plus(1)

    /**
     * The plan for the categories of the current import: categories that are gone are dropped, new ones join court 1 in
     * import order, so a competition without a plan has every category on court 1 in import order.
     */
    fun resolve(categoryIds: List<String>): CourtPlan {
        val known = categoryIds.toSet()
        val kept = courts.map { court -> court.filter { it in known } }
        val placed = kept.flatten().toSet()
        val missing = categoryIds.filter { it !in placed }
        return CourtPlan(kept.mapIndexed { index, court -> if (index == 0) court + missing else court })
    }

    /** [count] courts; the categories of removed courts move to the end of the last remaining court in their order. */
    fun withCount(count: Int): CourtPlan {
        require(count in 1..MAX_COURTS) { "A competition has 1..$MAX_COURTS courts" }
        return when {
            count >= courts.size -> CourtPlan(courts + List(count - courts.size) { emptyList() })
            else -> CourtPlan(courts.take(count - 1) + listOf(courts.drop(count - 1).flatten()))
        }
    }

    /** [categoryId] on [court] at [position] of its order, clamped to the court's length. */
    fun place(categoryId: String, court: Int, position: Int): CourtPlan {
        require(court in 1..count) { "No court $court" }
        val without = courts.map { it - categoryId }
        val target = without[court - 1].toMutableList()
        target.add(position.coerceIn(0, target.size), categoryId)
        return CourtPlan(without.mapIndexed { index, categories -> if (index == court - 1) target else categories })
    }

    companion object {
        const val MAX_COURTS = 10

        /** One court with every category in import order: the default of ADR-008 §1. */
        fun single(categoryIds: List<String>) = CourtPlan(listOf(categoryIds))
    }
}

/**
 * The seal and the signatures printed on the protocols (ADR-012 §5): PNG or JPEG of at most [MAX_BYTES] each, Base64 in the
 * journal so a backup carries them; null where none is set.
 */
@Serializable
data class ProtocolImages(
    val seal: String? = null,
    val chiefJudgeSignature: String? = null,
    val chiefSecretarySignature: String? = null,
) {
    val all: List<String?>
        get() = listOf(seal, chiefJudgeSignature, chiefSecretarySignature)

    /** Every set image decodes from Base64, is PNG or JPEG and fits [MAX_BYTES]. */
    val valid: Boolean
        get() = all.filterNotNull().all { image -> decode(image)?.let(::isImage) == true }

    companion object {
        const val MAX_BYTES = 1_048_576

        fun decode(image: String?): ByteArray? = image?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }

        fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

        /** PNG or JPEG by its signature, readable as an image and not larger than [MAX_BYTES]. */
        fun isImage(bytes: ByteArray): Boolean {
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return false
            val png = bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            val jpeg = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            return (png || jpeg) && runCatching { javax.imageio.ImageIO.read(bytes.inputStream()) != null }.getOrDefault(false)
        }
    }
}

/**
 * Competition settings printed on the protocols (ADR-008 §8): the place and the names of the chief judge and the chief
 * secretary. They are not part of the applications; empty until the operator enters them.
 */
@Serializable
data class CompetitionOfficials(val place: String = "", val chiefJudge: String = "", val chiefSecretary: String = "") {
    /** The settings without surrounding and repeated whitespace, as they are journaled. */
    fun normalised() = CompetitionOfficials(place.clean(), chiefJudge.clean(), chiefSecretary.clean())

    val valid: Boolean
        get() = listOf(place, chiefJudge, chiefSecretary).all { it.length <= MAX_LENGTH }

    private fun String.clean() = trim().replace(Regex("\\s+"), " ")

    companion object {
        const val MAX_LENGTH = 200
    }
}

/**
 * A bout or a performance of a category: elimination and round-robin bouts by their round and their index within the round
 * ([Bout]), performances of technical disciplines in round 1 by their place in the performance order.
 */
@Serializable
data class ItemRef(val categoryId: String, val round: Int, val index: Int)

/**
 * One numbering across all courts (ADR-008 §6), derived from the confirmed draws and never stored: round 1 of every category
 * (courts in order, categories of a court in the operator's order), then round 2, and so on. Round-robin rounds interleave
 * the same way, byes have no bout and so no number, and technical performances take numbers from the same sequence in round 1.
 */
object BoutNumbering {
    /**
     * Numbers the items of [courts], each a list of (category ID, confirmed draw) in the operator's order. Items of [frozen]
     * keep their numbers (the numbering at the first start, I2a); items that are not in it follow its highest number in the
     * same order.
     */
    fun number(courts: List<List<Pair<String, CategoryDraw>>>, frozen: Map<ItemRef, Int> = emptyMap()): Map<ItemRef, Int> {
        // A stable sort by round keeps the court, category and index order inside every round.
        val ordered = courts.flatten().flatMap { (categoryId, draw) -> items(categoryId, draw) }.sortedBy { it.round }
        val numbers = LinkedHashMap<ItemRef, Int>()
        var next = (frozen.values.maxOrNull() ?: 0) + 1
        ordered.forEach { ref -> numbers[ref] = frozen[ref] ?: next++ }
        return numbers
    }

    /** The bouts or performances a draw defines, round by round. */
    fun items(categoryId: String, draw: CategoryDraw): List<ItemRef> = when (draw.format) {
        DrawFormat.ELIMINATION, DrawFormat.ROUND_ROBIN -> Bouts.of(draw).map { ItemRef(categoryId, it.round, it.index) }
        DrawFormat.PERFORMANCE_ORDER -> draw.entryKeys.indices.map { ItemRef(categoryId, 1, it) }
        DrawFormat.NO_BOUTS -> emptyList()
    }
}
