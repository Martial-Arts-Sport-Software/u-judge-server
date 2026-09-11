package org.mass.domain

import java.time.Duration
import java.time.Instant

enum class KerugiCompetitor {
    BLUE,
    RED,
}

enum class KerugiScoringArea(val points: Int) {
    BODY(1),
    HEAD(2),
}

data class KerugiScoringConfiguration(
    val judges: Set<JudgeId>,
    val quorum: Int,
    val coincidenceWindow: Duration,
) {
    init {
        require(judges.size in 2..3) { "Kerugi requires two or three side judges" }
        require(quorum in 1..judges.size) { "Kerugi quorum must be between one and the configured judge count" }
        require(!coincidenceWindow.isNegative && !coincidenceWindow.isZero) {
            "Kerugi coincidence window must be positive"
        }
    }

    companion object {
        fun default(judges: Set<JudgeId>) = KerugiScoringConfiguration(
            judges = judges,
            quorum = 2,
            coincidenceWindow = Duration.ofSeconds(1),
        )
    }
}

/** A score candidate with a timestamp already adjusted to the agreed server clock. */
data class KerugiScoreCandidate(
    val eventId: EventId,
    val judgeId: JudgeId,
    val competitor: KerugiCompetitor,
    val area: KerugiScoringArea,
    val occurredAt: Instant,
)

enum class KerugiWindowDecision {
    SCORE_AWARDED,
    QUORUM_NOT_REACHED,
}

data class KerugiScoreAward(
    val competitor: KerugiCompetitor,
    val points: Int,
    val candidateEventIds: List<EventId>,
    val windowStartedAt: Instant,
)

/** Retains the input events and the decision for a scoring window for later audit. */
data class KerugiWindowAudit(
    val competitor: KerugiCompetitor,
    val candidateEventIds: List<EventId>,
    val distinctJudgeCount: Int,
    val decision: KerugiWindowDecision,
    val windowStartedAt: Instant,
)

data class KerugiScoringResult(
    val awards: List<KerugiScoreAward>,
    val audit: List<KerugiWindowAudit>,
) {
    val blueScore: Int get() = awards.filter { it.competitor == KerugiCompetitor.BLUE }.sumOf(KerugiScoreAward::points)
    val redScore: Int get() = awards.filter { it.competitor == KerugiCompetitor.RED }.sumOf(KerugiScoreAward::points)
}

/**
 * Deterministically resolves score candidates into non-overlapping per-competitor windows.
 * A window starts at its earliest candidate and includes candidates at most coincidenceWindow later.
 */
class KerugiScoringEngine(private val configuration: KerugiScoringConfiguration) {
    fun score(deliveredCandidates: Iterable<KerugiScoreCandidate>): KerugiScoringResult {
        val candidatesById = linkedMapOf<EventId, KerugiScoreCandidate>()
        deliveredCandidates.forEach { candidate ->
            require(candidate.judgeId in configuration.judges) { "Score candidate judge is not configured for this session" }
            val existing = candidatesById.putIfAbsent(candidate.eventId, candidate)
            require(existing == null || existing == candidate) { "Event ID is already assigned to a different score candidate" }
        }
        val windows = KerugiCompetitor.entries.flatMap { competitor ->
            windowsFor(candidatesById.values.filter { it.competitor == competitor })
        }.sortedWith(compareBy<KerugiWindowAudit>({ it.windowStartedAt }, { it.competitor }))

        val awardsByWindow = windows.mapNotNull { audit ->
            if (audit.decision != KerugiWindowDecision.SCORE_AWARDED) {
                null
            } else {
                val candidates = audit.candidateEventIds.map(candidatesById::getValue)
                KerugiScoreAward(
                    competitor = audit.competitor,
                    points = candidates.minOf { it.area.points },
                    candidateEventIds = audit.candidateEventIds,
                    windowStartedAt = audit.windowStartedAt,
                )
            }
        }
        return KerugiScoringResult(awardsByWindow, windows)
    }

    private fun windowsFor(candidates: List<KerugiScoreCandidate>): List<KerugiWindowAudit> {
        val pending = candidates.sortedWith(compareBy<KerugiScoreCandidate>({ it.occurredAt }, { it.eventId.value })).toMutableList()
        return buildList {
            while (pending.isNotEmpty()) {
                val first = pending.removeFirst()
                val cutoff = first.occurredAt.plus(configuration.coincidenceWindow)
                val window = buildList {
                    add(first)
                    while (pending.firstOrNull()?.occurredAt?.isAfter(cutoff) == false) {
                        add(pending.removeFirst())
                    }
                }
                add(
                    KerugiWindowAudit(
                        competitor = first.competitor,
                        candidateEventIds = window.map(KerugiScoreCandidate::eventId),
                        distinctJudgeCount = window.map(KerugiScoreCandidate::judgeId).toSet().size,
                        decision = if (window.map(KerugiScoreCandidate::judgeId).toSet().size >= configuration.quorum) {
                            KerugiWindowDecision.SCORE_AWARDED
                        } else {
                            KerugiWindowDecision.QUORUM_NOT_REACHED
                        },
                        windowStartedAt = first.occurredAt,
                    ),
                )
            }
        }
    }
}
