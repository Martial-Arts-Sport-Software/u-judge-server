package org.mass.domain

import java.time.Instant

data class DomainCommand(
    val competitionId: CompetitionId,
    val peerId: PeerId,
    val courtId: CourtId,
    val bracketId: BracketId,
    val sessionId: SessionId,
    val judgeId: JudgeId,
    val deviceId: DeviceId,
    val source: EventSource,
    val author: String,
    val type: String,
    val payload: String,
) {
    init {
        require(author.isNotBlank())
        require(type.isNotBlank())
        require(payload.isNotBlank())
    }

    fun toEvent(eventId: EventId, occurredAt: Instant): DomainEvent = DomainEvent(
        eventId = eventId,
        competitionId = competitionId,
        peerId = peerId,
        courtId = courtId,
        bracketId = bracketId,
        sessionId = sessionId,
        judgeId = judgeId,
        deviceId = deviceId,
        source = source,
        author = author,
        occurredAt = occurredAt,
        type = type,
        payload = payload,
    )
}
