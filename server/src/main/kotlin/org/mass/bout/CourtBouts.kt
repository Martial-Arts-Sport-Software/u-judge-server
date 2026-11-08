package org.mass.bout

import org.mass.DeviceConnectionState
import org.mass.PairingRequests
import org.mass.RealtimeHub
import org.mass.applications.ApplicationDiscipline
import org.mass.applications.CategoryEntry
import org.mass.domain.CompetitionId
import org.mass.domain.EventId
import org.mass.domain.SessionState
import org.mass.draw.CategoryStatus
import org.mass.draw.CompetitionCourtsService
import org.mass.draw.CompetitionDrawService
import org.mass.draw.CourtItem
import org.mass.draw.CourtPanel
import org.mass.draw.CourtProgress
import org.mass.draw.DrawOutcome
import org.mass.draw.ItemRef

/** A paired device the operator can seat: approved, not revoked and connected now. */
data class ConnectedJudge(val deviceId: String, val surname: String, val platform: String)

sealed interface CourtBoutOutcome {
    data class Done(val outcome: BoutOutcome.Applied) : CourtBoutOutcome

    data class Rejected(val code: String) : CourtBoutOutcome

    /** A journal write failed; the operator sees the diagnostic (`UI-007`). */
    data class Failed(val diagnostic: String) : CourtBoutOutcome
}

/**
 * The operator side of the court: the court panel of I4b with the current and the next bout, the connected judges and
 * «Начать», which fixes the roster of connected devices, locks the category's draw on its first bout (`BRK-002`,
 * `CMP-006`) and starts the next bout. Every later operator action goes through the same realtime dispatcher as the
 * judges (ADR-009 §11).
 */
class CourtBouts(
    private val realtime: RealtimeHub,
    private val draws: CompetitionDrawService,
    private val courts: CompetitionCourtsService,
    private val pairing: PairingRequests,
) {
    val bouts: KerugiBoutService
        get() = realtime.bouts

    fun panel(): CourtPanel = courts.courtPanel()

    fun connectedJudges(): List<ConnectedJudge> = pairing.operatorRegistry().devices
        .filter { !it.revoked && it.connectionState == DeviceConnectionState.CONNECTED }
        .map { ConnectedJudge(it.deviceId, it.surname, it.platform) }

    /** «Начать» of the court panel: starts its next bout; [judges] are device IDs in seat order, all connected now. */
    fun start(judges: List<String>, quorum: Int, coincidenceWindowMillis: Long, author: String): CourtBoutOutcome {
        val board = draws.board()
        val competitionId = board.competitionId ?: return CourtBoutOutcome.Rejected(NO_COMPETITION)
        val bout = when (val next = courts.courtPanel().next) {
            null -> return CourtBoutOutcome.Rejected(NO_NEXT_BOUT)
            is CourtItem.Performance -> return CourtBoutOutcome.Rejected(NOT_KERUGI)
            is CourtItem.Bout -> next.takeIf { it.category.discipline == ApplicationDiscipline.KERUGI }
                ?: return CourtBoutOutcome.Rejected(NOT_KERUGI)
        }
        val connected = connectedJudges().associateBy(ConnectedJudge::deviceId)
        if (judges.any { it !in connected }) return CourtBoutOutcome.Rejected(JUDGE_NOT_CONNECTED)
        val spec = BoutStartSpec(
            competitionId = CompetitionId(competitionId),
            bracketId = bout.bracketId,
            categoryId = bout.category.id,
            categoryTitle = bout.category.title,
            courtNumber = bout.court,
            boutNumber = bout.number,
            round = bout.ref.round,
            index = bout.ref.index,
            blue = corner(bout.blue),
            red = corner(bout.red),
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

    companion object {
        const val NO_COMPETITION = "no_competition"
        const val NOT_CONFIRMED = "not_confirmed"
        const val JUDGE_NOT_CONNECTED = "judge_not_connected"
        const val NO_NEXT_BOUT = "no_next_bout"
        const val NOT_KERUGI = "not_kerugi"

        /** The court panel's view of the bouts: the current one (unless cancelled) and the decided ones. */
        fun progress(bouts: KerugiBoutService): CourtProgress = CourtProgress(
            current = bouts.current()?.takeIf { it.state != SessionState.CANCELLED }?.let(::refOf),
            judged = bouts.bouts().filter { it.state == SessionState.COMPLETED }.map(::refOf).toSet(),
        )

        private fun refOf(bout: KerugiBout) = ItemRef(bout.started.categoryId, bout.started.round, bout.started.index)

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
