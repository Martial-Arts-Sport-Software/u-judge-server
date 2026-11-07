package org.mass.draw

/** A bout of a drawn category, addressed by its round (1 is the first) and its index within the round. */
data class Bout(val round: Int, val index: Int, val first: BoutSide, val second: BoutSide)

sealed interface BoutSide {
    /** An entry known from the draw. */
    data class Entry(val key: String) : BoutSide

    /** The winner of an earlier bout of the same category. */
    data class WinnerOf(val round: Int, val index: Int) : BoutSide
}

/**
 * The bouts a draw defines, round by round. Elimination byes pass an entry to the next round without a bout, as the
 * organiser's protocols draw it in the second-round column; round robin pairs follow the circle method.
 */
object Bouts {
    fun of(draw: CategoryDraw): List<Bout> = when (draw.format) {
        DrawFormat.ELIMINATION -> elimination(draw.positions)
        DrawFormat.ROUND_ROBIN -> roundRobin(draw.entryKeys)
        DrawFormat.PERFORMANCE_ORDER, DrawFormat.NO_BOUTS -> emptyList()
    }

    /** The number of elimination rounds, or of round-robin rounds. */
    fun rounds(draw: CategoryDraw): Int = when (draw.format) {
        DrawFormat.ELIMINATION -> Integer.numberOfTrailingZeros(draw.positions.size)
        DrawFormat.ROUND_ROBIN -> draw.entryKeys.size.let { if (it % 2 == 0) it - 1 else it }
        DrawFormat.PERFORMANCE_ORDER, DrawFormat.NO_BOUTS -> 0
    }

    private fun elimination(positions: List<String?>): List<Bout> {
        val bouts = mutableListOf<Bout>()
        var nodes: List<BoutSide?> = positions.map { key -> key?.let(BoutSide::Entry) }
        var round = 1
        while (nodes.size > 1) {
            var index = 0
            nodes = nodes.chunked(2).map { (first, second) ->
                when {
                    first != null && second != null -> {
                        bouts += Bout(round, index, first, second)
                        BoutSide.WinnerOf(round, index++)
                    }
                    else -> first ?: second
                }
            }
            round++
        }
        return bouts
    }

    /** Circle method: the first entry stays, the others rotate; with an odd count one entry rests each round. */
    private fun roundRobin(keys: List<String>): List<Bout> {
        val ring: MutableList<String?> = keys.toMutableList<String?>().apply { if (size % 2 == 1) add(null) }
        val bouts = mutableListOf<Bout>()
        repeat(ring.size - 1) { roundIndex ->
            var index = 0
            (0 until ring.size / 2).forEach { pair ->
                val first = ring[pair]
                val second = ring[ring.size - 1 - pair]
                if (first != null && second != null) {
                    bouts += Bout(roundIndex + 1, index++, BoutSide.Entry(first), BoutSide.Entry(second))
                }
            }
            ring.add(1, ring.removeLast())
        }
        return bouts
    }
}
