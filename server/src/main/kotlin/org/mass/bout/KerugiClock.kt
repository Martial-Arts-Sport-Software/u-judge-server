package org.mass.bout

import kotlinx.serialization.Serializable
import org.mass.applications.AgeClass
import java.time.Duration
import java.time.Instant

/** The durations of a bout, fixed at «Начать» from the category's age class (FHR 2024 §4.1.2, ADR-010 §1). */
@Serializable
data class BoutTiming(
    val roundMillis: Long = 120_000,
    val breakMillis: Long = 60_000,
    val goldenBreakMillis: Long = 30_000,
    val goldenRoundMillis: Long = 60_000,
) {
    companion object {
        fun of(ageClass: AgeClass): BoutTiming = when (ageClass) {
            AgeClass.YOUNGER -> BoutTiming(roundMillis = 60_000)
            AgeClass.YOUTH -> BoutTiming(roundMillis = 90_000)
            AgeClass.JUNIORS, AgeClass.ADULTS -> BoutTiming(roundMillis = 120_000)
        }
    }
}

enum class BoutPeriod { ROUND, BREAK }

/** What the snapshot's `timer` carries (ADR-010 §5); [remainingMillis] is the time left at [runningSince] while running. */
data class BoutTimer(
    val period: BoutPeriod,
    val round: Int,
    val golden: Boolean,
    val durationMillis: Long,
    val remainingMillis: Long,
    val runningSince: Instant?,
)

/**
 * The clock of a bout (ADR-010 §2): the periods `round 1 → break → round 2 → [rest → golden round]` as phases 0..4, the
 * remaining time of the current phase and the intervals in which a round clock ran. Immutable; [KerugiBout] folds the
 * journaled events into it, so a rebuild from the journal gives the same clock.
 *
 * While the clock runs, [remainingMillis] is the time left at [runningSince]; once stopped it is exact.
 */
data class KerugiClock(
    val timing: BoutTiming,
    val phase: Int = 0,
    val remainingMillis: Long = timing.roundMillis,
    val runningSince: Instant? = null,
    private val roundIntervals: List<Pair<Instant, Instant>> = emptyList(),
) {
    val period: BoutPeriod
        get() = if (phase % 2 == 0) BoutPeriod.ROUND else BoutPeriod.BREAK

    /** The round being fought, or for a break the round that follows it. */
    val round: Int
        get() = (phase + 1) / 2 + 1

    /** Round 3 and the rest before it. */
    val golden: Boolean
        get() = phase >= GOLDEN_BREAK

    val durationMillis: Long
        get() = when (phase) {
            ROUND_1, ROUND_2 -> timing.roundMillis
            BREAK -> timing.breakMillis
            GOLDEN_BREAK -> timing.goldenBreakMillis
            else -> timing.goldenRoundMillis
        }

    val running: Boolean
        get() = runningSince != null

    /** A round clock runs: the session is `running` exactly then. */
    val roundRunning: Boolean
        get() = running && period == BoutPeriod.ROUND

    /** When the running clock reaches zero. */
    val deadline: Instant?
        get() = runningSince?.plusMillis(remainingMillis)

    /** Round 2 ended without a tie, or the golden round ended: only the result is left. */
    val over: Boolean
        get() = (phase == ROUND_2 || phase == GOLDEN_ROUND) && !running && remainingMillis == 0L

    /** Round 2 has ended (also when the golden round followed). */
    val regularTimeOver: Boolean
        get() = phase > ROUND_2 || (phase == ROUND_2 && over)

    fun timer() = BoutTimer(period, round, golden, durationMillis, remainingMillis, runningSince)

    fun remainingAt(instant: Instant): Long {
        val since = runningSince ?: return remainingMillis
        return (remainingMillis - Duration.between(since, instant).toMillis()).coerceIn(0, remainingMillis)
    }

    /**
     * A judge tap at [instant] lies in fighting time: inside an interval in which a round clock ran, up to and including
     * the moment it stopped, never after its deadline.
     */
    fun counts(instant: Instant): Boolean {
        val open = if (roundRunning) listOf(checkNotNull(runningSince) to checkNotNull(deadline)) else emptyList()
        return (roundIntervals + open).any { (start, end) -> !instant.isBefore(start) && !instant.isAfter(end) }
    }

    val canPause: Boolean
        get() = roundRunning

    val canResume: Boolean
        get() = when (period) {
            BoutPeriod.ROUND -> !running && remainingMillis > 0
            BoutPeriod.BREAK -> true
        }

    val canEndPeriod: Boolean
        get() = running || remainingMillis > 0

    val canAdjust: Boolean
        get() = !running && period == BoutPeriod.ROUND && remainingMillis > 0

    fun start(at: Instant): KerugiClock = copy(phase = ROUND_1, remainingMillis = timing.roundMillis, runningSince = at)

    /** Stops the clock; a stop at or after the deadline ends the period at the deadline. */
    fun stop(at: Instant, tied: () -> Boolean): KerugiClock {
        val since = runningSince ?: return this
        val end = minOf(at, checkNotNull(deadline))
        val remaining = remainingAt(end)
        return if (remaining == 0L) {
            endPeriod(end, tied)
        } else {
            copy(remainingMillis = remaining, runningSince = null, roundIntervals = closed(since, end))
        }
    }

    /** Stops the clock without moving to another period: the completion or the cancellation of the bout. */
    fun halt(at: Instant): KerugiClock {
        val since = runningSince ?: return this
        val end = minOf(at, checkNotNull(deadline))
        return copy(remainingMillis = remainingAt(end), runningSince = null, roundIntervals = closed(since, end))
    }

    /** Ends the current period at [at] (or at the deadline when that is earlier) and starts the break that follows a round. */
    fun endPeriod(at: Instant, tied: () -> Boolean): KerugiClock {
        val end = deadline?.let { minOf(at, it) } ?: at
        val intervals = runningSince?.let { closed(it, end) } ?: roundIntervals
        val ended = copy(remainingMillis = 0, runningSince = null, roundIntervals = intervals)
        return when (phase) {
            ROUND_1 -> ended.copy(phase = BREAK, remainingMillis = timing.breakMillis, runningSince = end)
            ROUND_2 -> if (tied()) ended.copy(phase = GOLDEN_BREAK, remainingMillis = timing.goldenBreakMillis, runningSince = end) else ended
            else -> ended
        }
    }

    /** Runs the stopped round, or ends the break and runs the round after it. */
    fun resume(at: Instant): KerugiClock = when (period) {
        BoutPeriod.ROUND -> copy(runningSince = at)
        BoutPeriod.BREAK -> copy(phase = phase + 1, remainingMillis = 0, runningSince = null).let {
            it.copy(remainingMillis = it.durationMillis, runningSince = at)
        }
    }

    fun adjust(remainingMillis: Long): KerugiClock = copy(remainingMillis = remainingMillis)

    private fun closed(since: Instant, end: Instant) =
        if (period == BoutPeriod.ROUND && end.isAfter(since)) roundIntervals + (since to end) else roundIntervals

    companion object {
        const val ROUND_1 = 0
        const val BREAK = 1
        const val ROUND_2 = 2
        const val GOLDEN_BREAK = 3
        const val GOLDEN_ROUND = 4
    }
}
