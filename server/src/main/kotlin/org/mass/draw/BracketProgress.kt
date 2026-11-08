package org.mass.draw

import org.mass.domain.KerugiCompetitor
import org.mass.domain.SessionId

/**
 * A decided bout of a bracket (ADR-010 §6): the winning side, blue being the upper slot (ADR-008 §7), the reason and the
 * session whose events explain it (`SES-007`).
 */
data class BoutDecision(val winner: KerugiCompetitor, val reason: String, val sessionId: SessionId)

/** A round-robin line: wins and losses of an entry over its decided bouts. */
data class Standing(val key: String, val wins: Int, val losses: Int)

/**
 * The draw of one category with the results of its decided bouts, derived on every read (ADR-010 §6, `BRK-004`): the
 * winner of an elimination bout fills the `WinnerOf` slot of the next round, round robin ranks by wins and then by the
 * bout between two tied entries. [decisions] are keyed by the bout's round and index ([Bout]).
 */
class BracketProgress(val draw: CategoryDraw, val decisions: Map<Pair<Int, Int>, BoutDecision>) {
    val bouts: List<Bout> = Bouts.of(draw)
    private val byRef = bouts.associateBy { it.round to it.index }

    /** The entry key of a side, or null while the earlier bout that names it is not decided. */
    fun key(side: BoutSide): String? = when (side) {
        is BoutSide.Entry -> side.key
        is BoutSide.WinnerOf -> winner(side.round, side.index)
    }

    /** The entry key of the winner of a bout, or null while it is not decided. */
    fun winner(round: Int, index: Int): String? {
        val decision = decisions[round to index] ?: return null
        val bout = byRef[round to index] ?: return null
        return key(if (decision.winner == KerugiCompetitor.BLUE) bout.first else bout.second)
    }

    /** Every bout of the category is decided: «завершена» (`CMP-008`). */
    val decided: Boolean
        get() = bouts.isNotEmpty() && bouts.all { (it.round to it.index) in decisions }

    /** Round robin: every entry with its wins and losses, most wins first; the draw order breaks equal lines. */
    val standings: List<Standing>
        get() {
            val wins = mutableMapOf<String, Int>()
            val losses = mutableMapOf<String, Int>()
            bouts.forEach { bout ->
                val winner = winner(bout.round, bout.index) ?: return@forEach
                val loser = listOf(bout.first, bout.second).mapNotNull(::key).single { it != winner }
                wins[winner] = (wins[winner] ?: 0) + 1
                losses[loser] = (losses[loser] ?: 0) + 1
            }
            return draw.entryKeys.map { Standing(it, wins[it] ?: 0, losses[it] ?: 0) }.sortedByDescending(Standing::wins)
        }

    /**
     * «Победитель»: the winner of the final in elimination; in round robin, once every bout is decided, the entry with the
     * most wins, or of two tied entries the winner of their bout. A tie of three or more is the operator's decision outside
     * the system (ADR-007 §4), so it stays null.
     */
    val champion: String?
        get() = when (draw.format) {
            DrawFormat.ELIMINATION -> bouts.maxByOrNull(Bout::round)?.let { winner(it.round, it.index) }
            DrawFormat.ROUND_ROBIN -> if (!decided) null else {
                val lines = standings
                val leaders = lines.filter { it.wins == lines.first().wins }.map(Standing::key).toSet()
                when (leaders.size) {
                    1 -> leaders.single()
                    2 -> bouts.firstOrNull { bout -> listOf(bout.first, bout.second).mapNotNull(::key).toSet() == leaders }
                        ?.let { winner(it.round, it.index) }
                    else -> null
                }
            }
            DrawFormat.PERFORMANCE_ORDER, DrawFormat.NO_BOUTS -> null
        }
}
