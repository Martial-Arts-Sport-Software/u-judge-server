package org.mass.draw

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mass.domain.CompetitionId
import org.mass.domain.EventId
import org.mass.domain.EventSource
import org.mass.domain.PeerId
import org.mass.persistence.JdbcDomainEventStore
import java.time.Instant

/** Journal payloads of the draw (ADR-007 §8, ADR-008); previews are never journaled, only confirmed results. */
sealed interface DrawEvent {
    val competitionId: String
    val categoryId: String
    val bracketId: String

    /**
     * A confirmed draw of a category. [applicationsEventId] is the import it was drawn from; [system] is the operator's choice
     * (null where the format leaves none), [seed] replays [draw] before any later edit.
     */
    @Serializable
    data class Confirmed(
        override val competitionId: String,
        override val categoryId: String,
        override val bracketId: String,
        val applicationsEventId: String,
        val system: DrawSystem?,
        val method: DrawMethod,
        val seed: Long,
        val draw: CategoryDraw,
    ) : DrawEvent

    /** The operator swapped two entries of a confirmed draw before the start (`BRK-002`). */
    @Serializable
    data class Edited(
        override val competitionId: String,
        override val categoryId: String,
        override val bracketId: String,
        val first: String,
        val second: String,
        val before: List<String?>,
        val after: List<String?>,
    ) : DrawEvent

    /** The first bout of the category started (I2a): from now on the draw and edits are rejected (`BRK-002`, `CMP-006`). */
    @Serializable
    data class Started(
        override val competitionId: String,
        override val categoryId: String,
        override val bracketId: String,
    ) : DrawEvent
}

data class StoredDrawEvent(val eventId: String, val occurredAt: Instant, val event: DrawEvent)

interface CompetitionDrawJournal {
    /** Appends [events] atomically: a failed write leaves none of them in the journal. */
    fun append(events: List<StoredDrawEvent>)

    fun events(): List<StoredDrawEvent>

    class InMemory : CompetitionDrawJournal {
        private val stored = mutableListOf<StoredDrawEvent>()

        /** Set by tests to make the next append fail, like a lost database connection. */
        var failNextAppend = false

        override fun append(events: List<StoredDrawEvent>) {
            if (failNextAppend) {
                failNextAppend = false
                error("journal unavailable")
            }
            stored += events
        }

        override fun events(): List<StoredDrawEvent> = stored.toList()
    }
}

class JdbcCompetitionDrawJournal(
    private val store: JdbcDomainEventStore,
    private val peerId: PeerId,
) : CompetitionDrawJournal {
    override fun append(events: List<StoredDrawEvent>) {
        store.appendPeerEvents(
            events.map { stored ->
                val (type, payload) = when (val event = stored.event) {
                    is DrawEvent.Confirmed -> CONFIRMED to json.encodeToString(event)
                    is DrawEvent.Edited -> EDITED to json.encodeToString(event)
                    is DrawEvent.Started -> STARTED to json.encodeToString(event)
                }
                JdbcDomainEventStore.PeerScopedEvent(
                    eventId = EventId(stored.eventId),
                    peerId = peerId,
                    deviceId = null,
                    source = EventSource("operator"),
                    author = "operator",
                    occurredAt = stored.occurredAt,
                    type = type,
                    payload = payload,
                    competitionId = CompetitionId(stored.event.competitionId),
                )
            },
        )
    }

    override fun events(): List<StoredDrawEvent> =
        store.peerEvents(setOf(CONFIRMED, EDITED, STARTED)).map { stored ->
            val event: DrawEvent = when (stored.type) {
                CONFIRMED -> json.decodeFromString<DrawEvent.Confirmed>(stored.payload)
                EDITED -> json.decodeFromString<DrawEvent.Edited>(stored.payload)
                else -> json.decodeFromString<DrawEvent.Started>(stored.payload)
            }
            StoredDrawEvent(stored.eventId.value, stored.occurredAt, event)
        }

    private companion object {
        const val CONFIRMED = "category_draw_confirmed"
        const val EDITED = "bracket_edited"
        const val STARTED = "bracket_started"
        val json = Json { explicitNulls = false }
    }
}
