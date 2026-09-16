package org.mass.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

enum class KerugiVictoryReason { FINAL_SCORE, GOLDEN_ROUND }

@Serializable
data class KerugiResultPayload(val winner: String, val reason: String)

data class KerugiResultProjection(
    val sessionId: SessionId,
    val winner: KerugiCompetitor? = null,
    val reason: KerugiVictoryReason? = null,
) {
    companion object {
        fun initial(sessionId: SessionId) = KerugiResultProjection(sessionId)
    }
}

sealed interface KerugiResultJournalResult {
    class Applied(val event: SequencedDomainEvent, val projection: KerugiResultProjection, val isNew: Boolean = true) : KerugiResultJournalResult
    data class Rejected(val reason: String, val diagnosticContext: DiagnosticContext) : KerugiResultJournalResult
}

interface KerugiResultEventJournal {
    fun apply(command: DomainCommand, eventId: EventId): KerugiResultJournalResult
    fun events(): List<SequencedDomainEvent>
    fun projection(): KerugiResultProjection
}

class KerugiResultJournal(
    private val ownership: BracketOwnership,
    private val sessionId: SessionId,
    private val sequence: PeerEventSequence = PeerEventSequence(ownership.ownerPeerId),
    private val now: () -> Instant = Instant::now,
) : KerugiResultEventJournal {
    private val eventsById = linkedMapOf<EventId, SequencedDomainEvent>()
    private val projectionsByEventId = mutableMapOf<EventId, KerugiResultProjection>()
    private var currentProjection = KerugiResultProjection.initial(sessionId)

    override fun apply(command: DomainCommand, eventId: EventId): KerugiResultJournalResult {
        val event = command.toEvent(eventId, now())
        eventsById[eventId]?.let { existing ->
            return if (sameCommand(existing.event, event)) KerugiResultJournalResult.Applied(existing, projectionsByEventId.getValue(eventId), false)
            else rejected("Event ID is already assigned to a different command", event)
        }
        val next = try { applyEvent(currentProjection, event, ownership, sessionId) } catch (error: IllegalArgumentException) {
            return rejected(error.message ?: "Invalid Kerugi result command", event)
        }
        val sequenced = SequencedDomainEvent(event, sequence.next(event.peerId))
        eventsById[eventId] = sequenced
        projectionsByEventId[eventId] = next
        currentProjection = next
        return KerugiResultJournalResult.Applied(sequenced, next)
    }

    override fun events(): List<SequencedDomainEvent> = eventsById.values.toList()
    override fun projection(): KerugiResultProjection = currentProjection

    private fun rejected(reason: String, event: DomainEvent) = KerugiResultJournalResult.Rejected(reason, event.diagnosticContext())

    companion object {
        const val KERUGI_RESULT_EVENT = "kerugi_result_recorded"

        fun rebuild(ownership: BracketOwnership, sessionId: SessionId, events: Collection<SequencedDomainEvent>): KerugiResultProjection {
            val byId = linkedMapOf<EventId, SequencedDomainEvent>()
            events.forEach { event -> require(byId.putIfAbsent(event.event.eventId, event) in setOf(null, event)) { "Event ID is already assigned to a different event" } }
            return DomainEventOrder.order(byId.values).fold(KerugiResultProjection.initial(sessionId)) { projection, event ->
                applyEvent(projection, event.event, ownership, sessionId)
            }
        }

        fun applyEvent(
            projection: KerugiResultProjection,
            event: DomainEvent,
            ownership: BracketOwnership,
            sessionId: SessionId,
        ): KerugiResultProjection {
            require(event.bracketId == ownership.bracketId && event.sessionId == sessionId) { "Command does not target this session" }
            require(event.peerId == ownership.ownerPeerId && ownership.state == BracketState.IN_PROGRESS) {
                "Only the in-progress bracket owner can record its result"
            }
            require(event.source.value == "operator") { "Kerugi result command must have an operator source" }
            require(event.type == KERUGI_RESULT_EVENT) { "Unsupported Kerugi result command" }
            require(projection.winner == null) { "Kerugi result is already recorded" }
            val payload = try { Json.decodeFromString<KerugiResultPayload>(event.payload) } catch (_: Exception) {
                throw IllegalArgumentException("Invalid Kerugi result payload")
            }
            val winner = try { KerugiCompetitor.valueOf(payload.winner) } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid Kerugi winner")
            }
            val reason = try { KerugiVictoryReason.valueOf(payload.reason) } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid Kerugi victory reason")
            }
            return KerugiResultProjection(sessionId, winner, reason)
        }

        fun sameCommand(existing: DomainEvent, candidate: DomainEvent): Boolean = existing.copy(occurredAt = candidate.occurredAt) == candidate
    }
}
