package org.mass.bout

import org.mass.DeviceConnectionState
import org.mass.PairingRequests
import org.mass.RealtimeHub
import org.mass.applications.ApplicationCategory
import org.mass.applications.ApplicationDiscipline
import org.mass.applications.CategoryEntry
import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.EventId
import org.mass.draw.Bout
import org.mass.draw.BoutSide
import org.mass.draw.Bouts
import org.mass.draw.CategoryStatus
import org.mass.draw.CompetitionDrawService
import org.mass.draw.DrawBoard
import org.mass.draw.DrawOutcome
import org.mass.draw.drawKey

/** A bout of a confirmed bracket on this court; a side is null while it waits for an earlier winner (I2b). */
data class CourtBout(
    val number: Int,
    val category: ApplicationCategory,
    val bracketId: BracketId,
    val round: Int,
    val index: Int,
    val blue: CategoryEntry?,
    val red: CategoryEntry?,
    val judged: Boolean,
) {
    /** A Kerugi bout with both athletes known that has not been decided yet. */
    val startable: Boolean
        get() = category.discipline == ApplicationDiscipline.KERUGI && blue != null && red != null && !judged
}

/** A paired device the operator can seat: approved, not revoked and connected now. */
data class ConnectedJudge(val deviceId: String, val surname: String, val platform: String)

sealed interface CourtBoutOutcome {
    data class Done(val outcome: BoutOutcome.Applied) : CourtBoutOutcome

    data class Rejected(val code: String) : CourtBoutOutcome

    /** A journal write failed; the operator sees the diagnostic (`UI-007`). */
    data class Failed(val diagnostic: String) : CourtBoutOutcome
}

/**
 * The operator side of the court: the bouts of the confirmed draws, the connected judges and «Начать», which fixes the
 * roster of connected devices, locks the category's draw on its first bout (`BRK-002`, `CMP-006`) and starts the bout.
 * Every later operator action goes through the same realtime dispatcher as the judges (ADR-009 §11).
 */
class CourtBouts(
    private val realtime: RealtimeHub,
    private val draws: CompetitionDrawService,
    private val pairing: PairingRequests,
) {
    val bouts: KerugiBoutService
        get() = realtime.bouts

    fun courtBouts(): List<CourtBout> = numbered(draws.board())

    fun connectedJudges(): List<ConnectedJudge> = pairing.operatorRegistry().devices
        .filter { !it.revoked && it.connectionState == DeviceConnectionState.CONNECTED }
        .map { ConnectedJudge(it.deviceId, it.surname, it.platform) }

    /** «Начать»: [judges] are device IDs in seat order; every one must be connected now. */
    fun start(
        bout: CourtBout,
        judges: List<String>,
        quorum: Int,
        coincidenceWindowMillis: Long,
        author: String,
    ): CourtBoutOutcome {
        val board = draws.board()
        val competitionId = board.competitionId ?: return CourtBoutOutcome.Rejected(NO_COMPETITION)
        if (!bout.startable) return CourtBoutOutcome.Rejected(BoutRejection.BOUT_ALREADY_JUDGED)
        val connected = connectedJudges().associateBy(ConnectedJudge::deviceId)
        if (judges.any { it !in connected }) return CourtBoutOutcome.Rejected(JUDGE_NOT_CONNECTED)
        val spec = BoutStartSpec(
            competitionId = CompetitionId(competitionId),
            bracketId = bout.bracketId,
            categoryId = bout.category.id,
            categoryTitle = bout.category.title,
            boutNumber = bout.number,
            round = bout.round,
            index = bout.index,
            blue = corner(checkNotNull(bout.blue)),
            red = corner(checkNotNull(bout.red)),
            judges = judges.map { RosterJudge(it, connected.getValue(it).surname) },
            quorum = quorum,
            coincidenceWindowMillis = coincidenceWindowMillis,
        )
        bouts.startRejection(spec)?.let { return CourtBoutOutcome.Rejected(it) }
        if (board.category(bout.category.id)?.status == CategoryStatus.CONFIRMED) {
            when (val locked = draws.start(bout.category.id)) {
                DrawOutcome.Done -> Unit
                is DrawOutcome.Failed -> return CourtBoutOutcome.Failed(locked.diagnostic)
                is DrawOutcome.Rejected -> return CourtBoutOutcome.Rejected(NOT_CONFIRMED)
            }
        }
        return command(author, BoutCommand.StartBout(EventId.new(), spec))
    }

    /** Any other operator action on the current bout. */
    fun command(author: String, command: BoutCommand.FromOperator): CourtBoutOutcome =
        when (val outcome = realtime.operator(author, command)) {
            is BoutOutcome.Applied -> CourtBoutOutcome.Done(outcome)
            is BoutOutcome.Rejected -> if (outcome.code == BoutRejection.JOURNAL_UNAVAILABLE) {
                CourtBoutOutcome.Failed(outcome.code)
            } else {
                CourtBoutOutcome.Rejected(outcome.code)
            }
        }

    /**
     * Numbers the bouts of the confirmed draws across categories round by round, byes unnumbered (ADR-008 §6). This is the
     * single-court numbering of the pilot desktop; the court panel of I4b part 2 replaces it with the competition-wide one.
     */
    private fun numbered(board: DrawBoard): List<CourtBout> {
        val drawn = board.categories
            .filter { it.status == CategoryStatus.CONFIRMED || it.status == CategoryStatus.STARTED }
            .mapNotNull { state ->
                val confirmed = state.confirmed ?: return@mapNotNull null
                Triple(state.category, confirmed.ownership.bracketId, Bouts.of(confirmed.draw))
            }
        val rounds = drawn.maxOfOrNull { (_, _, bouts) -> bouts.maxOfOrNull(Bout::round) ?: 0 } ?: 0
        var number = 0
        return (1..rounds).flatMap { round ->
            drawn.flatMap { (category, bracketId, categoryBouts) ->
                val entries = category.entries.associateBy { it.drawKey }
                categoryBouts.filter { it.round == round }.sortedBy(Bout::index).map { bout ->
                    CourtBout(
                        number = ++number,
                        category = category,
                        bracketId = bracketId,
                        round = bout.round,
                        index = bout.index,
                        blue = (bout.first as? BoutSide.Entry)?.let { entries[it.key] },
                        red = (bout.second as? BoutSide.Entry)?.let { entries[it.key] },
                        judged = bouts.completed(bracketId, bout.round, bout.index),
                    )
                }
            }
        }
    }

    companion object {
        const val NO_COMPETITION = "no_competition"
        const val NOT_CONFIRMED = "not_confirmed"
        const val JUDGE_NOT_CONNECTED = "judge_not_connected"

        /** «Иванов И. И. (Москва)», as in the organiser's protocols; a pair joins its athletes with a slash. */
        fun label(entry: CategoryEntry): String = entry.athletes.joinToString(" / ") { athlete ->
            val parts = athlete.fullName.trim().split(Regex("\\s+"))
            val name = (listOf(parts.first()) + parts.drop(1).map { "${it.first()}." }).joinToString(" ")
            if (athlete.city.isBlank()) name else "$name (${athlete.city.trim()})"
        }

        fun corner(entry: CategoryEntry) = BoutCorner(
            entryId = entry.id,
            label = label(entry),
            athletes = entry.athletes.map { BoutAthlete(it.fullName.trim(), it.city.trim()) },
        )
    }
}
