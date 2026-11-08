package org.mass.draw

import org.mass.applications.ApplicationCategory
import org.mass.applications.CategoryEntry
import org.mass.domain.BracketId

/**
 * A numbered bout or performance of a court whose participants are known (ADR-008 §9). [ref] and [bracketId] address it for
 * the start of I2a: `CompetitionDrawService.start(ref.categoryId)` locks the category on its first start.
 */
sealed interface CourtItem {
    val ref: ItemRef
    val number: Int
    val court: Int
    val category: ApplicationCategory
    val bracketId: BracketId

    /** A Kerugi bout: [blue] is the upper slot of the pair, [red] the lower one, as the sides of the client. */
    data class Bout(
        override val ref: ItemRef,
        override val number: Int,
        override val court: Int,
        override val category: ApplicationCategory,
        override val bracketId: BracketId,
        val blue: CategoryEntry,
        val red: CategoryEntry,
    ) : CourtItem

    /** A performance of a technical discipline in its performance order. */
    data class Performance(
        override val ref: ItemRef,
        override val number: Int,
        override val court: Int,
        override val category: ApplicationCategory,
        override val bracketId: BracketId,
        val entry: CategoryEntry,
    ) : CourtItem
}

/**
 * The court panel of this desktop (ADR-008 §9, `SES-003`): the right column of the operator screen.
 *
 * - [current] is the bout in progress or the last one, until «Начать» (I2a) makes [next] current; decided bouts leave [queue].
 * - [next] is the operator's pick when it is still waiting, otherwise the lowest number of [queue]; [nextSelected] tells
 *   which of the two it is.
 * - [queue] are the confirmed bouts and performances of [court] whose participants are known, in number order. Later
 *   elimination rounds join it once the results name their participants (I2b).
 */
data class CourtPanel(
    val court: Int,
    val current: CourtItem?,
    val next: CourtItem?,
    val nextSelected: Boolean,
    val queue: List<CourtItem>,
    /** Not every category is confirmed yet: the numbers may still change. */
    val preliminary: Boolean,
) {
    companion object {
        val Empty = CourtPanel(court = 1, current = null, next = null, nextSelected = false, queue = emptyList(), preliminary = false)
    }
}

/**
 * What the bouts of I2a report to the court panel: the bout in progress and the bouts already decided, which leave the
 * queue.
 */
data class CourtProgress(val current: ItemRef?, val judged: Set<ItemRef>) {
    companion object {
        val None = CourtProgress(current = null, judged = emptySet())
    }
}
