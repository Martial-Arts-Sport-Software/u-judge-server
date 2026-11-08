package org.mass.draw

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mass.domain.CompetitionId
import org.mass.domain.EventId
import org.mass.domain.EventSource
import org.mass.domain.PeerId
import org.mass.persistence.JdbcDomainEventStore
import java.time.Instant

/**
 * Journal payloads of the draw and the courts (ADR-007 §8, ADR-008); previews are never journaled, only confirmed results
 * and the operator's settings.
 */
sealed interface DrawEvent {
    val competitionId: String

    /** An event of one category's bracket. */
    sealed interface OfCategory : DrawEvent {
        val categoryId: String
        val bracketId: String
    }

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
    ) : OfCategory

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
    ) : OfCategory

    /** The first bout of the category started (I2a): from now on the draw and edits are rejected (`BRK-002`, `CMP-006`). */
    @Serializable
    data class Started(
        override val competitionId: String,
        override val categoryId: String,
        override val bracketId: String,
    ) : OfCategory

    /** The courts of the competition and the order of the categories on each (ADR-008 §1, §6). */
    @Serializable
    data class CourtsSet(override val competitionId: String, val plan: CourtPlan) : DrawEvent

    /** The court the desktop [peerId] judges (ADR-008 §1). */
    @Serializable
    data class DesktopCourtSelected(override val competitionId: String, val peerId: String, val court: Int) : DrawEvent

    /** Place, chief judge and chief secretary for the protocols (ADR-008 §8). */
    @Serializable
    data class OfficialsSet(override val competitionId: String, val officials: CompetitionOfficials) : DrawEvent

    /** The seal and signature images of the protocols (ADR-012 §5). */
    @Serializable
    data class ProtocolImagesSet(override val competitionId: String, val images: ProtocolImages) : DrawEvent

    /** The operator picked the next bout of [court] instead of the default one (ADR-008 §9, `SES-003`). */
    @Serializable
    data class NextBoutSelected(
        override val competitionId: String,
        val court: Int,
        override val categoryId: String,
        override val bracketId: String,
        val round: Int,
        val index: Int,
    ) : OfCategory
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
                    is DrawEvent.CourtsSet -> COURTS_SET to json.encodeToString(event)
                    is DrawEvent.DesktopCourtSelected -> DESKTOP_COURT_SELECTED to json.encodeToString(event)
                    is DrawEvent.NextBoutSelected -> NEXT_BOUT_SELECTED to json.encodeToString(event)
                    is DrawEvent.OfficialsSet -> OFFICIALS_SET to json.encodeToString(event)
                    is DrawEvent.ProtocolImagesSet -> PROTOCOL_IMAGES_SET to json.encodeToString(event)
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
        store.peerEvents(TYPES).map { stored ->
            val event: DrawEvent = when (stored.type) {
                CONFIRMED -> json.decodeFromString<DrawEvent.Confirmed>(stored.payload)
                EDITED -> json.decodeFromString<DrawEvent.Edited>(stored.payload)
                STARTED -> json.decodeFromString<DrawEvent.Started>(stored.payload)
                COURTS_SET -> json.decodeFromString<DrawEvent.CourtsSet>(stored.payload)
                DESKTOP_COURT_SELECTED -> json.decodeFromString<DrawEvent.DesktopCourtSelected>(stored.payload)
                NEXT_BOUT_SELECTED -> json.decodeFromString<DrawEvent.NextBoutSelected>(stored.payload)
                PROTOCOL_IMAGES_SET -> json.decodeFromString<DrawEvent.ProtocolImagesSet>(stored.payload)
                else -> json.decodeFromString<DrawEvent.OfficialsSet>(stored.payload)
            }
            StoredDrawEvent(stored.eventId.value, stored.occurredAt, event)
        }

    private companion object {
        const val CONFIRMED = "category_draw_confirmed"
        const val EDITED = "bracket_edited"
        const val STARTED = "bracket_started"
        const val COURTS_SET = "competition_courts_set"
        const val DESKTOP_COURT_SELECTED = "desktop_court_selected"
        const val NEXT_BOUT_SELECTED = "court_next_bout_selected"
        const val OFFICIALS_SET = "competition_officials_set"
        const val PROTOCOL_IMAGES_SET = "competition_protocol_images_set"
        val TYPES = setOf(
            CONFIRMED, EDITED, STARTED, COURTS_SET, DESKTOP_COURT_SELECTED, NEXT_BOUT_SELECTED, OFFICIALS_SET, PROTOCOL_IMAGES_SET,
        )
        val json = Json { explicitNulls = false }
    }
}
