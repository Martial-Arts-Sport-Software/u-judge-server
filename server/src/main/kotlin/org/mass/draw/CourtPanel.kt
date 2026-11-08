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
 * - [current] is the bout in progress. Bouts start with I2a, so it is null until then; I2a's «Начать» makes [next] current.
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
