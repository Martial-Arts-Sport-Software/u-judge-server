package org.mass.domain

import java.time.Instant

@JvmInline
value class EventSource(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

data class DomainEvent(
    val eventId: EventId,
    val competitionId: CompetitionId,
    val peerId: PeerId,
    val courtId: CourtId,
    val bracketId: BracketId,
    val sessionId: SessionId,
    val judgeId: JudgeId,
    val deviceId: DeviceId,
    val source: EventSource,
    val author: String,
    val occurredAt: Instant,
    val type: String,
    val payload: String,
) {
    init {
        require(author.isNotBlank())
        require(type.isNotBlank())
        require(payload.isNotBlank())
    }
}
